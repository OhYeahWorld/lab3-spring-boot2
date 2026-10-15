package ru.lab3.accounting.service;

import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.lab3.accounting.exception.DuplicateRecordException;
import ru.lab3.accounting.repository.ChargeRepository;
import ru.lab3.accounting.repository.PaymentRepository;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Загрузка операций из банковской выписки (Excel, формат «СберБизнес»).
 *
 * Выписка разбирается по блокам «ПЛАТЕЖНОЕ ПОРУЧЕНИЕ № ...». Из каждого блока
 * берутся: номер поручения, дата операции, сумма, плательщик/получатель и
 * назначение платежа. Поступления на счёт кооператива заносятся как платежи
 * жильцов, списания — как начисления (расходы). Если в тексте назначения
 * указан номер квартиры («кв. 76», «л/с 105» и т.п.), операция привязывается
 * к этой квартире; иначе создаётся служебная квартира 9000 + последние три
 * цифры номера счёта, чтобы загрузку можно было выполнить без ручного ввода.
 * Сальдо при необходимости создаётся автоматически (цепочка пересчитывается).
 */
@Service
public class StatementImportService {

    public record ImportRow(String orderNo, LocalDate date, BigDecimal amount, String party,
                            String purpose, boolean incoming, Integer apartment, String problem) {
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
    private static final Pattern DATE_IN_PURPOSE_PATTERN = Pattern.compile(
            "Дата оплаты (\\d{2})/(\\d{2})/(\\d{4})");
    private static final DataFormatter FORMATTER = new DataFormatter(Locale.forLanguageTag("ru-RU"));

    private final LedgerService ledgerService;
    private final ChargeRepository chargeRepository;
    private final PaymentRepository paymentRepository;

    public StatementImportService(LedgerService ledgerService,
                                  ChargeRepository chargeRepository,
                                  PaymentRepository paymentRepository) {
        this.ledgerService = ledgerService;
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public ImportResult importWorkbook(MultipartFile file) throws IOException {
        List<ImportRow> rows = parse(file);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "В файле не найдено ни одного блока «ПЛАТЕЖНОЕ ПОРУЧЕНИЕ». "
                            + "Проверьте, что загружен файл выписки Excel (.xlsx).");
        }

        long fallbackSeed = extractAccountTail(file.getOriginalFilename());

        int saldoCreated = 0;
        int paymentsCreated = 0;
        int chargesCreated = 0;
        List<String> problems = new ArrayList<>();
        LinkedHashMap<Integer, Boolean> touchedApartments = new LinkedHashMap<>();

        for (ImportRow row : rows) {
            if (row.problem() != null) {
                problems.add(row.orderNo().isEmpty() ? row.problem() : "Поручение №" + row.orderNo() + ": " + row.problem());
                continue;
            }
            Integer apartment = row.apartment() != null
                    ? row.apartment()
                    : (int) (9000 + fallbackSeed % 1000);
            touchedApartments.put(apartment, true);
            LocalDate period = row.date().withDayOfMonth(1);

            try {
                if (!ledgerService.saldoExists(apartment, period)) {
                    ledgerService.ensureSaldoChain(apartment, period);
                    saldoCreated++;
                }
                String reference = truncate("ПП №" + row.orderNo() + " от " + row.date(), 100);
                if (row.incoming()) {
                    ru.lab3.accounting.dto.PaymentForm form = new ru.lab3.accounting.dto.PaymentForm();
                    form.setApartmentNumber(apartment);
                    form.setPeriod(period.toString());
                    form.setPaymentTime(row.date().atStartOfDay().toString());
                    form.setAmount(row.amount().toPlainString());
                    form.setPaymentReference(reference);
                    ledgerService.createPayment(form);
                    paymentsCreated++;
                } else {
                    ru.lab3.accounting.dto.ChargeForm form = new ru.lab3.accounting.dto.ChargeForm();
                    form.setApartmentNumber(apartment);
                    form.setPeriod(period.toString());
                    form.setAmount(row.amount().toPlainString());
                    form.setDescription(reference + ". " + buildDescription(row));
                    ledgerService.createCharge(form);
                    chargesCreated++;
                }
            } catch (DuplicateRecordException ex) {
                problems.add("Поручение №" + row.orderNo() + " (" + row.date() + ", " + row.amount()
                        + "): " + ex.getMessage());
            } catch (DataIntegrityViolationException ex) {
                problems.add("Поручение №" + row.orderNo() + " (" + row.date() + ", " + row.amount()
                        + "): запись уже существует в базе.");
            } catch (RuntimeException ex) {
                problems.add("Поручение №" + row.orderNo() + " (" + row.date() + ", " + row.amount()
                        + "): " + messageOf(ex));
            }
        }

        return new ImportResult(saldoCreated, paymentsCreated, chargesCreated,
                List.copyOf(touchedApartments.keySet()), problems);
    }

