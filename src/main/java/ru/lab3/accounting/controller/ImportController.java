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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/data/import")
public class ImportController {

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
     * Собирает строки из POST-параметров формы. Если форма пришла без строк
     * (обрезана сервером, открыта в другой вкладке и т.п.) — берёт последний
     * сохранённый разбор из ParsedRowsStore. Поле, которое вообще не дошло в
     * запросе, доливается из сохранённой строки; поле, присланное пустым, —
     * это осознанная правка пользователя (ячейку очистили), его не подменяем.
     * Замечания («не удалось определить…») пересчитываются по фактическим
     * данным строк, поэтому вписанный вручную номер квартиры снимает проблему.
     */
    private List<ParsedRowForm> collectRows(HttpServletRequest request, HttpSession session) {
        List<SentForm> sent = readRowsFromRequest(request);
        List<StatementImportService.ImportRow> stored = rowsStore.get(session.getId());
        List<ParsedRowForm> fromForm = new ArrayList<>();
        if (sent.isEmpty()) {
            if (stored != null && !stored.isEmpty()) {
                for (StatementImportService.ImportRow r : stored) {
                    fromForm.add(ParsedRowForm.from(r));
                }
            }
        } else {
            Map<Integer, StatementImportService.ImportRow> byNo = new LinkedHashMap<>();
            Map<Integer, StatementImportService.ImportRow> byPosition = new LinkedHashMap<>();
            if (stored != null) {
                int pos = 0;
                for (StatementImportService.ImportRow r : stored) {
                    byNo.put(r.orderNo(), r);
                    byPosition.put(++pos, r);
                }
            }
            for (SentForm sf : sent) {
                ParsedRowForm f = sf.form();
                StatementImportService.ImportRow saved = f.getOrderNo() != null
                        ? byNo.get(f.getOrderNo()) : byPosition.get(sf.position());
                if (saved != null) {
                    f.fillMissingFrom(saved, sf.sentFields());
                }
                fromForm.add(f);
            }
        }
        finishRows(fromForm);
        // Страховка: сохраняем актуальные правки, чтобы следующий запрос
        // (даже обрезанный сервером) работал с последней версией таблицы.
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

    private String resolveFileName(String fromForm, HttpSession session) {
        if (fromForm != null && !fromForm.isBlank()) {
            return fromForm;
        }
        return rowsStore.getFileName(session.getId());
    }

    private static final Pattern ROW_INDEX_PATTERN = Pattern.compile("^rows\\[(\\d+)\\]\\.(\\w+)$");

    /**
     * Читает строки таблицы из POST-параметров. Поддерживаются оба формата имён:
     * «rows[0].date» (Thymeleaf-шаблон) и «rows[0][date]» (альтернативный), чтобы
     * форма надёжно разбиралась независимо от мелочей разметки.
     * Возвращает пары «форма + набор полей, реально присутствующих в запросе»:
     * отсутствие поля означает обрезанную форму, а пустое значение — правку
     * пользователя (ячейку очистили руками).
     */
    private static List<SentForm> readRowsFromRequest(HttpServletRequest request) {
        Map<Integer, ParsedRowForm> byIndex = new LinkedHashMap<>();
        Map<Integer, Set<String>> sentFields = new LinkedHashMap<>();
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
            sentFields.computeIfAbsent(index, k -> new java.util.HashSet<>()).add(field);
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
        List<SentForm> result = new ArrayList<>();
        int fallbackIndex = 0;
        for (Map.Entry<Integer, ParsedRowForm> e : byIndex.entrySet()) {
            ParsedRowForm f = e.getValue();
            fallbackIndex++;
            if (isBlankForm(f)) {
                continue; // полностью пустая строка — пропускаем
            }
            result.add(new SentForm(f, sentFields.getOrDefault(e.getKey(), Set.of()), fallbackIndex));
        }
        return result;
    }

    /** Форма строки + имена полей, которые реально пришли в запросе, + позиция в таблице. */
    private record SentForm(ParsedRowForm form, Set<String> sentFields, int position) {
    }

    /**
     * Нормализует собранные строки: приводит поля к каноническому виду и
     * пересчитывает замечания по фактическим данным. После ручной правки
     * (например, вписанного номера квартиры) старая метка «не удалось
     * определить номер квартиры» снимается, и строка импортируется.
     */
    private static void finishRows(List<ParsedRowForm> rows) {
        int index = 0;
        for (ParsedRowForm f : rows) {
            if (f.getOrderNo() == null) {
                f.setOrderNo(++index);
            } else {
                index = f.getOrderNo();
            }
            StatementImportService.ImportRow row = f.toRow();
            f.setDate(row.date() == null ? "" : row.date().toString());
            f.setTime(row.executedAt() == null ? ""
                    : "%02d:%02d".formatted(row.executedAt().getHour(), row.executedAt().getMinute()));
            f.setAmount(row.amount() == null ? "" : row.amount().toPlainString());
            f.setApartment(row.apartment() == null ? "" : String.valueOf(row.apartment()));
            String problem = StatementImportService.recomputeProblem(row);
            f.setProblem(problem == null ? "" : problem);
        }
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

        private static final Pattern TIME_HM = Pattern.compile(
                "^(\\d{1,2})[:.](\\d{2})(?::\\d{2})?$");
        private static final Pattern APARTMENT_TAIL = Pattern.compile("(\\d{1,4})\\s*$");
        private static final Pattern APARTMENT_HINT = Pattern.compile(
                "(?:кв(?:артир)?а?|л[./\\s-]?с[ч.]?|лиц[.\\s-]*(?:счет|счёт))[^0-9а-я]{0,4}(\\d{1,4})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
            f.incoming = row.typeKnown() == null ? "" : (row.typeKnown() ? "1" : "0");
            f.apartment = row.apartment() == null ? "" : String.valueOf(row.apartment());
            f.problem = row.problem() == null ? "" : row.problem();
            return f;
        }

        /**
         * Доливает из сохранённого разбора только те поля, которых вообще не
         * оказалось в POST-запросе (обрезанная форма). Поля, пришедшие пустыми,
         * считаются осознанной правкой пользователя и остаются пустыми.
         */
        void fillMissingFrom(StatementImportService.ImportRow saved, Set<String> sentFields) {
            if (!sentFields.contains("date") && isBlank(date)) {
                date = saved.date() == null ? "" : saved.date().toString();
            }
            if (!sentFields.contains("time") && isBlank(time)) {
                time = saved.executedAt() == null ? ""
                        : "%02d:%02d".formatted(saved.executedAt().getHour(), saved.executedAt().getMinute());
            }
            if (!sentFields.contains("amount") && isBlank(amount)) {
                amount = saved.amount() == null ? "" : saved.amount().toPlainString();
            }
            if (!sentFields.contains("party") && isBlank(party)) {
                party = saved.party() == null ? "" : saved.party();
            }
            if (!sentFields.contains("purpose") && isBlank(purpose)) {
                purpose = saved.purpose() == null ? "" : saved.purpose();
            }
            if (!sentFields.contains("incoming") && isBlank(incoming)) {
                incoming = saved.typeKnown() == null ? "" : (saved.typeKnown() ? "1" : "0");
            }
            if (!sentFields.contains("apartment") && isBlank(apartment)) {
                apartment = saved.apartment() == null ? "" : String.valueOf(saved.apartment());
            }
            if (!sentFields.contains("problem") && isBlank(problem)) {
                problem = saved.problem() == null ? "" : saved.problem();
            }
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

        /**
         * Собирает строку данных из полей формы. Поля принимаются в любом
         * привычном виде: дата «2018-01-15» или «15.01.2018», сумма «4590,22»,
         * квартира «164», «кв. 164» или «л/с 0000000134». Замечание (problem)
         * здесь не вычисляется — итоговую проблему определяет
         * {@link StatementImportService#recomputeProblem}.
         */
        StatementImportService.ImportRow toRow() {
            LocalDate parsedDate = null;
            if (date != null && !date.isBlank()) {
                try {
                    // «2018-01-15 05:45:38» — время режем до разбора даты
                    parsedDate = LedgerService.parseDateText(date.trim().split("[ T]")[0]);
                } catch (RuntimeException ignored) {
                    // некорректная дата — останется проблемой при пересчёте
                }
            }

            BigDecimal parsedAmount = null;
            if (amount != null && !amount.isBlank()) {
                try {
                    parsedAmount = LedgerService.parseAmountText(amount);
                } catch (RuntimeException ignored) {
                    // некорректная сумма — останется проблемой при пересчёте
                }
            }

            Integer parsedApartment = parseApartment(apartment);

            // Тип операции: «1» — поступление, «0» — списание, пустое — не выбран.
            Boolean parsedTypeKnown = "1".equals(incoming) ? Boolean.TRUE
                    : "0".equals(incoming) ? Boolean.FALSE : null;

            java.time.LocalTime parsedTime = null;
            if (time != null && !time.isBlank()) {
                Matcher m = TIME_HM.matcher(time.trim());
                if (m.matches()) {
                    int hour = Integer.parseInt(m.group(1));
                    int minute = Integer.parseInt(m.group(2));
                    if (hour <= 23 && minute <= 59) {
                        parsedTime = java.time.LocalTime.of(hour, minute);
                    }
                }
            }

            // ВАЖНО: typeKnown передаётся как Boolean (TRUE/FALSE/null), а не как
            // примитивный boolean. Если бы сюда шёл "1".equals(incoming), неотмеченный
            // тип превращался бы в «списание», и ошибка типа терялась бы при импорте.
            return new StatementImportService.ImportRow(
                    orderNo == null ? 0 : orderNo, parsedDate,
                    parsedDate == null ? null : parsedDate.atTime(parsedTime == null
                            ? java.time.LocalTime.MIDNIGHT : parsedTime),
                    parsedAmount,
                    party == null ? "" : party, purpose == null ? "" : purpose,
                    parsedTypeKnown, parsedApartment, null);
        }

        /** Номер квартиры из текстового поля: чистое число либо подсказка «кв. 164», «л/с 134». */
        private static Integer parseApartment(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String a = text.replaceAll("\\s+", " ").trim();
            try {
                int v = Integer.parseInt(a);
                return (v > 0 && v < 1000) ? v : null;
            } catch (NumberFormatException ignored) {
                // ищем номер по подсказке или в конце строки
            }
            Matcher hint = APARTMENT_HINT.matcher(a);
            Integer fromHint = null;
            while (hint.find()) {
                int v = Integer.parseInt(hint.group(1).replaceFirst("^0+(?!$)", ""));
                if (v > 0 && v < 1000) {
                    fromHint = v;
                }
            }
            if (fromHint != null) {
                return fromHint;
            }
            Matcher tail = APARTMENT_TAIL.matcher(a);
            if (tail.find()) {
                int v = Integer.parseInt(tail.group(1));
                if (v > 0 && v < 1000) {
                    return v;
                }
            }
            return null;
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
