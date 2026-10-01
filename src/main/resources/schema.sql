BEGIN;

-- Лабораторная №3. Схема БД и три хранимые процедуры.
-- В бизнес-схеме ровно три таблицы: saldo, charges, payments.

CREATE TABLE IF NOT EXISTS saldo (
    id BIGSERIAL PRIMARY KEY,
    apartment_number INTEGER NOT NULL CHECK (apartment_number > 0),
    period DATE NOT NULL CHECK (period = date_trunc('month', period)::date),
    opening_balance NUMERIC(14,2) NOT NULL,
    closing_balance NUMERIC(14,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_saldo_apartment_period UNIQUE (apartment_number, period)
);

CREATE TABLE IF NOT EXISTS charges (
    id BIGSERIAL PRIMARY KEY,
    apartment_number INTEGER NOT NULL CHECK (apartment_number > 0),
    period DATE NOT NULL CHECK (period = date_trunc('month', period)::date),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    description VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_charge_natural UNIQUE (apartment_number, period, amount, description)
);

CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    apartment_number INTEGER NOT NULL CHECK (apartment_number > 0),
    period DATE NOT NULL CHECK (period = date_trunc('month', period)::date),
    payment_date DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    payment_reference VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_natural UNIQUE (apartment_number, period, payment_date, amount, payment_reference),
    CONSTRAINT chk_payment_date_in_period CHECK (
        payment_date >= period AND payment_date < (period + INTERVAL '1 month')::date
    )
);

-- Помимо технических реквизитов запрещаем одинаковые "видимые" записи.
-- Например, нельзя занести два одинаковых начисления за одну квартиру и месяц,
-- даже если описание отличается.
CREATE UNIQUE INDEX IF NOT EXISTS uq_charge_visible
    ON charges(apartment_number, period, amount);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_visible
    ON payments(apartment_number, period, payment_date, amount);

CREATE INDEX IF NOT EXISTS ix_saldo_apartment_period ON saldo(apartment_number, period);
CREATE INDEX IF NOT EXISTS ix_charge_apartment_period ON charges(apartment_number, period);
CREATE INDEX IF NOT EXISTS ix_payment_apartment_period ON payments(apartment_number, period);

-- Системное время последнего изменения.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_saldo_updated_at ON saldo;
CREATE TRIGGER trg_saldo_updated_at
BEFORE UPDATE ON saldo
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_charges_updated_at ON charges;
CREATE TRIGGER trg_charges_updated_at
BEFORE UPDATE ON charges
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_payments_updated_at ON payments;
CREATE TRIGGER trg_payments_updated_at
BEFORE UPDATE ON payments
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Контроль связи периодов: исходящее сальдо одного имеющегося периода
-- обязано совпасть с входящим сальдо следующего имеющегося периода.
CREATE OR REPLACE FUNCTION validate_saldo_chain()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    prev_closing NUMERIC(14,2);
    next_opening NUMERIC(14,2);
BEGIN
    SELECT s.closing_balance
      INTO prev_closing
      FROM saldo s
     WHERE s.apartment_number = NEW.apartment_number
       AND s.period < NEW.period
       AND s.id <> COALESCE(NEW.id, -1)
     ORDER BY s.period DESC
     LIMIT 1;

    IF prev_closing IS NOT NULL AND NEW.opening_balance <> prev_closing THEN
        RAISE EXCEPTION 'Входящее сальдо за % для квартиры % должно быть равно исходящему предыдущего периода: %',
            NEW.period, NEW.apartment_number, prev_closing
            USING ERRCODE = '23514';
    END IF;

    SELECT s.opening_balance
      INTO next_opening
      FROM saldo s
     WHERE s.apartment_number = NEW.apartment_number
       AND s.period > NEW.period
       AND s.id <> COALESCE(NEW.id, -1)
     ORDER BY s.period ASC
     LIMIT 1;

    IF next_opening IS NOT NULL AND next_opening <> NEW.closing_balance THEN
        RAISE EXCEPTION 'Исходящее сальдо за % для квартиры % должно быть равно входящему следующего периода: %',
            NEW.period, NEW.apartment_number, next_opening
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_validate_saldo_chain ON saldo;
CREATE CONSTRAINT TRIGGER trg_validate_saldo_chain
AFTER INSERT OR UPDATE ON saldo
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_saldo_chain();

-- При повторном запуске поверх старой версии убираем старые сигнатуры функций,
-- потому что PostgreSQL не позволяет CREATE OR REPLACE менять состав RETURNS TABLE.
DROP FUNCTION IF EXISTS sp_turnover_statement(INTEGER);
DROP FUNCTION IF EXISTS sp_apartment_statement(INTEGER, INTEGER);
DROP FUNCTION IF EXISTS sp_debtors_summary(DATE);

