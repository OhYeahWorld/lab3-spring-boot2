package ru.lab3.accounting.service;

import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.lab3.accounting.dto.ChargeForm;
import ru.lab3.accounting.dto.PaymentForm;
import ru.lab3.accounting.repository.ChargeRepository;
import ru.lab3.accounting.repository.PaymentRepository;
import ru.lab3.accounting.repository.SaldoRepository;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор банковской выписки СберБизнес (.xlsx) и импорт операций в базу.
 *
 * Поддерживаются два представления файла:
 *  1) Таблица операций (лист с колонками «Дата проводки / Дебет / Кредит /
 *     Сумма по дебету / Сумма по кредиту / № документа / Назначение платежа») —
 *     используется, если такой лист найден: он компактнее и однозначнее.
 *  2) Печатные бланки платёжных поручений (лист «operations», где каждое
 *     поручение занимает ~45 строк) — запасной вариант: блоки разделяются
 *     заголовком «ПЛАТЕЖНОЕ ПОРУЧЕНИЕ № …» / «РЕКВИЗИТЫ № …».
 *
 * Тип операции определяется надёжно: у поступлений метка «Поступ. в банк
 * плат.» стоит ниже «Списано со сч. плат.», у списаний — наоборот.
 * Квартира извлекается из назначения платежа по всем распространённым
 * форматам («л/с 164», «ЛС 49», «кв. 76», «квартира 12», «лиц. счет 88»),
 * а также из адреса плательщика («… Д 12 К 2 КВ 164»).
 *
 * Любую распознанную строку можно исправить прямо на странице предпросмотра
 * перед импортом: номер квартиры, сумму, тип операции, дату.
 */
@Service
public class StatementImportService {

