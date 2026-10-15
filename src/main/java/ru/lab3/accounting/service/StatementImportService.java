package ru.lab3.accounting.service;

import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import ru.lab3.accounting.dto.ChargeForm;
import ru.lab3.accounting.dto.PaymentForm;
import ru.lab3.accounting.exception.DuplicateRecordException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Загрузка операций из банковской выписки (Excel, формат «СберБизнес»).
 *
 * Файл годовой выписки содержит два листа: короткий табличный список операций
 * («ВЫПИСКА ОПЕРАЦИЙ ПО ЛИЦЕВОМУ СЧЕТУ»: дата проводки, дебет/кредит, суммы,
 * № документа, назначение платежа) и большой постраничный лист с обрамлениями
 * платёжных поручений. Разбираются ОБА формата — выбирается тот лист, где
 * распознано больше операций. Из каждой операции берутся номер документа,
 * дата, сумма, тип (поступление/списание), контрагент, назначение платежа и,
 * по возможности, номер квартиры (из назначения вида «кв. 76», «л/с 105» или
 * из адреса плательщика). Поступления заносятся как платежи, списания — как
 * начисления. Если квартира не определена, операция относится на служебную
 * квартиру-«котёлку» (9000 + последние две цифры номера расчётного счёта):
 * такие строки видно в предпросмотре, их можно поправить перед импортом либо
 * отредактировать позже на страницах «Платежи» и «Начисления».
 */
@Service
public class StatementImportService {

    /** Строка разобранной операции выписки (для предпросмотра и редактирования). */
    public record ImportRow(String orderNo, LocalDate date, BigDecimal amount, String party,
                            String purpose, boolean incoming, Integer apartment, String problem) {

        public ImportRow withApartment(Integer newApartment) {
            return new ImportRow(orderNo, date, amount, party, purpose, incoming, newApartment, problem);
        }

        public ImportRow withAmount(BigDecimal newAmount) {
            return new ImportRow(orderNo, date, newAmount, party, purpose, incoming, apartment, problem);
        }
    }

    public record ImportResult(int saldoCreated, int paymentsCreated, int chargesCreated,
                               List<Integer> apartments, List<String> problems) {
    }

