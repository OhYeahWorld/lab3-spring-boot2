package ru.lab3.accounting.repository.report;

import java.math.BigDecimal;
import java.time.Instant;

public record TurnoverCell(
        int monthNo,
        BigDecimal charges,
        BigDecimal payments,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        Instant actionAt,
        boolean present) {
}