    private static final Pattern DATE_TIME = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");
    private static final Pattern ORDER_NO = Pattern.compile(
            "(?:ПЛАТЕЖНОЕ\\s+ПОРУЧЕНИЕ|РЕКВИЗИТЫ)\\s*№?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    /** л/с, л.с, ЛС 123, «л с 164», кв., квартира 76, лиц. счет 88 — берётся последнее совпадение. */
    private static final Pattern APARTMENT_HINT = Pattern.compile(
            "(?:кв(?:артир)?а?|л[./\\s-]?с[./\\sч]*|лиц[.\\s-]*(?:счет|счёт))[^0-9a-zа-я]{0,4}(\\d{1,4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Адрес в строке контрагента: «… Д 12 К 2 КВ 164». */
    private static final Pattern KV_ADDRESS = Pattern.compile(
            "\\bКВ\\.?\\s*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final String PROBLEM_DATE = "не удалось определить дату операции";
    public static final String PROBLEM_AMOUNT = "не удалось определить сумму";
    public static final String PROBLEM_TYPE = "не удалось определить тип операции (поступление/списание)";
    public static final String PROBLEM_APARTMENT = "не удалось определить номер квартиры";

    private final LedgerService ledgerService;
    private final ChargeRepository chargeRepository;
    private final PaymentRepository paymentRepository;
    private final SaldoRepository saldoRepository;

    public StatementImportService(LedgerService ledgerService,
                                  ChargeRepository chargeRepository,
                                  PaymentRepository paymentRepository,
                                  SaldoRepository saldoRepository) {
        this.ledgerService = ledgerService;
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
        this.saldoRepository = saldoRepository;
    }

    /** Одна операция выписки после разбора (или с описанием проблемы). */
    public record ImportRow(int orderNo, LocalDate date, LocalDateTime executedAt, BigDecimal amount,
                            String party, String purpose, boolean incoming, Integer apartment,
                            String problem) {

        public String timeText() {
            if (executedAt == null) {
                return "";
            }
            return "%02d:%02d".formatted(executedAt.getHour(), executedAt.getMinute());
        }

        /** Копия с другой квартирой; проблема «нет номера квартиры» снимается. */
        public ImportRow withApartment(Integer newApartment) {
            String fixedProblem = PROBLEM_APARTMENT.equals(problem) && newApartment != null ? null : problem;
            return new ImportRow(orderNo, date, executedAt, amount, party, purpose,
                    incoming, newApartment, fixedProblem);
        }

        /** Копия с другой суммой; проблема «нет суммы» снимается. */
        public ImportRow withAmount(BigDecimal newAmount) {
            String fixedProblem = PROBLEM_AMOUNT.equals(problem) && newAmount != null ? null : problem;
            return new ImportRow(orderNo, date, executedAt, newAmount, party, purpose,
                    incoming, apartment, fixedProblem);
        }

        /** Копия с другим типом операции. */
        public ImportRow withIncoming(boolean newIncoming) {
            return new ImportRow(orderNo, date, executedAt, amount, party, purpose,
                    newIncoming, apartment, problem);
        }

        /** Копия с другой датой; проблема «нет даты» снимается. */
        public ImportRow withDate(LocalDate newDate) {
            String fixedProblem = PROBLEM_DATE.equals(problem) && newDate != null ? null : problem;
            LocalDateTime newTime = newDate == null ? executedAt : newDate.atStartOfDay();
            return new ImportRow(orderNo, newDate, newTime, amount, party, purpose,
                    incoming, apartment, fixedProblem);
        }
    }

    /** Результат импорта: сколько записей создано и какие строки были пропущены. */
    public record ImportResult(int paymentsCreated, int chargesCreated, int saldoCreated,
                               List<String> problems, Set<Integer> apartments) {
    }

    // ==================== публичный разбор ====================

    public List<ImportRow> parse(MultipartFile file) throws IOException {
        try (InputStream input = file.getInputStream();
             XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            List<ImportRow> tableRows = parseTableSheets(workbook);
            if (!tableRows.isEmpty()) {
                return tableRows;
            }
            List<ImportRow> blockRows = parseBlockSheets(workbook);
            if (!blockRows.isEmpty()) {
                return blockRows;
            }
            throw new IllegalArgumentException(
                    "в файле нет ни таблицы операций («Дата проводки / Сумма по дебету / Сумма по кредиту»), "
                            + "ни блоков платёжных поручений («ПЛАТЕЖНОЕ ПОРУЧЕНИЕ № …»).");
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException("файл повреждён или не является документом Excel (.xlsx): "
                    + Optional.ofNullable(ex.getMessage()).orElse("нет данных"), ex);
        }
    }

    // ==================== стратегия 1: таблица операций ====================

    private List<ImportRow> parseTableSheets(XSSFWorkbook workbook) {
        List<ImportRow> rows = new ArrayList<>();
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            XSSFSheet sheet = workbook.getSheetAt(s);
            TableLayout layout = findTableLayout(sheet);
            if (layout == null) {
                continue;
            }
            rows.addAll(readTableSheet(sheet, layout));
        }
        return rows;
    }

    /** Колонки находим по подписям заголовков, а не по жёстким индексам. */
    private record TableLayout(int headerRow, int dateCol, int debitAccCol, int creditAccCol,
                               int debitSumCol, int creditSumCol, int docCol, int purposeCol) {
    }

    private TableLayout findTableLayout(XSSFSheet sheet) {
        int lastScan = Math.min(sheet.getLastRowNum(), 40);
        for (int r = sheet.getFirstRowNum(); r <= lastScan; r++) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            int dateCol = -1;
            int debitSumCol = -1;
            int creditSumCol = -1;
            int docCol = -1;
            int purposeCol = -1;
            for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                String text = cellText(row, c);
                if (text == null) {
                    continue;
                }
                String t = text.toLowerCase(Locale.ROOT);
                if (dateCol < 0 && (t.startsWith("дата проводки") || t.equals("дата"))) {
                    dateCol = c;
                } else if (debitSumCol < 0 && t.contains("по дебету")) {
                    debitSumCol = c;
                } else if (creditSumCol < 0 && t.contains("по кредиту")) {
                    creditSumCol = c;
                } else if (docCol < 0 && (t.startsWith("№ документа") || t.startsWith("no документа"))) {
                    docCol = c;
                } else if (purposeCol < 0 && t.startsWith("назначение")) {
                    purposeCol = c;
                }
            }
            if (dateCol >= 0 && (debitSumCol >= 0 || creditSumCol >= 0)) {
                int debitAccCol = findAccountColumn(sheet, r, debitSumCol);
                int creditAccCol = findAccountColumn(sheet, r, creditSumCol);
                return new TableLayout(r, dateCol, debitAccCol, creditAccCol,
                        debitSumCol, creditSumCol, docCol, purposeCol);
            }
        }
        return null;
    }

