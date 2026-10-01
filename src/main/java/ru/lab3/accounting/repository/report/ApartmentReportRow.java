package ru.lab3.accounting.repository.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ApartmentReportRow(
        Integer monthNo,
        LocalDate monthStart,
        BigDecimal openingBalance,
        BigDecimal charges,
        BigDecimal payments,
        BigDecimal closingBalance,
        Instant actionAt) {
}
