package ru.lab3.accounting.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.lab3.accounting.service.LedgerService;
import ru.lab3.accounting.service.StatementImportService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/data/import")
public class ImportController {

    private final StatementImportService importService;
    private final LedgerService ledgerService;

    public ImportController(StatementImportService importService, LedgerService ledgerService) {
        this.importService = importService;
        this.ledgerService = ledgerService;
    }

    @GetMapping
    public String page(Model model) {
        if (!model.containsAttribute("parsedRows")) {
            model.addAttribute("parsedRows", List.of());
        }
        return "data/import";
    }

    @PostMapping("/parse")
    public String parse(@RequestParam("file") MultipartFile file, Model model) {
        try {
            List<ParsedRowForm> rows = importService.parse(file).stream()
                    .map(ParsedRowForm::from)
                    .toList();
            model.addAttribute("parsedRows", rows);
            model.addAttribute("summary", summarize(rows));
            model.addAttribute("fileName", file.getOriginalFilename());
        } catch (Exception ex) {
            model.addAttribute("error", "Не удалось разобрать выписку: " + friendly(ex));
            model.addAttribute("parsedRows", List.of());
        }
        return page(model);
    }

    /** Правка строк предпросмотра прямо на сайте — без импорта в базу. */
    @PostMapping("/edit")
    public String edit(HttpServletRequest request,
                       @RequestParam(value = "fileName", required = false) String fileName,
                       Model model) {
        List<ParsedRowForm> safe = readRowsFromRequest(request);
        if (safe.isEmpty()) {
            model.addAttribute("error", "Нет данных для редактирования — сначала загрузите файл.");
            model.addAttribute("parsedRows", List.of());
            return page(model);
        }
        model.addAttribute("parsedRows", safe);
        model.addAttribute("summary", summarize(safe));
        model.addAttribute("fileName", fileName == null ? "" : fileName);
        model.addAttribute("notice", "Правки сохранены в таблице. Проверьте данные и нажмите «Импортировать в базу».");
        return page(model);
    }

    @PostMapping("/commit")
    public String commit(HttpServletRequest request,
                         Model model,
                         RedirectAttributes ra) {
        List<ParsedRowForm> safe = readRowsFromRequest(request);
        if (safe.isEmpty()) {
            // Форма пришла без строк — показываем подсказку вместо пустого редиректа.
            model.addAttribute("parsedRows", List.of());
            model.addAttribute("error", "Не удалось прочитать строки таблицы. "
                    + "Проверьте данные и попробуйте ещё раз, либо загрузите файл заново.");
            return page(model);
        }
        try {
            List<StatementImportService.ImportRow> importRows = new ArrayList<>();
            for (ParsedRowForm f : safe) {
                importRows.add(f.toRow());
            }
            StatementImportService.ImportResult result = importService.importRows(importRows);
            StringBuilder msg = new StringBuilder("Импорт завершён: платежей %d, начислений %d, сальдо %d."
                    .formatted(result.paymentsCreated(), result.chargesCreated(), result.saldoCreated()));
            if (!result.problems().isEmpty()) {
                msg.append(" Пропущено строк: ").append(result.problems().size()).append('.');
                ra.addFlashAttribute("error", "Пропущенные строки: "
                        + String.join("; ", result.problems().subList(0, Math.min(20, result.problems().size()))));
            }
            ra.addFlashAttribute("success", msg.toString());
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("error", "Ошибка импорта: " + friendly(ex));
        }
        return "redirect:/data/import";
    }

    private static final Pattern ROW_INDEX_PATTERN = Pattern.compile("^rows\\[(\\d+)\\]\\.(\\w+)$");

