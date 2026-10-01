package ru.lab3.accounting.repository.report;

import java.math.BigDecimal;
import java.time.Instant;

public record TurnoverRow(
        Integer apartmentNumber,
        BigDecimal openingBalance,
        Integer monthNo,
        BigDecimal charges,
        BigDecimal payments,
        BigDecimal monthOpeningBalance,
        BigDecimal monthClosingBalance,
        Instant actionAt,
        BigDecimal outgoingBalance) {
}