    private static final Pattern ORDER_PATTERN =
            Pattern.compile("(?:ПЛАТЕЖНОЕ\\s+ПОРУЧЕНИЕ|РЕКВИЗИТЫ)\\s*№?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    // Номер квартиры / лицевого счёта в назначении платежа.
    private static final Pattern APARTMENT_PATTERN = Pattern.compile(
            "(?:кв(?:артир)?а?|л[./\\s-]?с[./\\sч]*|лиц[.\\s-]*(?:счет|счёт))[^0-9a-zа-я]{0,4}(\\d{1,4})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // Номер квартиры в адресной строке плательщика («…Д 12 К 2 КВ 164//»).
    private static final Pattern KV_ADDRESS_PATTERN = Pattern.compile(
            "\\bКВ\\.?\\s*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DATE_IN_PURPOSE_PATTERN = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.forLanguageTag("ru-RU"));

    private final LedgerService ledgerService;

    public StatementImportService(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** Импортирует уже разобранный (и, возможно, отредактированный) список операций. */
    @Transactional
    public ImportResult importRows(List<ImportRow> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Нечего импортировать: список операций пуст.");
        }

        int saldoCreated = 0;
        int paymentsCreated = 0;
        int chargesCreated = 0;
        List<String> problems = new ArrayList<>();
        LinkedHashMap<Integer, Boolean> touchedApartments = new LinkedHashMap<>();

        for (ImportRow row : rows) {
            if (row.problem() != null) {
                problems.add(label(row) + ": " + row.problem());
                continue;
            }
            Integer apartment = row.apartment();
            touchedApartments.put(apartment, true);
            LocalDate period = row.date().withDayOfMonth(1);

            try {
                if (!ledgerService.saldoExists(apartment, period)) {
                    ledgerService.ensureSaldoChain(apartment, period);
                    saldoCreated++;
                }
                String reference = truncate("ПП №" + row.orderNo() + " от " + row.date(), 100);
                if (row.incoming()) {
                    PaymentForm form = new PaymentForm();
                    form.setApartmentNumber(apartment);
                    form.setPeriod(period.toString());
                    form.setPaymentTime(row.date().atStartOfDay().toString());
                    form.setAmount(row.amount().toPlainString());
                    form.setPaymentReference(reference);
                    ledgerService.createPayment(form);
                    paymentsCreated++;
                } else {
                    ChargeForm form = new ChargeForm();
                    form.setApartmentNumber(apartment);
                    form.setPeriod(period.toString());
                    form.setAmount(row.amount().toPlainString());
                    form.setDescription(truncate(reference + ". " + buildDescription(row), 1000));
                    ledgerService.createCharge(form);
                    chargesCreated++;
                }
            } catch (DuplicateRecordException ex) {
                problems.add(label(row) + ": " + ex.getMessage());
            } catch (DataIntegrityViolationException ex) {
                problems.add(label(row) + ": запись уже существует в базе.");
            } catch (RuntimeException ex) {
                problems.add(label(row) + ": " + messageOf(ex));
            }
        }

        return new ImportResult(saldoCreated, paymentsCreated, chargesCreated,
                List.copyOf(touchedApartments.keySet()), problems);
    }

    /**
     * Разбирает файл выписки на строки без записи в базу (для предпросмотра
     * и редактирования). Используется потоковый (SAX) читатель POI: годовая
     * выписка СберБизнес целиком в памяти обрушивала приложение (OutOfMemory).
     */
    public List<ImportRow> parse(MultipartFile file) throws IOException {
        byte[] content = file.getBytes();
        List<ImportRow> best = List.of();
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(content))) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            Iterator<InputStream> sheets = reader.getSheetsData();
            while (sheets.hasNext()) {
                List<String[]> grid = new ArrayList<>();
                XMLReader parser = SAXHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings,
                        new GridCollector(grid), FORMATTER, false));
                try (InputStream sheet = sheets.next()) {
                    parser.parse(new InputSource(sheet));
                }
                List<ImportRow> tableRows = parseOperationTable(grid);
                List<ImportRow> blockRows = parseBlocks(grid);
                List<ImportRow> chosen = tableRows.size() >= blockRows.size() ? tableRows : blockRows;
                if (chosen.size() > best.size()) {
                    best = chosen;
                }
            }
        } catch (OpenXML4JException ex) {
            throw new IllegalArgumentException(
                    "Файл не является корректным Excel (.xlsx). Сохраните выписку в формате .xlsx и повторите загрузку.", ex);
        } catch (SAXException ex) {
            throw new IllegalArgumentException("Не удалось прочитать листы Excel-файла: " + messageOf(ex), ex);
        } catch (ParserConfigurationException ex) {
            throw new IllegalArgumentException("В среде запуска недоступен SAX-парсер XML: " + messageOf(ex), ex);
        }

        if (best.isEmpty()) {
            throw new IllegalArgumentException(
                    "В файле не распознано ни одной операции. Проверьте, что это выписка СберБизнес в формате .xlsx "
                            + "(лист с колонками «Дата проводки», «№ документа», «Назначение платежа» "
                            + "или блоки «ПЛАТЕЖНОЕ ПОРУЧЕНИЕ № …»).");
        }

        // Убираем точные повторы внутри файла (один и тот же документ иногда попадает дважды).
        List<ImportRow> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ImportRow r : best) {
            String key = r.orderNo() + "|" + r.date() + "|" + r.amount() + "|" + r.incoming();
            if (r.problem() == null && !seen.add(key)) {
                continue;
            }
            unique.add(r);
        }
        return unique;
    }

    // ============ табличный формат (лист «ВЫПИСКА ОПЕРАЦИЙ ПО ЛИЦЕВОМУ СЧЕТУ») ==========

    private List<ImportRow> parseOperationTable(List<String[]> grid) {
        List<ImportRow> rows = new ArrayList<>();
        int headerRow = -1;
        Map<String, Integer> cols = new HashMap<>();
        for (int i = 0; i < Math.min(grid.size(), 40); i++) {
            Map<String, Integer> found = new HashMap<>();
            String[] row = grid.get(i);
            for (int c = 0; c < row.length; c++) {
                String v = row[c];
                if (v == null) continue;
                String t = v.trim().toLowerCase(Locale.ROOT);
                if (t.startsWith("дата проводки")) found.putIfAbsent("date", c);
                else if (t.equals("счет") || t.equals("счёт")) found.putIfAbsent("debitAccount", c);
                else if (t.equals("кредит")) found.putIfAbsent("creditAccount", c);
                else if (t.contains("по дебету")) found.putIfAbsent("debit", c);
                else if (t.contains("по кредиту")) found.putIfAbsent("credit", c);
                else if (t.contains("документа")) found.putIfAbsent("doc", c);
                else if (t.contains("назначение платежа")) found.putIfAbsent("purpose", c);
            }
            if (found.containsKey("date") && found.containsKey("doc") && found.containsKey("purpose")) {
                headerRow = i;
                cols.putAll(found);
                break;
            }
        }
        if (headerRow < 0) return rows;
        Integer debitAccCol = cols.get("debitAccount");
        Integer creditAccCol = cols.getOrDefault("creditAccount", debitAccCol == null ? null : debitAccCol + 4);
        Integer debitSumCol = cols.get("debit");
        Integer creditSumCol = cols.get("credit");

        // Наш расчётный счёт — самый частый 20-значный номер в столбцах счетов.
        String ourAccount = detectOurAccount(grid, headerRow, debitAccCol, creditAccCol);
        int fallbackApartment = fallbackApartment(ourAccount);

        for (int i = headerRow + 1; i < grid.size(); i++) {
            String dateText = cell(grid, i, cols.get("date"));
            if (dateText == null || !isDateLike(dateText)) continue;
            LocalDate date = parseDate(dateText);
            String doc = trim(cell(grid, i, cols.get("doc")));
            String purpose = trim(cell(grid, i, cols.get("purpose")));
            String debitAcc = debitAccCol == null ? null : cell(grid, i, debitAccCol);
            String creditAcc = creditAccCol == null ? null : cell(grid, i, creditAccCol);
            BigDecimal debitSum = debitSumCol == null ? null : parseAmount(cell(grid, i, debitSumCol));
            BigDecimal creditSum = creditSumCol == null ? null : parseAmount(cell(grid, i, creditSumCol));

            BigDecimal amount;
            boolean incoming;
            if (creditSum != null && creditSum.signum() > 0) {
                amount = creditSum;
                incoming = true;
            } else if (debitSum != null && debitSum.signum() > 0) {
                amount = debitSum;
                incoming = false;
            } else {
                rows.add(new ImportRow(doc, date, null, null, purpose, false, null,
                        "не найдена сумма операции"));
                continue;
            }
            if (date == null) {
                rows.add(new ImportRow(doc, null, amount, null, purpose, incoming, null,
                        "не найдена дата операции"));
                continue;
            }
            if (ourAccount != null) {
                incoming = containsDigits(creditAcc, ourAccount);
            }

            String party = cleanParty(incoming ? accountName(debitAcc) : accountName(creditAcc));
            Integer apartment = extractApartment(purpose);
            if (apartment == null) {
                apartment = extractKvFromAddress(incoming ? debitAcc : creditAcc);
            }
            if (apartment == null) {
                apartment = fallbackApartment;
            }

            rows.add(new ImportRow(doc, date, amount, party, stripPurposePrefix(purpose),
                    incoming, apartment, null));
        }
        return rows;
    }

    /** Наш счёт: 20-значный номер, встречающийся в большинстве строк таблицы. */
    private String detectOurAccount(List<String[]> grid, int headerRow, Integer debitAccCol, Integer creditAccCol) {
        Map<String, Integer> freq = new HashMap<>();
        for (int i = headerRow + 1; i < grid.size(); i++) {
            for (Integer col : new Integer[]{debitAccCol, creditAccCol}) {
                String v = cell(grid, i, col);
                if (v == null) continue;
                Matcher m = Pattern.compile("\\d{20}").matcher(v);
                while (m.find()) freq.merge(m.group(), 1, Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Служебная квартира для операций без распознанного номера. */
    private static int fallbackApartment(String ourAccount) {
        if (ourAccount != null && ourAccount.length() >= 2 && ourAccount.matches("\\d+")) {
            return 9000 + Integer.parseInt(ourAccount.substring(ourAccount.length() - 2));
        }
        return 9999;
    }

    private static boolean containsDigits(String cellValue, String digits) {
        return cellValue != null && cellValue.replaceAll("\\D", "").contains(digits);
    }

    /** Имя владельца счёта из многострочной ячейки «Счет» (последняя текстовая строка). */
    private static String accountName(String accountCell) {
        if (accountCell == null) return null;
        String[] parts = accountCell.split("\\R");
        for (int k = parts.length - 1; k >= 0; k--) {
            String p = parts[k].trim();
            if (!p.isEmpty() && !p.matches("\\d+")) return p;
        }
        return null;
    }

    private static String cleanParty(String name) {
        return name == null ? null : truncate(name.trim(), 300);
    }

    private static String stripPurposePrefix(String purpose) {
        if (purpose == null) return null;
        return purpose.replaceFirst("(?i)^Дата оплаты\\s+[\\d/]+\\s*,\\s*(сумма\\s*[\\d.,]+\\s*,?)?", "").trim();
    }

    private static Integer extractKvFromAddress(String accountCell) {
        if (accountCell == null) return null;
        Matcher m = KV_ADDRESS_PATTERN.matcher(accountCell);
        Integer last = null;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v > 0 && v < 1000) last = v;
            } catch (NumberFormatException ignored) { }
        }
        return last;
    }

    // ================= блочный формат («ПЛАТЕЖНОЕ ПОРУЧЕНИЕ № …») =====================

    private List<ImportRow> parseBlocks(List<String[]> grid) {
        List<ImportRow> rows = new ArrayList<>();
        String ourAccount = detectMostFrequentAccountInColumn(grid, 29);
        int fallbackApartment = fallbackApartment(ourAccount);

        for (int i = 0; i < grid.size(); i++) {
            String orderLabel = cell(grid, i, 1);
            if (orderLabel == null) continue;
            Matcher om = ORDER_PATTERN.matcher(orderLabel);
            if (!om.find()) continue;
            String orderNo = om.group(1);

            int end = i + 1;
            while (end < grid.size()) {
                String label = cell(grid, end, 1);
                if (label != null && ORDER_PATTERN.matcher(label).find()) break;
                if (label != null && label.trim().toUpperCase(Locale.ROOT).startsWith("ИСПОЛНЕНО")) break;
                end++;
            }

            LocalDate opDate = null;
            BigDecimal amount = null;
            boolean incoming = false, outgoing = false;
            String payer = null, payee = null, purpose = null;

            // Дата операции — в шапке над блоком (одинаковая дата в B- и R-колонках).
            for (int j = Math.max(0, i - 9); j < i; j++) {
                String b = cell(grid, j, 1);
                String r = cell(grid, j, 17);
                if (b != null && r != null && isDateLike(b) && isDateLike(r)) {
                    opDate = parseDate(b);
                    if (opDate == null) opDate = parseDate(r);
                    break;
                }
            }

            for (int j = i; j < end; j++) {
                String b = cell(grid, j, 1);
                String z = cell(grid, j, 25);
                String ad = cell(grid, j, 29);
                if (z != null) {
                    String t = z.trim();
                    if (t.equals("Сумма") && amount == null) {
                        amount = parseAmount(ad);
                    } else if (t.startsWith("Сч. №") && ad != null && ourAccount != null
                            && ad.replaceAll("\\s", "").equals(ourAccount)) {
                        if (sideLabelIsPayer(grid, j, i)) incoming = true; else outgoing = true;
                    }
                }
                if (b != null) {
                    String t = b.trim();
                    if (t.equals("Плательщик") && payer == null) payer = partyNameAbove(grid, j, i);
                    if (t.equals("Получатель") && payee == null) payee = partyNameAbove(grid, j, i);
                    if (purpose == null && t.contains("Назначение") && !t.equalsIgnoreCase("Назначение платежа")) {
                        purpose = t.replaceFirst("(?i)^.*Назначение\\s+платежа\\s*:?\\s*", "").trim();
                    }
                }
            }

            Integer apartment = purpose == null ? null : extractApartment(purpose);
            if (opDate == null && purpose != null) {
                Matcher dm = DATE_IN_PURPOSE_PATTERN.matcher(purpose);
                if (dm.find()) {
                    try {
                        opDate = LocalDate.of(Integer.parseInt(dm.group(3)),
                                Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(1)));
                    } catch (RuntimeException ignored) { }
                }
            }

            String problem = null;
            if (opDate == null) problem = "не найдена дата операции";
            else if (amount == null || amount.signum() <= 0) problem = "не найдена корректная сумма";
            else if (incoming == outgoing) problem = "не удалось определить тип операции (поступление/списание)";
            if (apartment == null) apartment = fallbackApartment;

            rows.add(new ImportRow(orderNo, opDate, amount, incoming ? payer : payee, purpose,
                    incoming, apartment, problem));
            i = Math.max(i, end - 1);
        }
        return rows;
    }

    /** Самый частый 20-значный номер в указанном столбце (для блочного листа — столбец AD). */
    private String detectMostFrequentAccountInColumn(List<String[]> grid, int column) {
        Map<String, Integer> freq = new HashMap<>();
        for (String[] row : grid) {
            String v = row.length > column ? row[column] : null;
            if (v == null) continue;
            Matcher m = Pattern.compile("^\\s*(\\d{20})\\s*$").matcher(v);
            if (m.matches()) freq.merge(m.group(1), 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** К чьей подписи («Плательщик»/«Получатель») относится строка со счётом. true = Плательщик. */
    private boolean sideLabelIsPayer(List<String[]> grid, int accountRow, int blockStart) {
        for (int j = accountRow; j >= blockStart; j--) {
            String b = cell(grid, j, 1);
            if (b == null) continue;
            String t = b.trim();
            if (t.equals("Плательщик")) return true;
            if (t.equals("Получатель")) return false;
        }
        return false;
    }

    private String partyNameAbove(List<String[]> grid, int labelRow, int blockStart) {
        for (int j = labelRow - 1; j >= blockStart; j--) {
            String b = cell(grid, j, 1);
            if (b == null) continue;
            String t = b.trim();
            if (t.isEmpty()) continue;
            if (t.equals("Банк Плательщика") || t.equals("Банк Получателя") || t.equals("ИНН")
                    || t.startsWith("Сч. №") || t.equals("БИК") || t.matches("(?i)^г\\.?\\s.*")) {
                break;
            }
            return truncate(t, 300);
        }
        return null;
    }

    // ======================= общие утилиты =======================

    private String buildDescription(ImportRow row) {
        StringBuilder sb = new StringBuilder();
        if (row.party() != null && !row.party().isBlank()) {
            sb.append(truncate(row.party().trim(), 300)).append(". ");
        }
        if (row.purpose() != null && !row.purpose().isBlank()) {
            sb.append(truncate(row.purpose().trim(), 650));
        }
        if (sb.isEmpty()) {
            sb.append("Операция из выписки №").append(row.orderNo());
        }
        return sb.toString();
    }

    private static Integer extractApartment(String purpose) {
        if (purpose == null) return null;
        Matcher am = APARTMENT_PATTERN.matcher(purpose);
        String last = null;
        while (am.find()) last = am.group(1);
        if (last == null) return null;
        try {
            int v = Integer.parseInt(last);
            return (v > 0 && v < 1000) ? v : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isDateLike(String text) {
        return text != null && text.matches("\\d{1,4}[./-]\\d{1,2}[./-]\\d{2,4}.*");
    }

    private static LocalDate parseDate(String text) {
        if (text == null) return null;
        String s = text.trim();
        Matcher m = Pattern.compile("^(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})").matcher(s);
        if (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (RuntimeException ignored) { }
        }
        m = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})").matcher(s);
        if (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private static BigDecimal parseAmount(String text) {
        if (text == null) return null;
        String s = text.replace('\u00A0', ' ').trim();
        if (s.isEmpty()) return null;
        Matcher dash = Pattern.compile("^(-?\\d+)-(\\d{2})$").matcher(s);
        if (dash.matches()) {
            return new BigDecimal(dash.group(1) + "." + dash.group(2));
        }
        String cleaned = s.replace(" ", "").replace(",", ".");
        if (cleaned.chars().filter(c -> c == '.').count() > 1) {
            cleaned = cleaned.replaceAll("\\.(?=\\D)", "");
        }
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String messageOf(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current && current.getMessage() == null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String label(ImportRow row) {
        return "Операция №" + (row.orderNo() == null || row.orderNo().isEmpty() ? "?" : row.orderNo())
                + " (" + row.date() + ", " + row.amount() + ")";
    }

    private static String cell(List<String[]> grid, int rowIdx, int colIdx) {
        if (rowIdx < 0 || rowIdx >= grid.size() || colIdx < 0) return null;
        String[] row = grid.get(rowIdx);
        if (colIdx >= row.length) return null;
        String v = row[colIdx];
        return v == null || v.isBlank() ? null : v;
    }

    /** Потоковый сборщик ячеек листа в «сетку» строк для разбора выписки. */
    private static final class GridCollector implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final List<String[]> grid;
        private String[] currentRow = new String[0];

        GridCollector(List<String[]> grid) { this.grid = grid; }

        @Override public void startRow(int rowNum) {
            while (grid.size() < rowNum) grid.add(new String[0]);
            currentRow = new String[0];
        }

        @Override public void endRow(int rowNum) {
            while (grid.size() <= rowNum) grid.add(new String[0]);
            grid.set(rowNum, currentRow);
        }

        @Override public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int col = cellRefToColumn(cellReference);
            if (col < 0 || col > 2000) return;
            if (currentRow.length < col + 1) {
                String[] grown = new String[Math.max(col + 1, currentRow.length * 2)];
                System.arraycopy(currentRow, 0, grown, 0, currentRow.length);
                currentRow = grown;
            }
            currentRow[col] = formattedValue;
        }

        static int cellRefToColumn(String ref) {
            int col = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch >= 'A' && ch <= 'Z') col = col * 26 + (ch - 'A' + 1);
                else if (ch >= 'a' && ch <= 'z') col = col * 26 + (ch - 'a' + 1);
                else break;
            }
            return col - 1;
        }
    }
}