    /**
     * Читает строки таблицы прямо из POST-параметров вида rows[0].date, rows[1].amount и т.д.
     * Такой подход не зависит от механизмов привязки данных Spring и работает всегда.
     */
    private static List<ParsedRowForm> readRowsFromRequest(HttpServletRequest request) {
        Map<Integer, ParsedRowForm> byIndex = new LinkedHashMap<>();
        for (String name : request.getParameterMap().keySet()) {
            Matcher m = ROW_INDEX_PATTERN.matcher(name);
            if (!m.matches()) {
                continue;
            }
            int index = Integer.parseInt(m.group(1));
            String field = m.group(2);
            String value = request.getParameter(name);
            ParsedRowForm f = byIndex.computeIfAbsent(index, k -> new ParsedRowForm());
            switch (field) {
                case "orderNo" -> {
                    if (value != null && !value.isBlank()) {
                        try {
                            f.setOrderNo(Integer.parseInt(value.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                case "date" -> f.setDate(value == null ? "" : value);
                case "time" -> f.setTime(value == null ? "" : value);
                case "amount" -> f.setAmount(value == null ? "" : value);
                case "party" -> f.setParty(value == null ? "" : value);
                case "purpose" -> f.setPurpose(value == null ? "" : value);
                case "incoming" -> f.setIncoming(value == null ? "" : value);
                case "apartment" -> f.setApartment(value == null ? "" : value);
                case "problem" -> f.setProblem(value == null ? "" : value);
                default -> {
                }
            }
        }
        List<ParsedRowForm> result = new ArrayList<>();
        int index = 0;
        for (ParsedRowForm f : byIndex.values()) {
            if (isBlankForm(f)) {
                continue; // полностью пустая строка — пропускаем
            }
            if (f.getOrderNo() == null) {
                f.setOrderNo(++index);
            } else {
                index = f.getOrderNo();
            }
            result.add(f);
        }
        return result;
    }

    /** Форма, в которую не попало ни одно значение. */
    private static boolean isBlankForm(ParsedRowForm row) {
        return row.getOrderNo() == null
                && isBlank(row.getDate()) && isBlank(row.getTime()) && isBlank(row.getAmount())
                && isBlank(row.getParty()) && isBlank(row.getPurpose()) && isBlank(row.getIncoming())
                && isBlank(row.getApartment()) && isBlank(row.getProblem());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ==================== форма предпросмотра ====================

    /** Строка таблицы предпросмотра: все поля редактируются прямо на сайте. */
    public static class ParsedRowForm {
        private Integer orderNo;
        private String date = "";
        private String time = "";
        private String amount = "";
        private String party = "";
        private String purpose = "";
        private String incoming = "";
        private String apartment = "";
        private String problem = "";

        public static ParsedRowForm from(StatementImportService.ImportRow row) {
            ParsedRowForm f = new ParsedRowForm();
            f.orderNo = row.orderNo();
            f.date = row.date() == null ? "" : row.date().toString();
            f.time = row.executedAt() == null ? ""
                    : String.format("%02d:%02d", row.executedAt().getHour(), row.executedAt().getMinute());
            f.amount = row.amount() == null ? "" : row.amount().toPlainString();
            f.party = row.party() == null ? "" : row.party();
            f.purpose = row.purpose() == null ? "" : row.purpose();
            f.incoming = row.problem() != null && row.date() == null && row.amount() == null
                    ? "" : (row.incoming() ? "1" : "0");
            f.apartment = row.apartment() == null ? "" : String.valueOf(row.apartment());
            f.problem = row.problem() == null ? "" : row.problem();
            return f;
        }

        public Integer getOrderNo() { return orderNo; }
        public void setOrderNo(Integer orderNo) { this.orderNo = orderNo; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getTime() { return time; }
        public void setTime(String time) { this.time = time; }
        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }
        public String getParty() { return party; }
        public void setParty(String party) { this.party = party; }
        public String getPurpose() { return purpose; }
        public void setPurpose(String purpose) { this.purpose = purpose; }
        public String getIncoming() { return incoming; }
        public void setIncoming(String incoming) { this.incoming = incoming; }
        public String getApartment() { return apartment; }
        public void setApartment(String apartment) { this.apartment = apartment; }
        public String getProblem() { return problem; }
        public void setProblem(String problem) { this.problem = problem; }

        StatementImportService.ImportRow toRow() {
            LocalDate parsedDate = null;
            String dateProblem = null;
            if (date != null && !date.isBlank()) {
                try {
                    parsedDate = LedgerService.parseDateText(date);
                } catch (RuntimeException ex) {
                    dateProblem = StatementImportService.PROBLEM_DATE;
                }
            } else {
                dateProblem = StatementImportService.PROBLEM_DATE;
            }

            BigDecimal parsedAmount = null;
            if (amount != null && !amount.isBlank()) {
                try {
                    parsedAmount = LedgerService.parseAmountText(amount);
                } catch (RuntimeException ex) {
                    // сумма не разобрана — помечается проблемой ниже
                }
            }

            Integer parsedApartment = null;
            if (apartment != null && !apartment.isBlank()) {
                try {
                    parsedApartment = Integer.parseInt(apartment.trim());
                } catch (NumberFormatException ex) {
                    // некорректный номер — останется пустым
                }
            }

            boolean typeKnown = "1".equals(incoming) || "0".equals(incoming);

            String effectiveProblem = null;
            if (dateProblem != null) {
                effectiveProblem = dateProblem;
            } else if (parsedAmount == null) {
                effectiveProblem = StatementImportService.PROBLEM_AMOUNT;
            } else if (!typeKnown) {
                effectiveProblem = StatementImportService.PROBLEM_TYPE;
            } else if (parsedApartment == null || parsedApartment <= 0) {
                effectiveProblem = StatementImportService.PROBLEM_APARTMENT;
            }

            java.time.LocalDateTime executedAt = null;
            if (parsedDate != null) {
                int hour = 0;
                int minute = 0;
                if (time != null && time.matches("\\d{1,2}:\\d{2}.*")) {
                    try {
                        String hm = time.trim();
                        hour = Integer.parseInt(hm.substring(0, hm.indexOf(':')));
                        String mm = hm.substring(hm.indexOf(':') + 1).replaceAll("\\D.*$", "");
                        minute = mm.isEmpty() ? 0 : Integer.parseInt(mm);
                    } catch (RuntimeException ignored) {
                        hour = 0;
                        minute = 0;
                    }
                }
                executedAt = parsedDate.atTime(Math.min(hour, 23), Math.min(minute, 59));
            }

            return new StatementImportService.ImportRow(
                    orderNo == null ? 0 : orderNo, parsedDate, executedAt, parsedAmount,
                    party == null ? "" : party, purpose == null ? "" : purpose,
                    "1".equals(incoming), parsedApartment, effectiveProblem);
        }
    }

    // ==================== вспомогательное ====================

    private static String summarize(List<ParsedRowForm> rows) {
        long payments = rows.stream().filter(r -> "1".equals(r.getIncoming())).count();
        long charges = rows.stream().filter(r -> "0".equals(r.getIncoming())).count();
        long problems = rows.stream().filter(r -> r.getProblem() != null && !r.getProblem().isBlank()).count();
        return "Всего операций: %d · поступлений: %d · списаний: %d · требуют внимания: %d"
                .formatted(rows.size(), payments, charges, problems);
    }

    private static String friendly(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)^failed to\\s+", "");
    }
}