    /**
     * Разбирает файл выписки на строки без записи в базу (для предпросмотра).
     * Используется потоковый (SAX) читатель POI: годовая выписка СберБизнес
     * в DOM-виде занимает сотни мегабайт и обрушивала приложение (OutOfMemory).
     */
    public List<ImportRow> parse(MultipartFile file) throws IOException {
        List<ImportRow> result = new ArrayList<>();
        byte[] content = file.getBytes();
        try (org.apache.poi.openxml4j.opc.OPCPackage pkg =
                     org.apache.poi.openxml4j.opc.OPCPackage.open(new java.io.ByteArrayInputStream(content))) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XMLReader parser = SAXHelper.newXMLReader();
            GridCollector collector = new GridCollector();
            parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, collector, FORMATTER, false));
            java.util.Iterator<InputStream> sheets = reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheet = sheets.next()) {
                    parser.parse(new InputSource(sheet));
                }
                result.addAll(parseBlocks(collector.takeGrid()));
            }
        } catch (org.apache.poi.openxml4j.exceptions.OpenXML4JException ex) {
            throw new IllegalArgumentException(
                    "Файл не является корректным Excel (.xlsx). Сохраните выписку в формате .xlsx и повторите загрузку.", ex);
        } catch (org.xml.sax.SAXException ex) {
            throw new IllegalArgumentException("Не удалось прочитать листы Excel-файла: " + messageOf(ex), ex);
        } catch (javax.xml.parsers.ParserConfigurationException ex) {
            throw new IllegalArgumentException("В среде запуска недоступен SAX-парсер XML: " + messageOf(ex), ex);
        }

        // Убираем точные повторы внутри самого файла (выписка иногда содержит один и тот же блок дважды).
        List<ImportRow> unique = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ImportRow r : result) {
            String key = r.orderNo() + "|" + r.date() + "|" + r.amount() + "|" + r.incoming();
            if (r.problem() == null && !seen.add(key)) {
                continue;
            }
            unique.add(r);
        }
        return unique;
    }

    private List<ImportRow> parseBlocks(List<String[]> grid) {
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < grid.size(); i++) {
            String orderLabel = cell(grid, i, 1);
            if (orderLabel == null) {
                continue;
            }
            Matcher om = ORDER_PATTERN.matcher(orderLabel);
            if (!om.find()) {
                continue;
            }
            String orderNo = om.group(1);

            // Ищем конец блока: следующий блок или строка «ИСПОЛНЕНО».
            int end = i + 1;
            while (end < grid.size()) {
                String label = cell(grid, end, 1);
                if (label != null && ORDER_PATTERN.matcher(label).find()) {
                    break;
                }
                if ("ИСПОЛНЕНО".equalsIgnoreCase(label == null ? "" : label.trim())) {
                    break;
                }
                end++;
            }

            LocalDate opDate = null;
            LocalDateTime stamp = null;
            boolean incoming = false;
            boolean outgoing = false;
            BigDecimal amount = null;
            String payer = null;
            String payee = null;
            String purpose = null;

            for (int j = i; j < end; j++) {
                String c2 = cell(grid, j, 1);
                String c18 = cell(grid, j, 17);
                String c26 = cell(grid, j, 25);
                String c30 = cell(grid, j, 29);

                if (opDate == null && isDateLike(c2) && isDateLike(c18)) {
                    stamp = parseDateTime(c2);
                    if (stamp == null) {
                        stamp = parseDateTime(c18);
                    }
                    if (stamp != null) {
                        opDate = stamp.toLocalDate();
                    }
                }
                if (c2 != null && containsIgnoreCase(c2, "поступ")) {
                    incoming = true;
                }
                if (c18 != null && containsIgnoreCase(c18, "списано")) {
                    outgoing = true;
                }
                if (c26 != null) {
                    String t = c26.trim();
                    if (t.equals("Сумма") && amount == null) {
                        amount = parseAmount(c30);
                    } else if (t.equals("Плательщик") && payer == null) {
                        payer = cell(grid, j - 1, 1);
                    } else if (t.equals("Получатель") && payee == null) {
                        payee = cell(grid, j - 1, 1);
                    }
                }
                if (purpose == null && c2 != null && c2.trim().equalsIgnoreCase("Назначение платежа")) {
                    purpose = cell(grid, j - 2, 1);
                }
            }

            String problem = null;
            if (opDate == null) {
                problem = "не найдена дата операции";
            } else if (amount == null || amount.signum() <= 0) {
                problem = "не найдена корректная сумма";
            } else if (incoming == outgoing) {
                problem = "не удалось определить тип операции (поступление/списание)";
            }

            Integer apartment = null;
            if (purpose != null) {
                Matcher am = APARTMENT_PATTERN.matcher(purpose);
                String last = null;
                while (am.find()) {
                    last = am.group(1);
                }
                if (last != null) {
                    try {
                        int v = Integer.parseInt(last);
                        if (v > 0 && v < 1000) {
                            apartment = v;
                        }
                    } catch (NumberFormatException ignored) {
                        // слишком длинная последовательность цифр — не номер квартиры
                    }
                }
            }

            rows.add(new ImportRow(orderNo, opDate, amount, incoming ? payer : payee, purpose,
                    incoming, apartment, problem));
            i = Math.max(i, end - 1);
        }
        return rows;
    }

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
        return truncate(sb.toString(), 1000);
    }

    private static boolean isDateLike(String text) {
        return text != null && text.matches("\\d{1,4}[./-]\\d{1,2}[./-]\\d{2,4}.*");
    }

    private static LocalDateTime parseDateTime(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        // Форматы, которые отдаёт Excel через DataFormatter для СберБизнес-выписок.
        List<String> patterns = List.of(
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
                "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd.MM.yyyy",
                "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy");
        for (String pattern : patterns) {
            try {
                if (pattern.contains("HH")) {
                    return LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern(pattern));
                }
                return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern(pattern)).atStartOfDay();
            } catch (RuntimeException ignored) {
                // пробуем следующий формат
            }
        }
        return null;
    }

    private static BigDecimal parseAmount(String text) {
        if (text == null) {
            return null;
        }
        String s = text.replace('\u00A0', ' ').trim();
        if (s.isEmpty()) {
            return null;
        }
        // «311-27» — рубли-копейки из выписки.
        Matcher dash = Pattern.compile("^(\\d+)-(\\d{2})$").matcher(s);
        if (dash.matches()) {
            return new BigDecimal(dash.group(1) + "." + dash.group(2));
        }
        // Нормализуем разделители тысяч/десятичной запятой.
        String cleaned = s.replace(" ", "").replace("\u00A0", "");
        cleaned = cleaned.replace(",", ".");
        if (cleaned.chars().filter(c -> c == '.').count() > 1) {
            cleaned = cleaned.replaceAll("\\.(?=\\D)", "");
        }
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(String source, String needle) {
        return source.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static long extractAccountTail(String filename) {
        if (filename != null) {
            Matcher m = Pattern.compile("(\\d{4})\\d*$").matcher(filename.replaceAll("\\D", ""));
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        }
        return 0L;
    }

    private static String messageOf(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current
                && current.getMessage() == null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String cell(List<String[]> grid, int rowIdx, int colIdx) {
        if (rowIdx < 0 || rowIdx >= grid.size()) {
            return null;
        }
        String[] row = grid.get(rowIdx);
        if (colIdx < 0 || colIdx >= row.length) {
            return null;
        }
        String v = row[colIdx];
        return v == null || v.isBlank() ? null : v;
    }

    /** Потоковый сборщик ячеек листа в «сетку» строк для разбора блоков выписки. */
    private static final class GridCollector implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final List<String[]> grid = new ArrayList<>();
        private String[] currentRow = new String[0];
        private int currentRowIdx = -1;

        @Override
        public void startRow(int rowNum) {
            while (grid.size() < rowNum) {
                grid.add(new String[0]);
            }
            currentRow = new String[0];
            currentRowIdx = rowNum;
        }

        @Override
        public void endRow(int rowNum) {
            while (grid.size() <= rowNum) {
                grid.add(new String[0]);
            }
            grid.set(rowNum, currentRow);
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            int col = cellRefToColumn(cellReference);
            if (col < 0 || col > 2000) {
                return;
            }
            if (currentRow.length < col + 1) {
                String[] grown = new String[Math.max(col + 1, currentRow.length * 2)];
                System.arraycopy(currentRow, 0, grown, 0, currentRow.length);
                currentRow = grown;
            }
            currentRow[col] = formattedValue;
        }

        List<String[]> takeGrid() {
            List<String[]> copy = new ArrayList<>(grid);
            grid.clear();
            return copy;
        }

        private static int cellRefToColumn(String ref) {
            int col = 0;
            for (int i = 0; i < ref.length(); i++) {
                char ch = ref.charAt(i);
                if (ch >= 'A' && ch <= 'Z') {
                    col = col * 26 + (ch - 'A' + 1);
                } else if (ch >= 'a' && ch <= 'z') {
                    col = col * 26 + (ch - 'a' + 1);
                } else {
                    break;
                }
            }
            return col - 1;
        }
    }
}
