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

                if (businessRows != null && businessRows == 0) {
                    jdbcTemplate.execute(read("data.sql"));
                    log.info("Empty database detected: demo data loaded automatically.");
                } else {
                    log.info("Database already contains {} business rows: demo data was not added.", businessRows);
                }

                validateBalances();
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
                "Не удалось подключиться к PostgreSQL или выполнить schema.sql/data.sql. " +
                        "Проверьте DB_URL, DB_USER, DB_PASSWORD и запущенный PostgreSQL. Причина: " + rootMessage(last),
                last);
    }

    /**
     * Проверяет ключевое правило лабораторной: сумма закрытия периода должна
     * совпадать с открытием следующего существующего периода, а закрытие
     * каждого периода должно соответствовать формуле с начислениями и платежами.
     */
    private void validateBalances() {
        Integer formulaErrors = jdbcTemplate.queryForObject("""
                select count(*)
                  from saldo s
                 where s.closing_balance <> round(
                     (s.opening_balance
                      + coalesce((select sum(c.amount) from charges c
                                   where c.apartment_number = s.apartment_number
                                     and c.period = s.period), 0)
                      - coalesce((select sum(p.amount) from payments p
                                   where p.apartment_number = s.apartment_number
                                     and p.period = s.period), 0)), 2)
                """, Integer.class);

        Integer chainErrors = jdbcTemplate.queryForObject("""
                select count(*)
                  from saldo current_saldo
                  join lateral (
                      select previous_saldo.closing_balance
                        from saldo previous_saldo
                       where previous_saldo.apartment_number = current_saldo.apartment_number
                         and previous_saldo.period < current_saldo.period
                       order by previous_saldo.period desc
                       limit 1
                  ) previous_saldo on true
                 where current_saldo.opening_balance <> previous_saldo.closing_balance
                """, Integer.class);

        if ((formulaErrors != null && formulaErrors > 0) || (chainErrors != null && chainErrors > 0)) {
            throw new IllegalStateException(
                    "Проверка БД не пройдена: ошибок формулы сальдо = " +
                            (formulaErrors == null ? 0 : formulaErrors) +
                            ", ошибок связи периодов = " +
                            (chainErrors == null ? 0 : chainErrors) + ".");
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
