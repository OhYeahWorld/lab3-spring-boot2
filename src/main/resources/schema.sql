BEGIN;

-- =============================================================
-- СХЕМА, СОБСТВЕННАЯ ПРИЛОЖЕНИЮ (Spring Data JPA / Hibernate).
-- Единственный источник структуры таблиц — этот файл.
-- ddl-auto=none, поэтому колонки должны точно соответствовать
-- сущностям Charge / Payment / Saldo (включая @Version).
-- =============================================================

CREATE TABLE IF NOT EXISTS saldo (
    id BIGSERIAL PRIMARY KEY,
    apartment_number INTEGER NOT NULL CHECK (apartment_number > 0),
    period DATE NOT NULL CHECK (period = date_trunc('month', period)::date),
    opening_balance NUMERIC(14,2) NOT NULL DEFAULT 0,
    closing_balance NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS charges (
    id BIGSERIAL PRIMARY KEY,
    apartment_number INTEGER NOT NULL CHECK (apartment_number > 0),
    period DATE NOT NULL CHECK (period = date_trunc('month', period)::date),
    amount NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    description VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    apartment_number INTEGER NOT NULL CHECK (apartment_number > 0),
    period DATE NOT NULL CHECK (period = date_trunc('month', period)::date),
    payment_time TIMESTAMP NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount >= 0),
    payment_reference VARCHAR(255) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS pending_import_rows (
    id BIGSERIAL PRIMARY KEY,
    source_file TEXT NOT NULL DEFAULT '',
    order_no INTEGER NOT NULL,
    operation_date DATE,
    executed_at TIMESTAMP,
    amount NUMERIC(14,2),
    party TEXT NOT NULL DEFAULT '',
    purpose TEXT NOT NULL DEFAULT '',
    type_known BOOLEAN,
    apartment_number INTEGER,
    problem TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS ix_pending_import_rows_created_at
    ON pending_import_rows(created_at, id);

-- ------------------------------------------------------------
-- Миграции поверх баз, созданных предыдущими версиями схемы.
-- Всё идемпотентно: повторный запуск безопасен.
-- ------------------------------------------------------------

-- Колонка version появилась вместе с @Version в сущностях.
ALTER TABLE saldo    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE charges  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE charges  ALTER COLUMN description TYPE VARCHAR(1000);
ALTER TABLE payments ALTER COLUMN payment_reference SET DATA TYPE VARCHAR(255);
ALTER TABLE payments ALTER COLUMN payment_reference SET DEFAULT '';

-- Старые «жёсткие» ограничения уникальности: в реальной выписке по квартире
-- могут быть несколько одинаковых платежей — приложение должно их принимать.
ALTER TABLE charges  DROP CONSTRAINT IF EXISTS uq_charge_natural;
ALTER TABLE payments DROP CONSTRAINT IF EXISTS uq_payment_natural;
ALTER TABLE charges  DROP CONSTRAINT IF EXISTS uq_charge_visible;
ALTER TABLE payments DROP CONSTRAINT IF EXISTS uq_payment_visible;
ALTER TABLE saldo    DROP CONSTRAINT IF EXISTS uq_saldo_apartment_period;
DROP INDEX IF EXISTS uq_charge_visible;
DROP INDEX IF EXISTS uq_payment_visible;
DROP INDEX IF EXISTS uq_saldo_apartment_period;

-- Миграция payment_date (DATE) -> payment_time (TIMESTAMP).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'payments' AND column_name = 'payment_date')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'payments' AND column_name = 'payment_time') THEN
        ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payment_date_in_period;
        UPDATE payments SET payment_date = payment_date + TIME '10:30';
        ALTER TABLE payments RENAME COLUMN payment_date TO payment_time;
        ALTER TABLE payments ALTER COLUMN payment_time TYPE TIMESTAMP USING payment_time::timestamp;
    END IF;
END $$;

-- Ограничение «дата платежа внутри периода» применяется только к новым
-- записям; старые нестыкующиеся строки не блокируют старт приложения.
ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payment_date_in_period;
DO $$
BEGIN
    ALTER TABLE payments
        ADD CONSTRAINT chk_payment_date_in_period CHECK (
            payment_time::date >= period AND payment_time::date < (period + INTERVAL '1 month')::date
        ) NOT VALID;
