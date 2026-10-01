package ru.lab3.accounting.repository.report;

import java.math.BigDecimal;
import java.time.Instant;

public record DebtorReportRow(
        Integer apartmentNumber,
        BigDecimal lastMonthCharge,
        BigDecimal balance,
        BigDecimal oneMonth,
        BigDecimal twoMonths,
        BigDecimal threeMonths,
        BigDecimal overThreeMonths,
        BigDecimal debtMonths,
        String debtCategory,
        Instant actionAt) {
}
