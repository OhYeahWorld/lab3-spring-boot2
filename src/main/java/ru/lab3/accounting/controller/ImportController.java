package ru.lab3.accounting.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.lab3.accounting.service.LedgerService;
import ru.lab3.accounting.service.ParsedRowsStore;
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

    // (SESSION-ключи зарезервированы; сами строки лежат в ParsedRowsStore)
    private static final String SESSION_FILE_KEY = "import.fileName";

    private final StatementImportService importService;
    private final LedgerService ledgerService;
    private final ParsedRowsStore rowsStore;

    public ImportController(StatementImportService importService, LedgerService ledgerService,
                            ParsedRowsStore rowsStore) {
        this.importService = importService;
        this.ledgerService = ledgerService;
        this.rowsStore = rowsStore;
    }

    @GetMapping
    public String page(Model model) {
        if (!model.containsAttribute("parsedRows")) {
            model.addAttribute("parsedRows", List.of());
        }
        return "data/import";
    }

    @PostMapping("/parse")
    public String parse(@RequestParam("file") MultipartFile file,
                        HttpSession session,
                        Model model) {
        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("файл пуст — выберите выписку ещё раз.");
            }
            List<StatementImportService.ImportRow> parsed = importService.parse(file);
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("в файле не найдено ни одной операции.");
            }
            List<ParsedRowForm> rows = parsed.stream().map(ParsedRowForm::from).toList();
            // страхуемся: строки также хранятся в сессии — если POST-форма когда-нибудь
            // придёт обрезанной, «Сохранить правки» и «Импортировать» всё равно сработают
            rowsStore.save(session.getId(), file.getOriginalFilename(), parsed);
            model.addAttribute("parsedRows", rows);
            model.addAttribute("summary", summarize(rows));
            model.addAttribute("fileName", file.getOriginalFilename());
            model.addAttribute("notice", "Файл разобран: %d операций. При необходимости отредактируйте "
                    .formatted(rows.size()) + "строки и нажмите «Импортировать в базу».");
        } catch (MaxUploadSizeExceededException ex) {
            model.addAttribute("error", "Файл слишком большой (лимит 20 МБ).");
            model.addAttribute("parsedRows", List.of());
        } catch (Exception ex) {
            model.addAttribute("error", "Не удалось разобрать выписку: " + friendly(ex));
            model.addAttribute("parsedRows", List.of());
        }
        return page(model);
    }

    /** Правка строк предпросмотра прямо на сайте — без импорта в базу. */
    @PostMapping("/edit")
    public String edit(HttpServletRequest request,
                       HttpSession session,
                       @RequestParam(value = "fileName", required = false) String fileName,
                       Model model) {
        List<ParsedRowForm> safe = collectRows(request, session);
        if (safe.isEmpty()) {
            model.addAttribute("error", "Нет данных для редактирования — сначала загрузите файл.");
            model.addAttribute("parsedRows", List.of());
            return page(model);
        }
        model.addAttribute("parsedRows", safe);
        model.addAttribute("summary", summarize(safe));
        model.addAttribute("fileName", resolveFileName(fileName, session));
        model.addAttribute("notice", "Правки сохранены в таблице. Проверьте данные и нажмите «Импортировать в базу».");
        return page(model);
    }

    @PostMapping("/commit")
    public String commit(HttpServletRequest request,
                         HttpSession session,
                         Model model,
                         RedirectAttributes ra) {
        List<ParsedRowForm> safe = collectRows(request, session);
        if (safe.isEmpty()) {
            model.addAttribute("error", "Не удалось получить строки: загрузите файл выписки заново "
                    + "(он больше не хранится в этой сессии).");
            model.addAttribute("parsedRows", List.of());
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
            rowsStore.remove(session.getId()); // импорт выполнен — страховочные данные больше не нужны
            ra.addFlashAttribute("success", msg.toString());
        } catch (RuntimeException ex) {
            ra.addFlashAttribute("error", "Ошибка импорта: " + friendly(ex));
        }
        return "redirect:/data/import";
    }

    /**
     * Собирает строки из POST-параметров формы; если форма пришла без строк
     * (обрезана сервером, открыта в другой вкладке и т.п.) — берёт последний
     * разбор файла из сессии. Пользовательские правки при этом не теряются:
     * они всегда есть в параметрах, а сессия служит только подстраховкой.
     */
    private List<ParsedRowForm> collectRows(HttpServletRequest request, HttpSession session) {
        List<ParsedRowForm> fromForm = readRowsFromRequest(request);
        List<StatementImportService.ImportRow> stored = rowsStore.get(session.getId());
        if (fromForm.isEmpty() && stored != null && !stored.isEmpty()) {
            // форма пришла без строк (обрезана сервером / открыта другая вкладка) —
            // берём последний сохранённый разбор вместе с предыдущими правками
            return stored.stream().map(ParsedRowForm::from).toList();
        }
        // Слияние с сохранённым разбором: если какой-то параметр формы не дошёл
        // (Tomcat отрезал часть полей), значение берётся из последней сохранённой версии.
        // Так пользовательские правки (например, вписанные номера квартир) никогда не теряются.
        if (stored != null && !stored.isEmpty()) {
            Map<Integer, ParsedRowForm> byIndex = rowsByIndex(fromForm);
            for (int i = 0; i < stored.size(); i++) {
                ParsedRowForm form = byIndex.get(i);
                if (form == null) {
                    continue;
                }
                StatementImportService.ImportRow base = stored.get(i);
                if (isBlank(form.getDate()) && base.date() != null) {
                    form.setDate(base.date().toString());
                }
                if (isBlank(form.getTime()) && base.executedAt() != null) {
                    form.setTime(String.format("%02d:%02d",
                            base.executedAt().getHour(), base.executedAt().getMinute()));
                }
                if (isBlank(form.getAmount()) && base.amount() != null) {
                    form.setAmount(base.amount().toPlainString());
                }
                if (isBlank(form.getApartment()) && base.apartment() != null) {
                    form.setApartment(String.valueOf(base.apartment()));
                }
                if (isBlank(form.getParty()) && base.party() != null && !base.party().isBlank()) {
                    form.setParty(base.party());
                }
                if (isBlank(form.getPurpose()) && base.purpose() != null && !base.purpose().isBlank()) {
                    form.setPurpose(base.purpose());
                }
                if (isBlank(form.getIncoming())) {
                    form.setIncoming(base.incoming() ? "1" : "0");
                }
            }
        }
        // страхуемся: обновляем сохранённый разбор актуальными правками из формы,
        // чтобы следующий запрос (даже обрезанный Tomcat'ом) работал с ними
        try {
            List<StatementImportService.ImportRow> importRows = new ArrayList<>();
            for (ParsedRowForm f : fromForm) {
                importRows.add(f.toRow());
            }
            rowsStore.save(session.getId(), resolveFileName(null, session), importRows);
        } catch (RuntimeException ignored) {
            // страховка не должна ломать основной сценарий
        }
        return fromForm;
    }

    /** Индексация собранных форм по позиции строки в исходной таблице. */
    private static Map<Integer, ParsedRowForm> rowsByIndex(List<ParsedRowForm> forms) {
        Map<Integer, ParsedRowForm> byIndex = new LinkedHashMap<>();
        int i = 0;
        for (ParsedRowForm f : forms) {
            Integer orderNo = f.getOrderNo();
            int key = (orderNo != null && orderNo >= 1 && orderNo <= forms.size())
                    ? orderNo - 1 : i;
            byIndex.put(key, f);
            i++;
        }
        return byIndex;
    }

    private String resolveFileName(String fromForm, HttpSession session) {
        if (fromForm != null && !fromForm.isBlank()) {
            return fromForm;
        }
        Object inSession = session.getAttribute(SESSION_FILE_KEY);
        if (inSession instanceof String s && !s.isBlank()) {
            return s;
        }
        return rowsStore.getFileName(session.getId());
    }

    private static final Pattern ROW_INDEX_PATTERN = Pattern.compile("^rows\\[(\\d+)\\]\\.(\\w+)$");

    /**
     * Читает строки таблицы из POST-параметров. Поддерживаются оба формата имён:
     * «rows[0].date» (Thymeleaf-шаблон) и «rows[0][date]» (альтернативный), чтобы
     * форма надёжно разбиралась независимо от мелочей разметки.
     */
    private static List<ParsedRowForm> readRowsFromRequest(HttpServletRequest request) {
        Map<Integer, ParsedRowForm> byIndex = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String name = entry.getKey();
            String field;
            int index;
            Matcher m = ROW_INDEX_PATTERN.matcher(name);
            if (m.matches()) {
                index = Integer.parseInt(m.group(1));
                field = m.group(2);
            } else if (name.startsWith("rows[") && name.contains("][")) {
                int close = name.indexOf(']');
                index = parseIntOr(name.substring(5, close), -1);
                field = name.substring(close + 2, name.length() - 1);
            } else {
                continue;
            }
            if (index < 0) {
                continue;
            }
            String[] values = entry.getValue();
            String value = values == null || values.length == 0 ? "" : values[0];
            ParsedRowForm f = byIndex.computeIfAbsent(index, k -> new ParsedRowForm());
            switch (field) {
                case "orderNo" -> {
                    if (value != null && !value.isBlank()) {
                        int no = parseIntOr(value.trim(), -1);
                        if (no > 0) {
                            f.setOrderNo(no);
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

    private static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return fallback;
        }
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
            f.incoming = row.incoming() ? "1" : "0";
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
                    // «2018-01-15 05:45:38» — время режем до разбора даты
                    parsedDate = LedgerService.parseDateText(date.trim().split("[ T]")[0]);
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
                String a = apartment.trim();
                try {
                    parsedApartment = Integer.parseInt(a);
                } catch (NumberFormatException ex) {
                    try {
                        // допускаем «кв. 164», «л/с 0000000134» и т.п.
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("(\\d{1,4})\\s*$").matcher(a.replaceAll("\\s+", " ").trim());
                        if (m.find()) {
                            int v = Integer.parseInt(m.group(1));
                            if (v > 0 && v < 1000) {
                                parsedApartment = v;
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // некорректный номер — останется пустым
                    }
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