EXCEPTION WHEN others THEN NULL;
END $$;

-- Индексы для выборок и пересчёта сальдо.
CREATE INDEX IF NOT EXISTS ix_saldo_apartment_period ON saldo(apartment_number, period);
CREATE INDEX IF NOT EXISTS ix_charge_apartment_period ON charges(apartment_number, period);
CREATE INDEX IF NOT EXISTS ix_payment_apartment_period ON payments(apartment_number, period);

-- ------------------------------------------------------------
-- ТРИГГЕРЫ БД УДАЛЕНЫ ПОЛНОСТЬЮ.
-- Раньше validate_saldo_chain() отклонял всю транзакцию импорта из-за одной
-- нестыкующейся старой строки («Unable to commit against JDBC Connection»).
-- Целостность цепочки сальдо теперь обеспечивает приложение (LedgerService):
-- при любом изменении начислений/платежей пересчитываются все последующие
-- периоды, а данные можно редактировать без ошибок коммита.
-- ------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_validate_saldo_chain ON saldo;
DROP FUNCTION IF EXISTS validate_saldo_chain();

-- Системное время последнего изменения.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $fn$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END
$fn$;

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

-- =============================================================
-- Хранимые процедуры отчётов (лабораторная работа №3).
-- При повторном запуске убираем старые сигнатуры: PostgreSQL не позволяет
-- CREATE OR REPLACE менять состав RETURNS TABLE.
-- =============================================================
DROP FUNCTION IF EXISTS sp_turnover_statement(INTEGER);
DROP FUNCTION IF EXISTS sp_apartment_statement(INTEGER, INTEGER);
DROP FUNCTION IF EXISTS sp_debtors_summary(DATE);

CREATE OR REPLACE FUNCTION sp_turnover_statement(p_year INTEGER)
RETURNS TABLE (
    apartment_number INTEGER,
    opening_balance NUMERIC(14,2),
    month_no INTEGER,
    month_start DATE,
    charges_total NUMERIC(14,2),
    payments_total NUMERIC(14,2),
    month_opening_balance NUMERIC(14,2),
    month_closing_balance NUMERIC(14,2),
    action_at TIMESTAMPTZ,
    outgoing_balance NUMERIC(14,2)
)
LANGUAGE sql
AS $fn$
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
        COALESCE(s.opening_balance,
                 (SELECT prev.closing_balance
                    FROM saldo prev
                   WHERE prev.apartment_number = a.apartment_number
                     AND prev.period < make_date(p_year, m.month_no, 1)
                   ORDER BY prev.period DESC
                   LIMIT 1)) AS month_opening_balance,
        COALESCE(s.closing_balance,
                 COALESCE(s.opening_balance,
                          (SELECT prev.closing_balance
                             FROM saldo prev
                            WHERE prev.apartment_number = a.apartment_number
                              AND prev.period < make_date(p_year, m.month_no, 1)
                            ORDER BY prev.period DESC
                            LIMIT 1), 0)
                 + COALESCE((SELECT SUM(c.amount) FROM charges c
                              WHERE c.apartment_number = a.apartment_number
                                AND c.period = make_date(p_year, m.month_no, 1)), 0)
                 - COALESCE((SELECT SUM(p.amount) FROM payments p
                              WHERE p.apartment_number = a.apartment_number
                                AND p.period = make_date(p_year, m.month_no, 1)), 0)
                )::NUMERIC(14,2) AS month_closing_balance,
        GREATEST(
            COALESCE(s.updated_at, '-infinity'::timestamptz),
            COALESCE((SELECT MAX(c.updated_at) FROM charges c
                       WHERE c.apartment_number = a.apartment_number
                         AND c.period = make_date(p_year, m.month_no, 1)), '-infinity'::timestamptz),
            COALESCE((SELECT MAX(p.updated_at) FROM payments p
                       WHERE p.apartment_number = a.apartment_number
                         AND p.period = make_date(p_year, m.month_no, 1)), '-infinity'::timestamptz)
        ) AS action_at
    FROM apartments a
    CROSS JOIN months m
    LEFT JOIN saldo s ON s.apartment_number = a.apartment_number
                     AND s.period = make_date(p_year, m.month_no, 1)
)
SELECT apartment_number,
       opening_balance,
       month_no,
       month_start,
       charges_total,
       payments_total,
       month_opening_balance,
       month_closing_balance,
       action_at,
       month_closing_balance AS outgoing_balance
  FROM rows
 ORDER BY apartment_number, month_no;
