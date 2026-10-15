package ru.lab3.accounting.controller;

import jakarta.validation.Valid;
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
import java.util.List;

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
            List<StatementImportService.ImportRow> rows = importService.parse(file);
            model.addAttribute("parsedRows", rows);
            model.addAttribute("summary", summarize(rows));
            model.addAttribute("fileName", file.getOriginalFilename());
        } catch (Exception ex) {
            model.addAttribute("error", "Не удалось разобрать выписку: " + friendly(ex));
            model.addAttribute("parsedRows", List.of());
        }
        return page(model);
    }

    /** Правка строки предпросмотра прямо на сайте: квартира, сумма, тип, дата. */
    @PostMapping("/edit")
    public String edit(@Valid ParsedRowsHolder holder, Model model, RedirectAttributes ra) {
        if (holder.getRows() == null || holder.getRows().isEmpty()) {
            ra.addFlashAttribute("error", "Нет данных для редактирования — сначала загрузите файл.");
            return "redirect:/data/import";
        }
        model.addAttribute("parsedRows", holder.toRows());
        model.addAttribute("summary", summarize(holder.toRows()));
        model.addAttribute("fileName", holder.getFileName());
        return page(model);
    }

    @PostMapping("/commit")
    public String commit(@Valid ParsedRowsHolder holder, RedirectAttributes ra) {
        if (holder.getRows() == null || holder.getRows().isEmpty()) {
            ra.addFlashAttribute("error", "Нет данных для импорта — сначала загрузите файл.");
            return "redirect:/data/import";
        }
        try {
            StatementImportService.ImportResult result = importService.importRows(holder.toRows());
            StringBuilder msg = new StringBuilder("Импорт завершён: платежей %d, начислений %d, сальдо %d."
                    .formatted(result.paymentsCreated(), result.chargesCreated(), result.saldoCreated()));
            if (!result.problems().isEmpty()) {
                msg.append(" Пропущено строк: ").append(result.problems().size()).append('.');
            }
            ra.addFlashAttribute("success", msg.toString());
            if (!result.problems().isEmpty()) {
                ra.addFlashAttribute("error", "Пропущенные строки: " + String.join("; ", result.problems()));
            }
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("error", "Ошибка импорта: " + friendly(ex));
        }
        return "redirect:/data/import";
    }

    // ==================== форма предпросмотра ====================

    /** Хранит распознанные строки между запросами в скрытых полях формы. */
    public static class ParsedRowsHolder {
        private String fileName = "";
        private List<ParsedRowForm> rows = new ArrayList<>();

        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public List<ParsedRowForm> getRows() { return rows; }
        public void setRows(List<ParsedRowForm> rows) { this.rows = rows; }

        List<StatementImportService.ImportRow> toRows() {
            List<StatementImportService.ImportRow> result = new ArrayList<>();
            for (ParsedRowForm f : rows) {
                result.add(f.toRow());
            }
            return result;
        }
    }

    public static class ParsedRowForm {
        private Integer orderNo;
        private String date;
        private String time = "";
        private String amount = "";
        private String party = "";
        private String purpose = "";
        private Boolean incoming;
        private String apartment = "";
        private String problem = "";

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
        public Boolean getIncoming() { return incoming; }
        public void setIncoming(Boolean incoming) { this.incoming = incoming; }
        public String getApartment() { return apartment; }
        public void setApartment(String apartment) { this.apartment = apartment; }
        public String getProblem() { return problem; }
        public void setProblem(String problem) { this.problem = problem; }

        StatementImportService.ImportRow toRow() {
            LocalDate parsedDate = null;
            String dateProblem = null;
            if (date != null && !date.isBlank()) {
                try {
                    parsedDate = LocalDate.parse(date.trim());
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
                } catch (IllegalArgumentException ex) {
                    // сумма не разобрана — ниже пометим проблемой
                }
            }
            Integer parsedApartment = null;
            if (apartment != null && !apartment.isBlank()) {
                try {
                    parsedApartment = Integer.parseInt(apartment.trim());
                } catch (NumberFormatException ex) {
                    // некорректный номер — оставим null
                }
            }
            boolean typeKnown = incoming != null;
            String carriedProblem = (problem == null || problem.isBlank()) ? null : problem;

            String effectiveProblem = null;
            if (dateProblem != null) {
                effectiveProblem = dateProblem;
            } else if (parsedAmount == null) {
                effectiveProblem = StatementImportService.PROBLEM_AMOUNT;
            } else if (!typeKnown) {
                effectiveProblem = StatementImportService.PROBLEM_TYPE;
            } else if (parsedApartment == null || parsedApartment <= 0) {
                effectiveProblem = StatementImportService.PROBLEM_APARTMENT;
            } else {
                // Пользователь мог исправить проблему вручную; чужие проблемы сохраняем.
                boolean wasFixable = StatementImportService.PROBLEM_APARTMENT.equals(carriedProblem)
                        || StatementImportService.PROBLEM_AMOUNT.equals(carriedProblem);
                if (carriedProblem != null && !wasFixable) {
                    effectiveProblem = carriedProblem;
                }
            }

            java.time.LocalDateTime executedAt = null;
            if (parsedDate != null) {
                int hour = 0;
                int minute = 0;
                if (time != null && time.matches("\\d{1,2}:\\d{2}.*")) {
                    try {
                        String hm = time.trim();
                        hour = Integer.parseInt(hm.substring(0, hm.indexOf(':')));
                        minute = Integer.parseInt(hm.substring(hm.indexOf(':') + 1).replaceAll("\\D.*$", ""));
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
                    Boolean.TRUE.equals(incoming), parsedApartment, effectiveProblem);
        }
    }

    // ==================== вспомогательное ====================

    private static String summarize(List<StatementImportService.ImportRow> rows) {
        long payments = rows.stream().filter(r -> r.problem() == null && r.incoming()).count();
        long charges = rows.stream().filter(r -> r.problem() == null && !r.incoming()).count();
        long problems = rows.stream().filter(r -> r.problem() != null).count();
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