-- ==============================================================
-- 1. Оборотная ведомость за год.
-- В ячейке месяца: начисления / платежи / входящее сальдо на начало месяца.
-- ==============================================================
DROP FUNCTION IF EXISTS sp_turnover_statement(INTEGER);
CREATE OR REPLACE FUNCTION sp_turnover_statement(p_year INTEGER)
RETURNS TABLE (
    apartment_number INTEGER,
    opening_balance NUMERIC(14,2),
    month_no INTEGER,
    month_start DATE,
    charges_total NUMERIC(14,2),
    payments_total NUMERIC(14,2),
    month_opening_balance NUMERIC(14,2),
    action_at TIMESTAMPTZ,
    outgoing_balance NUMERIC(14,2)
)
LANGUAGE sql
AS $$
WITH apartments AS (
    SELECT apartment_number FROM saldo WHERE EXTRACT(YEAR FROM period) = p_year
    UNION
    SELECT apartment_number FROM charges WHERE EXTRACT(YEAR FROM period) = p_year
    UNION
    SELECT apartment_number FROM payments WHERE EXTRACT(YEAR FROM period) = p_year
),
months AS (
    SELECT generate_series(1, 12)::INTEGER AS month_no
),
rows AS (
    SELECT
        a.apartment_number,
        m.month_no,
        make_date(p_year, m.month_no, 1) AS month_start,
        s.opening_balance,
        COALESCE((SELECT SUM(c.amount) FROM charges c
                   WHERE c.apartment_number = a.apartment_number
                     AND c.period = make_date(p_year, m.month_no, 1)), 0)::NUMERIC(14,2) AS charges_total,
        COALESCE((SELECT SUM(p.amount) FROM payments p
                   WHERE p.apartment_number = a.apartment_number
                     AND p.period = make_date(p_year, m.month_no, 1)), 0)::NUMERIC(14,2) AS payments_total,
        -- входящее сальдо месяца: из saldo, иначе — исходящее предыдущего имеющегося периода.
        COALESCE(s.opening_balance,
                 (SELECT prev.closing_balance
                    FROM saldo prev
                   WHERE prev.apartment_number = a.apartment_number
                     AND prev.period < make_date(p_year, m.month_no, 1)
                   ORDER BY prev.period DESC
                   LIMIT 1)) AS month_opening_balance,
        GREATEST(
            COALESCE(s.updated_at, '-infinity'::timestamptz),
            COALESCE((SELECT MAX(GREATEST(c.created_at, c.updated_at)) FROM charges c
                       WHERE c.apartment_number = a.apartment_number
                         AND c.period = make_date(p_year, m.month_no, 1)), '-infinity'::timestamptz),
            COALESCE((SELECT MAX(GREATEST(p.created_at, p.updated_at)) FROM payments p
                       WHERE p.apartment_number = a.apartment_number
                         AND p.period = make_date(p_year, m.month_no, 1)), '-infinity'::timestamptz)
        ) AS action_at
    FROM apartments a
    CROSS JOIN months m
    LEFT JOIN saldo s
      ON s.apartment_number = a.apartment_number
     AND s.period = make_date(p_year, m.month_no, 1)
),
opening AS (
    SELECT DISTINCT ON (apartment_number)
           apartment_number,
           opening_balance
      FROM rows
     WHERE opening_balance IS NOT NULL
     ORDER BY apartment_number, month_no
),
year_end AS (
    -- Исходящее за год: входящее сальдо января следующего года;
    -- если его нет — последнее известное входящее сальдо декабря.
    SELECT r.apartment_number,
           COALESCE((SELECT nx.opening_balance
                       FROM saldo nx
                      WHERE nx.apartment_number = r.apartment_number
                        AND nx.period = make_date(p_year + 1, 1, 1)),
                    r.month_opening_balance, 0)::NUMERIC(14,2) AS outgoing_balance
      FROM rows r
     WHERE r.month_no = 12
)
SELECT r.apartment_number,
       o.opening_balance,
       r.month_no,
       r.month_start,
       r.charges_total,
       r.payments_total,
       r.month_opening_balance,
       NULLIF(r.action_at, '-infinity'::timestamptz),
       y.outgoing_balance
  FROM rows r
  JOIN opening o USING (apartment_number)
  JOIN year_end y USING (apartment_number)
 ORDER BY r.apartment_number, r.month_no;
$$;

