package ru.lab3.accounting.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.lab3.accounting.model.PendingImportRow;
import ru.lab3.accounting.repository.PendingImportRowRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PendingImportServiceTest {
    @Test
    void savesOnlyRowsWithImportProblems() {
        PendingImportRowRepository repository = mock(PendingImportRowRepository.class);
        PendingImportService service = new PendingImportService(repository);
        StatementImportService.ImportRow valid = new StatementImportService.ImportRow(
                1, LocalDate.of(2026, 1, 1), LocalDateTime.of(2026, 1, 1, 0, 0),
                new BigDecimal("10.00"), "Плательщик", "Оплата", true, 1, null);
        StatementImportService.ImportRow unresolved = new StatementImportService.ImportRow(
                2, LocalDate.of(2026, 1, 1), LocalDateTime.of(2026, 1, 1, 0, 0),
                new BigDecimal("10.00"), "Плательщик", "Оплата", null, 1,
                StatementImportService.PROBLEM_TYPE);

        int saved = service.saveUnresolved("statement.xlsx", List.of(valid, unresolved));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PendingImportRow>> rowsCaptor =
                (ArgumentCaptor<Iterable<PendingImportRow>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(rowsCaptor.capture());
        List<PendingImportRow> rows = new java.util.ArrayList<>();
        rowsCaptor.getValue().forEach(rows::add);
        assertEquals(1, saved);
        assertEquals(1, rows.size());
        assertEquals("statement.xlsx", rows.get(0).getSourceFile());
        assertEquals(StatementImportService.PROBLEM_TYPE, rows.get(0).getProblem());
    }
}
