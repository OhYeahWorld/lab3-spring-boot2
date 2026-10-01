package ru.lab3.accounting.repository.report;

import java.math.BigDecimal;
import java.util.List;

public record TurnoverApartmentView(
        Integer apartmentNumber,
        BigDecimal openingBalance,
        List<TurnoverCell> months,
        BigDecimal outgoingBalance) {
}
