package ru.lab3.accounting.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.lab3.accounting.repository.report.ApartmentReportRow;
import ru.lab3.accounting.repository.report.DebtorReportRow;
import ru.lab3.accounting.repository.report.TurnoverRow;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class ReportRepository {
    private final JdbcTemplate jdbcTemplate;

    public ReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TurnoverRow> turnover(int year) {
        return jdbcTemplate.query(
                "select apartment_number, opening_balance, month_no, month_start, charges_total, payments_total, month_opening_balance, action_at, outgoing_balance " +
                        "from sp_turnover_statement(?)",
                ps -> ps.setInt(1, year),
                (rs, rowNum) -> new TurnoverRow(
                        rs.getInt("apartment_number"),
                        rs.getBigDecimal("opening_balance"),
                        rs.getInt("month_no"),
                        rs.getBigDecimal("charges_total"),
                        rs.getBigDecimal("payments_total"),
                        rs.getBigDecimal("month_opening_balance"),
                        toInstant(rs.getTimestamp("action_at")),
                        rs.getBigDecimal("outgoing_balance"))
        );
    }

    public List<ApartmentReportRow> apartment(int apartment, int year) {
        return jdbcTemplate.query(
                "select month_no, month_start, opening_balance, charges_total, payments_total, closing_balance, action_at " +
                        "from sp_apartment_statement(?, ?)",
                ps -> {
                    ps.setInt(1, apartment);
                    ps.setInt(2, year);
                },
                (rs, rowNum) -> new ApartmentReportRow(
                        rs.getInt("month_no"),
                        rs.getObject("month_start", java.time.LocalDate.class),
                        rs.getBigDecimal("opening_balance"),
                        rs.getBigDecimal("charges_total"),
                        rs.getBigDecimal("payments_total"),
                        rs.getBigDecimal("closing_balance"),
                        toInstant(rs.getTimestamp("action_at")))
        );
    }

    public List<DebtorReportRow> debtors(java.time.LocalDate asOf) {
        return jdbcTemplate.query(
                "select apartment_number, last_month_charge, balance, one_month, two_months, three_months, over_three_months, " +
                        "debt_months, debt_category, action_at from sp_debtors_summary(?)",
                ps -> ps.setObject(1, asOf),
                (rs, rowNum) -> new DebtorReportRow(
                        rs.getInt("apartment_number"),
                        rs.getBigDecimal("last_month_charge"),
                        rs.getBigDecimal("balance"),
                        rs.getBigDecimal("one_month"),
                        rs.getBigDecimal("two_months"),
                        rs.getBigDecimal("three_months"),
                        rs.getBigDecimal("over_three_months"),
                        rs.getBigDecimal("debt_months"),
                        rs.getString("debt_category"),
                        toInstant(rs.getTimestamp("action_at")))
        );
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
