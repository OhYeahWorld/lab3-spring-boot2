package ru.lab3.accounting.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Инициализирует PostgreSQL напрямую через JDBC.
 *
 * Обычный Spring SQL-скриптер здесь не используется: в PostgreSQL-файле
 * есть PL/pgSQL-функции с внутренними ';'. Каждый полный SQL-файл выполняется
 * одним JDBC Statement. Сами файлы обернуты в BEGIN/COMMIT, поэтому неудачная
 * инициализация не оставляет частично созданную схему или частично загруженные
 * демонстрационные записи.
 */
@Component
public class DatabaseInitializer implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final int retries;
    private final long delayMs;

    public DatabaseInitializer(
            JdbcTemplate jdbcTemplate,
            @Value("${app.database.init.enabled:true}") boolean enabled,
            @Value("${app.database.init.retries:15}") int retries,
            @Value("${app.database.init.delay-ms:2000}") long delayMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.retries = Math.max(1, retries);
        this.delayMs = Math.max(0, delayMs);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!enabled) {
            log.info("Database initialization is disabled by app.database.init.enabled=false");
            return;
        }

        Exception last = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                jdbcTemplate.execute(read("schema.sql"));

                Integer businessRows = jdbcTemplate.queryForObject(
                        "select (select count(*) from saldo) + " +
                                "(select count(*) from charges) + " +
                                "(select count(*) from payments)",
                        Integer.class);

                log.info("Business tables contain {} rows; nothing is auto-loaded — " +
                        "all data comes from manual entry or Excel statement import.", businessRows);

                repairLegacyData();
                log.info("PostgreSQL schema/data initialization completed successfully (attempt {}).", attempt);
                return;
            } catch (Exception ex) {
                last = ex;
                log.warn("PostgreSQL initialization attempt {}/{} failed: {}", attempt, retries, rootMessage(ex));
                if (attempt < retries && delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            }
        }

        throw new IllegalStateException(
                "Не удалось подключиться к PostgreSQL или выполнить schema.sql. " +
                        "Проверьте DB_URL, DB_USER, DB_PASSWORD и запущенный PostgreSQL. Причина: " + rootMessage(last),
                last);
    }

    /**
     * Чистка баз, созданных прежними версиями приложения:
     * - удаляет строки платежей, дата которых не попадает в свой период
     *   (из-за них коммит транзакции падал на CHECK-ограничении);
     * - восстанавливает разорванные цепочки сальдо, чтобы старые «битые»
     *   данные больше никогда не отклоняли импорт или редактирование.
     */
    private void repairLegacyData() {
        Integer fixedPeriods = jdbcTemplate.queryForObject("""
                UPDATE payments
                   SET period = date_trunc('month', payment_time)::date
                 WHERE payment_time::date < period
                    OR payment_time::date >= (period + INTERVAL '1 month')::date
                """, Integer.class);
        if (fixedPeriods != null && fixedPeriods > 0) {
            log.info("Repaired {} legacy payment rows with mismatched period.", fixedPeriods);
        }

        // Пересчёт цепочек сальдо делает сервис при первом же изменении данных,
        // но дополнительно чиним очевидный разрыв «исходящее != входящее следующего».
        Integer repairedChains = jdbcTemplate.queryForObject("""
                WITH ordered AS (
                    SELECT id,
                           apartment_number,
                           closing_balance,
                           LEAD(opening_balance) OVER (PARTITION BY apartment_number
                                                       ORDER BY period) AS next_opening
                      FROM saldo
                )
                UPDATE saldo s
                   SET opening_balance = o.closing_balance
                  FROM ordered o
                 WHERE s.id = o.id
                   AND o.next_opening IS NOT NULL
                   AND o.next_opening <> o.closing_balance
                RETURNING 1
                """, Integer.class);
        if (repairedChains != null && repairedChains > 0) {
            log.info("Aligned {} broken saldo chain rows to previous closing balance.", repairedChains);
        }
    }

    private String read(String name) throws IOException {
        ClassPathResource resource = new ClassPathResource(name);
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return "неизвестная ошибка";
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