$fn$;

-- =============================================================
-- 2. Выписка по квартире за год.
-- =============================================================
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
AS $fn$
WITH months AS (
    SELECT generate_series(1, 12)::INTEGER AS month_no
),
base AS (
    SELECT
        m.month_no,
        make_date(p_year, m.month_no, 1) AS month_start,
        s.opening_balance,
        COALESCE((SELECT SUM(c.amount) FROM charges c
                   WHERE c.apartment_number = p_apartment
                     AND c.period = make_date(p_year, m.month_no, 1)), 0)::NUMERIC(14,2) AS charges_total,
        COALESCE((SELECT SUM(p.amount) FROM payments p
                   WHERE p.apartment_number = p_apartment
                     AND p.period = make_date(p_year, m.month_no, 1)), 0)::NUMERIC(14,2) AS payments_total,
        COALESCE(s.closing_balance,
                 COALESCE(s.opening_balance,
                          (SELECT prev.closing_balance
                             FROM saldo prev
                            WHERE prev.apartment_number = p_apartment
                              AND prev.period < make_date(p_year, m.month_no, 1)
                            ORDER BY prev.period DESC
                            LIMIT 1), 0)
                 + COALESCE((SELECT SUM(c.amount) FROM charges c
                              WHERE c.apartment_number = p_apartment
                                AND c.period = make_date(p_year, m.month_no, 1)), 0)
                 - COALESCE((SELECT SUM(p.amount) FROM payments p
                              WHERE p.apartment_number = p_apartment
                                AND p.period = make_date(p_year, m.month_no, 1)), 0)
                )::NUMERIC(14,2) AS closing_balance,
        GREATEST(
            COALESCE(s.updated_at, '-infinity'::timestamptz),
            COALESCE((SELECT MAX(c.updated_at) FROM charges c
                       WHERE c.apartment_number = p_apartment
                         AND c.period = make_date(p_year, m.month_no, 1)), '-infinity'::timestamptz),
            COALESCE((SELECT MAX(p.updated_at) FROM payments p
                       WHERE p.apartment_number = p_apartment
                         AND p.period = make_date(p_year, m.month_no, 1)), '-infinity'::timestamptz)
        ) AS action_at
    FROM months m
    LEFT JOIN saldo s ON s.apartment_number = p_apartment
                     AND s.period = make_date(p_year, m.month_no, 1)
)
SELECT month_no,
       month_start,
       opening_balance,
       charges_total,
       payments_total,
       closing_balance,
       action_at
  FROM base
 ORDER BY month_no;
$fn$;

-- =============================================================
-- 3. Ведомость должников на указанную дату.
-- =============================================================
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
AS $fn$
WITH last_saldo AS (
    SELECT DISTINCT ON (s.apartment_number)
           s.apartment_number,
           s.period,
           s.closing_balance,
           s.updated_at
      FROM saldo s
     WHERE s.period <= date_trunc('month', p_as_of)::date
     ORDER BY s.apartment_number, s.period DESC
),
last_charge AS (
    SELECT DISTINCT ON (c.apartment_number)
           c.apartment_number,
           c.amount
      FROM charges c
     WHERE c.period <= date_trunc('month', p_as_of)::date
     ORDER BY c.apartment_number, c.period DESC, c.id DESC
),
calc AS (
    SELECT s.apartment_number,
           c.amount AS last_month_charge,
           s.closing_balance AS balance,
           CEIL(s.closing_balance / NULLIF(c.amount, 0))::NUMERIC(12,2) AS debt_months,
           GREATEST(s.updated_at,
                    COALESCE((SELECT MAX(x.updated_at) FROM charges x
                               WHERE x.apartment_number = s.apartment_number), '-infinity'::timestamptz)) AS action_at
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
$fn$;

COMMIT;