-- ==============================================================
-- 2. Начисления и платежи по квартире.
-- ==============================================================
CREATE OR REPLACE FUNCTION sp_apartment_statement(p_apartment INTEGER, p_year INTEGER)
RETURNS TABLE (
    month_no INTEGER,
    month_start DATE,
    opening_balance NUMERIC(14,2),
    charges_total NUMERIC(14,2),
    payments_total NUMERIC(14,2),
    closing_balance NUMERIC(14,2),
    action_at TIMESTAMPTZ
)
LANGUAGE sql
AS $$
WITH rows AS (
    SELECT
        gs::INTEGER AS month_no,
        make_date(p_year, gs::INTEGER, 1) AS month_start,
        s.opening_balance,
        COALESCE((SELECT SUM(c.amount) FROM charges c
                   WHERE c.apartment_number = p_apartment
                     AND c.period = make_date(p_year, gs::INTEGER, 1)), 0)::NUMERIC(14,2) AS charges_total,
        COALESCE((SELECT SUM(p.amount) FROM payments p
                   WHERE p.apartment_number = p_apartment
                     AND p.period = make_date(p_year, gs::INTEGER, 1)), 0)::NUMERIC(14,2) AS payments_total,
        s.closing_balance,
        GREATEST(
            COALESCE(s.updated_at, '-infinity'::timestamptz),
            COALESCE((SELECT MAX(GREATEST(c.created_at, c.updated_at)) FROM charges c
                       WHERE c.apartment_number = p_apartment
                         AND c.period = make_date(p_year, gs::INTEGER, 1)), '-infinity'::timestamptz),
            COALESCE((SELECT MAX(GREATEST(p.created_at, p.updated_at)) FROM payments p
                       WHERE p.apartment_number = p_apartment
                         AND p.period = make_date(p_year, gs::INTEGER, 1)), '-infinity'::timestamptz)
        ) AS action_at
      FROM generate_series(1, 12) gs
      LEFT JOIN saldo s
        ON s.apartment_number = p_apartment
       AND s.period = make_date(p_year, gs::INTEGER, 1)
)
SELECT month_no,
       month_start,
       opening_balance,
       charges_total,
       payments_total,
       closing_balance,
       NULLIF(action_at, '-infinity'::timestamptz)
  FROM rows
 ORDER BY month_no;
$$;

-- ==============================================================
-- 3. Сводка по категориям должников.
-- Для даты 01.10.2017 последний полный месяц = сентябрь 2017.
-- Категория = ceil(положительное сальдо / начисление последнего полного месяца).
-- ==============================================================
CREATE OR REPLACE FUNCTION sp_debtors_summary(p_as_of DATE)
RETURNS TABLE (
    apartment_number INTEGER,
    last_month_charge NUMERIC(14,2),
    balance NUMERIC(14,2),
    one_month NUMERIC(14,2),
    two_months NUMERIC(14,2),
    three_months NUMERIC(14,2),
    over_three_months NUMERIC(14,2),
    debt_months NUMERIC(12,2),
    debt_category VARCHAR(20),
    action_at TIMESTAMPTZ
)
LANGUAGE sql
AS $$
WITH period_limit AS (
    SELECT date_trunc('month', p_as_of)::DATE AS month_limit
),
last_saldo AS (
    SELECT DISTINCT ON (s.apartment_number)
           s.apartment_number,
           s.closing_balance,
           s.updated_at
      FROM saldo s, period_limit lim
     WHERE s.period < lim.month_limit
     ORDER BY s.apartment_number, s.period DESC
),
last_charge_period AS (
    SELECT DISTINCT ON (c.apartment_number)
           c.apartment_number,
           c.period
      FROM charges c, period_limit lim
     WHERE c.period < lim.month_limit
     ORDER BY c.apartment_number, c.period DESC
),
last_charge AS (
    SELECT c.apartment_number,
           c.period,
           SUM(c.amount)::NUMERIC(14,2) AS amount,
           MAX(GREATEST(c.created_at, c.updated_at)) AS action_at
      FROM charges c
      JOIN last_charge_period lp
        ON lp.apartment_number = c.apartment_number
       AND lp.period = c.period
     GROUP BY c.apartment_number, c.period
),
calc AS (
    SELECT s.apartment_number,
           c.amount AS last_month_charge,
           s.closing_balance AS balance,
           CEIL(s.closing_balance / NULLIF(c.amount, 0))::NUMERIC(12,2) AS debt_months,
           GREATEST(s.updated_at, c.action_at) AS action_at
      FROM last_saldo s
      JOIN last_charge c ON c.apartment_number = s.apartment_number
     WHERE s.closing_balance > 0
       AND c.amount > 0
),
classified AS (
    SELECT *,
           CASE
               WHEN debt_months <= 1 THEN '1 месяц'
               WHEN debt_months = 2 THEN '2 месяца'
               WHEN debt_months = 3 THEN '3 месяца'
               ELSE 'Свыше 3 месяцев'
           END::VARCHAR(20) AS debt_category
      FROM calc
)
SELECT apartment_number,
       last_month_charge,
       balance,
       CASE WHEN debt_category = '1 месяц' THEN balance ELSE NULL END,
       CASE WHEN debt_category = '2 месяца' THEN balance ELSE NULL END,
       CASE WHEN debt_category = '3 месяца' THEN balance ELSE NULL END,
       CASE WHEN debt_category = 'Свыше 3 месяцев' THEN balance ELSE NULL END,
       debt_months,
       debt_category,
       action_at
  FROM classified
 ORDER BY apartment_number;
$$;

COMMIT;