    /** Ищем подпись «Дебет»/«Кредит» чуть выше колонки суммы — это колонка счетов. */
    private int findAccountColumn(XSSFSheet sheet, int headerRow, int sumCol) {
        if (sumCol < 0) {
            return -1;
        }
        for (int r = Math.max(sheet.getFirstRowNum(), headerRow - 2); r <= headerRow; r++) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = Math.max(0, sumCol - 4); c <= sumCol; c++) {
                String t = cellText(row, c);
                if (t != null) {
                    String tl = t.toLowerCase(Locale.ROOT);
                    if (tl.equals("дебет") || tl.equals("кредит")) {
                        return c;
                    }
                }
            }
        }
        return Math.max(0, sumCol - 1);
    }

    private List<ImportRow> readTableSheet(XSSFSheet sheet, TableLayout layout) {
        List<ImportRow> rows = new ArrayList<>();
        int order = 0;
        for (int r = layout.headerRow() + 1; r <= sheet.getLastRowNum(); r++) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            DateParts dp = parseDate(cellText(row, layout.dateCol()));
            if (dp == null) {
                continue; // служебные/пустые строки между блоками
            }
            BigDecimal debitSum = parseAmount(cellText(row, layout.debitSumCol()));
            BigDecimal creditSum = parseAmount(cellText(row, layout.creditSumCol()));
            boolean incoming = creditSum != null && creditSum.signum() > 0;
            BigDecimal amount = incoming ? creditSum : debitSum;
            String purpose = trimToEmpty(cellText(row, layout.purposeCol()));
            String accountSide = layout.debitAccCol() >= 0 || layout.creditAccCol() >= 0
                    ? trimToEmpty(cellText(row, incoming ? layout.debitAccCol() : layout.creditAccCol()))
                    : "";
            Integer apartment = extractApartment(purpose, accountSide);

            String problem = null;
            if (amount == null || amount.signum() <= 0) {
                problem = PROBLEM_AMOUNT;
            } else if (apartment == null) {
                problem = PROBLEM_APARTMENT;
            }
            rows.add(new ImportRow(++order, dp.date(), dp.time(), amount,
                    shortParty(accountSide), purpose, incoming, apartment, problem));
        }
        return rows;
    }

    // ==================== стратегия 2: блоки поручений ====================

    private List<ImportRow> parseBlockSheets(XSSFWorkbook workbook) {
        List<ImportRow> rows = new ArrayList<>();
        int order = 0;
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            XSSFSheet sheet = workbook.getSheetAt(s);
            List<Integer> starts = blockStarts(sheet);
            if (starts.size() < 3) {
                continue; // это не лист с поручениями
            }
            for (int bi = 0; bi < starts.size(); bi++) {
                int start = starts.get(bi);
                int end = (bi + 1 < starts.size()) ? starts.get(bi + 1) : sheet.getLastRowNum() + 1;
                BlockData data = readBlock(sheet, start, end, bi > 0 ? starts.get(bi - 1) : -1);
                Integer apartment = extractApartment(data.purpose(), data.party());
                String problem = null;
                if (data.date() == null) {
                    problem = PROBLEM_DATE;
                } else if (data.amount() == null || data.amount().signum() <= 0) {
                    problem = PROBLEM_AMOUNT;
                } else if (data.incoming() == null) {
                    problem = PROBLEM_TYPE;
                } else if (apartment == null) {
                    problem = PROBLEM_APARTMENT;
                }
                rows.add(new ImportRow(++order, data.date(), data.executedAt(), data.amount(),
                        data.party(), data.purpose(), Boolean.TRUE.equals(data.incoming()),
                        apartment, problem));
            }
        }
        return rows;
    }

    private List<Integer> blockStarts(XSSFSheet sheet) {
        List<Integer> starts = new ArrayList<>();
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = Math.max(row.getFirstCellNum(), 0); c < Math.min(row.getLastCellNum(), 6); c++) {
                String text = cellText(row, c);
                if (text != null && ORDER_NO.matcher(text).find()) {
                    starts.add(r);
                    break;
                }
            }
        }
        return starts;
    }

    private record BlockData(LocalDate date, LocalDateTime executedAt, BigDecimal amount,
                             Boolean incoming, String party, String purpose) {
    }

    private BlockData readBlock(XSSFSheet sheet, int start, int end, int prevStart) {
        LocalDate opDate = null;
        LocalDateTime opTime = null;
        int postLabelRow = -1;
        int writtenOffLabelRow = -1;

        // Шапка блока — между концом предыдущего блока и началом этого.
        int windowFrom = Math.max(sheet.getFirstRowNum(), prevStart + 1);
        for (int r = start - 1; r >= windowFrom; r--) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            for (int c = Math.max(row.getFirstCellNum(), 0); c < row.getLastCellNum(); c++) {
                String t = cellText(row, c);
                if (t == null) {
                    continue;
                }
                String tl = t.toLowerCase(Locale.ROOT);
                if (postLabelRow < 0 && tl.startsWith("поступ")) {
                    postLabelRow = r;
                }
                if (writtenOffLabelRow < 0 && tl.startsWith("списано со")) {
                    writtenOffLabelRow = r;
                }
            }
            if (opDate == null) {
                DateParts dp = parseDateAnyCell(row);
                if (dp != null) {
                    opDate = dp.date();
                    opTime = dp.time();
                }
            }
            if (postLabelRow >= 0 && writtenOffLabelRow >= 0 && opDate != null) {
                break;
            }
        }
        Boolean incoming = null;
        if (postLabelRow >= 0 && writtenOffLabelRow >= 0) {
            // У поступления метка «Поступ. в банк плат.» стоит НИЖЕ «Списано со сч. плат.»
            incoming = postLabelRow > writtenOffLabelRow;
        }

        BigDecimal amount = null;
        String payer = null;
        String payee = null;
        String purpose = null;
        for (int r = start; r < end; r++) {
            XSSFRow row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String first = cellText(row, 1);
            String labelZ = cellText(row, 25);
            String value29 = cellText(row, 29);

            if (amount == null && labelZ != null && labelZ.equalsIgnoreCase("сумма")) {
                amount = parseAmount(value29);
            }
            if (first != null) {
                String fl = first.toLowerCase(Locale.ROOT);
                if (fl.equals("плательщик") && payer == null) {
                    payer = nearestNameAbove(sheet, r, start);
                } else if (fl.equals("получатель") && payee == null) {
                    payee = nearestNameBelow(sheet, r, end);
                } else if (purpose == null && fl.contains("назначение платеж") && fl.length() > 25) {
                    purpose = stripPurposePrefix(first);
                }
            }
        }
        if (purpose == null) {
            purpose = "";
        }
        String party = Boolean.TRUE.equals(incoming) ? payer : payee;
        LocalDateTime executedAt = opDate == null ? null
                : opDate.atTime(opTime == null ? 0 : opTime.getHour(),
                        opTime == null ? 0 : opTime.getMinute(),
                        opTime == null ? 0 : opTime.getSecond());
        return new BlockData(opDate, executedAt, amount, incoming,
                party == null ? "" : shortParty(party), purpose);
    }

    private DateParts parseDateAnyCell(XSSFRow row) {
        for (int c = Math.max(row.getFirstCellNum(), 0); c < Math.min(row.getLastCellNum(), 24); c++) {
            DateParts dp = parseDate(cellText(row, c));
            if (dp != null) {
                return dp;
            }
        }
        return null;
    }

    private String nearestNameAbove(XSSFSheet sheet, int labelRow, int fromRow) {
        for (int r = labelRow - 1; r >= Math.max(fromRow, labelRow - 4); r--) {
            String t = cellText(sheet.getRow(r), 1);
            if (isName(t)) {
                return t;
            }
        }
        return null;
    }

    private String nearestNameBelow(XSSFSheet sheet, int labelRow, int toRow) {
        for (int r = labelRow + 1; r < Math.min(toRow, labelRow + 3); r++) {
            String t = cellText(sheet.getRow(r), 1);
            if (isName(t)) {
                return t;
            }
        }
        return null;
    }

    private boolean isName(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        if (t.matches("[\\d\\s./:-]+")) {
            return false; // только цифры/разделители — это даты или счета
        }
        String tl = t.toLowerCase(Locale.ROOT);
        return !tl.startsWith("бик") && !tl.startsWith("сч") && !tl.startsWith("банк ");
    }

    private String stripPurposePrefix(String text) {
        return text.replaceFirst("(?i)^.*назначение\\s+платежа\\s*:?", "").trim();
    }

    // ==================== общие утилиты ====================

    private record DateParts(LocalDate date, LocalDateTime time) {
    }

    private DateParts parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = DATE_TIME.matcher(text.trim());
        if (!m.find()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
            int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
            return new DateParts(date, date.atTime(hour, minute, second));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Номер квартиры из назначения платежа (последнее упоминание) или из адреса «КВ N». */
    private Integer extractApartment(String purpose, String fallbackText) {
        Integer fromPurpose = lastApartmentMatch(purpose);
        if (fromPurpose != null) {
            return fromPurpose;
        }
        Integer fromAddress = lastKvAddressMatch(fallbackText);
        if (fromAddress != null) {
            return fromAddress;
        }
        return lastApartmentMatch(fallbackText);
    }

    private Integer lastApartmentMatch(String text) {
        if (text == null) {
            return null;
        }
        Integer last = null;
        Matcher m = APARTMENT_HINT.matcher(text);
        while (m.find()) {
            int value = Integer.parseInt(m.group(1));
            if (value > 0 && value < 1000) {
                last = value;
            }
        }
        return last;
    }

    private Integer lastKvAddressMatch(String text) {
        if (text == null) {
            return null;
        }
        Integer last = null;
        Matcher m = KV_ADDRESS.matcher(text);
        while (m.find()) {
            int value = Integer.parseInt(m.group(1));
            if (value > 0 && value < 1000) {
                last = value;
            }
        }
        return last;
    }

    /** Человекочитаемое имя контрагента: первая текстовая строка ячейки реквизитов. */
    private String shortParty(String accountCell) {
        if (accountCell == null || accountCell.isBlank()) {
            return "";
        }
        for (String line : accountCell.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.matches("[\\d\\s./-]+")
                    || t.toUpperCase(Locale.ROOT).startsWith("БИК")) {
                continue;
            }
            return t.length() > 120 ? t.substring(0, 120) : t;
        }
        return "";
    }

    private BigDecimal parseAmount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LedgerService.parseAmountText(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String cellText(XSSFRow row, int index) {
        if (row == null || index < 0 || index >= row.getLastCellNum()) {
            return null;
        }
        XSSFCell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case FORMULA -> formulaText(cell);
            case NUMERIC -> numericText(cell);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private String formulaText(XSSFCell cell) {
        try {
            return cell.getStringCellValue().trim();
        } catch (IllegalStateException ex) {
            try {
                return numericTextOf(cell.getNumericCellValue());
            } catch (RuntimeException nested) {
                return null;
            }
        }
    }

    private String numericText(XSSFCell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime dt = cell.getLocalDateTimeCellValue();
            return "%04d-%02d-%02d %02d:%02d:%02d".formatted(
                    dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                    dt.getHour(), dt.getMinute(), dt.getSecond());
        }
        return numericTextOf(cell.getNumericCellValue());
    }

    private String numericTextOf(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).toPlainString();
    }

    private String trimToEmpty(String text) {
        return text == null ? "" : text;
    }

    // ==================== импорт ====================

    @org.springframework.transaction.annotation.Transactional
    public ImportResult importRows(List<ImportRow> rows) {
        int payments = 0;
        int charges = 0;
        long before = saldoRepository.count();
        List<String> problems = new ArrayList<>();
        Set<Integer> touchedApartments = new LinkedHashSet<>();

        for (ImportRow row : rows) {
            if (row.problem() != null) {
                problems.add("строка №" + row.orderNo() + ": " + row.problem());
                continue;
            }
            try {
                if (row.incoming()) {
                    PaymentForm form = new PaymentForm();
                    form.setApartmentNumber(row.apartment());
                    form.setPeriod(YearMonth.from(row.date()).toString());
                    form.setPaymentTime(executedAtText(row));
                    form.setAmount(row.amount().toPlainString());
                    form.setPaymentReference("выписка-" + row.orderNo());
                    if (!paymentExists(row)) {
                        ledgerService.createPayment(form);
                        payments++;
                    }
                } else {
                    ChargeForm form = new ChargeForm();
                    form.setApartmentNumber(row.apartment());
                    form.setPeriod(YearMonth.from(row.date()).toString());
                    form.setAmount(row.amount().toPlainString());
                    form.setDescription(buildChargeDescription(row));
                    if (!chargeExists(row)) {
                        ledgerService.createCharge(form);
                        charges++;
                    }
                }
                touchedApartments.add(row.apartment());
            } catch (RuntimeException ex) {
                problems.add("строка №" + row.orderNo() + ": "
                        + Optional.ofNullable(ex.getMessage()).orElse("ошибка сохранения"));
            }
        }

        // Единый быстрый пересчёт цепочек сальдо всех затронутых квартир.
        ledgerService.recalculateAll(touchedApartments);

        long after = saldoRepository.count();
        return new ImportResult(payments, charges, (int) Math.max(0, after - before),
                problems, touchedApartments);
    }

    private String executedAtText(ImportRow row) {
        LocalDateTime executedAt = row.executedAt();
        if (executedAt == null) {
            executedAt = row.date().atStartOfDay();
        }
        return "%s %02d:%02d".formatted(row.date(), executedAt.getHour(), executedAt.getMinute());
    }

    private String buildChargeDescription(ImportRow row) {
        StringBuilder sb = new StringBuilder("Списание по выписке");
        if (row.party() != null && !row.party().isBlank()) {
            sb.append(", получатель: ").append(row.party());
        }
        if (row.purpose() != null && !row.purpose().isBlank()) {
            String p = row.purpose();
            if (p.length() > 140) {
                p = p.substring(0, 140);
            }
            sb.append(" — ").append(p);
        }
        String text = sb.toString();
        return text.length() > 255 ? text.substring(0, 255) : text;
    }

    private boolean paymentExists(ImportRow row) {
        LocalDateTime time = row.executedAt() == null ? row.date().atStartOfDay() : row.executedAt();
        LocalDate period = LocalDate.of(row.date().getYear(), row.date().getMonthValue(), 1);
        return paymentRepository.existsByApartmentNumberAndPeriodAndPaymentTimeAndAmount(
                row.apartment(), period, time, row.amount());
    }

    private boolean chargeExists(ImportRow row) {
        return chargeRepository.existsByApartmentNumberAndPeriodAndAmount(
                row.apartment(), row.date(), row.amount());
    }
}
