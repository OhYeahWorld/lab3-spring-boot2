package ru.lab3.accounting.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.lab3.accounting.model.PendingImportRow;
import ru.lab3.accounting.service.PendingImportService;
import ru.lab3.accounting.service.StatementImportService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/data/import/errors")
public class PendingImportController {
    private static final Pattern ROW_FIELD = Pattern.compile("^rows\\[(\\d+)]\\.(\\w+)$");

    private final PendingImportService pendingImportService;
    private final StatementImportService importService;

    public PendingImportController(PendingImportService pendingImportService,
                                   StatementImportService importService) {
        this.pendingImportService = pendingImportService;
        this.importService = importService;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("rows", pendingImportService.list().stream()
                .map(ImportController.ParsedRowForm::from).toList());
        return "data/import-errors";
    }

    @PostMapping("/save")
    public String save(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            List<ImportController.ParsedRowForm> forms = readRows(request);
            if (forms.isEmpty()) {
                redirectAttributes.addFlashAttribute("error", "Нет строк для сохранения.");
                return "redirect:/data/import/errors";
            }
            List<PendingImportRow> rows = findRows(forms);
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).apply(forms.get(i).toRow());
                pendingImportService.save(rows.get(i));
            }
            redirectAttributes.addFlashAttribute("success", "Изменения сохранены во временном хранилище.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        } catch (DataAccessException ex) {
            redirectAttributes.addFlashAttribute("error", "Не удалось сохранить изменения: " + ex.getMessage());
        }
        return "redirect:/data/import/errors";
    }

    @PostMapping("/import")
    public String importFixed(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        List<ImportController.ParsedRowForm> forms = readRows(request);
        if (forms.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Нет строк для обработки.");
            return "redirect:/data/import/errors";
        }
        try {
            List<PendingImportRow> rows = findRows(forms);
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).apply(forms.get(i).toRow());
                pendingImportService.save(rows.get(i));
            }

            int importedPayments = 0;
            int importedCharges = 0;
            int remaining = 0;
            for (PendingImportRow row : rows) {
                if (StatementImportService.recomputeProblem(row.toImportRow()) != null) {
                    remaining++;
                    continue;
                }
                StatementImportService.ImportResult result =
                        importService.importRows(List.of(row.toImportRow()));
                if (result.problems().isEmpty()) {
                    importedPayments += result.paymentsCreated();
                    importedCharges += result.chargesCreated();
                    pendingImportService.delete(row.getId());
                } else {
                    row.setProblem(result.problems().get(0));
                    pendingImportService.save(row);
                    remaining++;
                }
            }
            redirectAttributes.addFlashAttribute("success",
                    "Загружено исправленных записей: платежей %d, начислений %d. Осталось в очереди: %d."
                            .formatted(importedPayments, importedCharges, remaining));
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        } catch (DataAccessException ex) {
            redirectAttributes.addFlashAttribute("error", "Ошибка обработки записей: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
        return "redirect:/data/import/errors";
    }

    private List<PendingImportRow> findRows(List<ImportController.ParsedRowForm> forms) {
        Map<Long, PendingImportRow> available = new HashMap<>();
        for (PendingImportRow row : pendingImportService.list()) {
            available.put(row.getId(), row);
        }
        Set<Long> seen = new HashSet<>();
        List<PendingImportRow> rows = new ArrayList<>(forms.size());
        for (ImportController.ParsedRowForm form : forms) {
            Long id = form.getPendingId();
            PendingImportRow row = id == null ? null : available.get(id);
            if (row == null || !seen.add(id)) {
                throw new IllegalArgumentException("Временные записи изменились. Обновите страницу и повторите.");
            }
            rows.add(row);
        }
        return rows;
    }

    private List<ImportController.ParsedRowForm> readRows(HttpServletRequest request) {
        Map<Integer, ImportController.ParsedRowForm> byIndex = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            Matcher matcher = ROW_FIELD.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ex) {
                continue;
            }
            String[] values = entry.getValue();
            String value = values == null || values.length == 0 ? "" : values[0];
            ImportController.ParsedRowForm form =
                    byIndex.computeIfAbsent(index, ignored -> new ImportController.ParsedRowForm());
            switch (matcher.group(2)) {
                case "pendingId" -> form.setPendingId(parseLong(value));
                case "orderNo" -> form.setOrderNo(parseInteger(value));
                case "date" -> form.setDate(value);
                case "time" -> form.setTime(value);
                case "incoming" -> form.setIncoming(value);
                case "amount" -> form.setAmount(value);
                case "apartment" -> form.setApartment(value);
                case "party" -> form.setParty(value);
                case "purpose" -> form.setPurpose(value);
                default -> {
                }
            }
        }
        return new ArrayList<>(byIndex.values());
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
