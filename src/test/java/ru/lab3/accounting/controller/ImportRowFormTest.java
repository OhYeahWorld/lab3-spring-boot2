package ru.lab3.accounting.controller;

import org.junit.jupiter.api.Test;
import ru.lab3.accounting.service.StatementImportService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImportRowFormTest {
    @Test
    void keepsUnknownOperationTypeUnresolved() {
        StatementImportService.ImportRow parsed = new StatementImportService.ImportRow(
                1, LocalDate.of(2026, 1, 1), LocalDateTime.of(2026, 1, 1, 0, 0),
                new BigDecimal("10.00"), "", "", null, 1, null);

        ImportController.ParsedRowForm form = ImportController.ParsedRowForm.from(parsed);
        StatementImportService.ImportRow restored = form.toRow();

        assertNull(restored.typeKnown());
        assertEquals(StatementImportService.PROBLEM_TYPE,
                StatementImportService.recomputeProblem(restored));
    }

    @Test
    void preservesExplicitOutgoingOperationType() {
        ImportController.ParsedRowForm form = new ImportController.ParsedRowForm();
        form.setDate("2026-01-01");
        form.setAmount("10.00");
        form.setApartment("1");
        form.setIncoming("0");

        StatementImportService.ImportRow restored = form.toRow();

        assertEquals(Boolean.FALSE, restored.typeKnown());
        assertNull(StatementImportService.recomputeProblem(restored));
    }
}
