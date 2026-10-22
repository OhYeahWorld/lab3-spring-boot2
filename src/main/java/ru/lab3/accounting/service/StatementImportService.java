package ru.lab3.accounting.service;

import jakarta.annotation.PreDestroy;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.lab3.accounting.dto.ChargeForm;
import ru.lab3.accounting.dto.PaymentForm;
import ru.lab3.accounting.repository.ChargeRepository;
import ru.lab3.accounting.repository.PaymentRepository;
import ru.lab3.accounting.repository.SaldoRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор банковской выписки СберБизнес (.xlsx/.csv) и импорт операций в базу.
 *
 * Поддерживаемые виды файла:
 *  1) Таблица операций (лист «ВЫПИСКА ОПЕРАЦИЙ ПО ЛИЦЕВОМУ СЧЕТУ» с колонками
 *     «Дата проводки / Сумма по дебету / Сумма по кредиту / № документа /
 *     Назначение платежа») — основной формат;
 *  2) Печатные бланки платёжных поручений (лист «operations», где каждое
 *     поручение занимает несколько десятков строк) — запасной вариант;
 *  3) Простой CSV/TSV с датой в первой колонке.
 *
 * Файл читается потоковым SAX-ридером POI в «сетку» отформатированных строк,
 * поэтому большие выписки не требуют много памяти. Таблицы операций и блоки
 * платёжных поручений разбираются на всех листах файла.
 *
 * Тип операции определяется по позиции нашего расчётного счёта (самый частый
 * номер вида 40xxxxxxxxxxxxxxxxx): по КРЕДИТУ — поступление, по ДЕБЕТУ —
 * списание.
 *
 * Квартира извлекается из назначения платежа по распространённым форматам
 * («л/с 164», «ЛС 49», «ЛСЧ 0000000134», «кв. 76», «квартира 12»,
 * «лиц. счет 88»), затем из адреса плательщика («… Д 12 К 2 КВ 164»),
 * затем из хвоста маски лицевого счёта («МИЧУРИНСКИЙ,38,86»).
 *
 * Любую распознанную строку можно исправить прямо на странице предпросмотра:
 * дату, время, сумму, тип операции и номер квартиры. Итоговая проблема строки
 * всегда пересчитывается по фактическим данным ({@link #recomputeProblem}),
 * поэтому вписанный вручную номер квартиры снимает метку
 * «не удалось определить номер квартиры» и строка импортируется.
 */
@Service
public class StatementImportService {

    private static final Pattern DATE_TIME = Pattern.compile(
            "^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");
    /** Excel-serial даты в диапазоне 2000–2100 годов (на случай, если лист отдаёт сырое число). */
    private static final double SERIAL_MIN = 36526d;   // 01.01.2000 ≈ 36526
    private static final double SERIAL_MAX = 73415d;   // ~31.12.2100
    private static final Pattern ORDER_NO = Pattern.compile(
            "(?:ПЛАТЕЖНОЕ\\s+ПОРУЧЕНИЕ|РЕКВИЗИТЫ)\\s*№?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    /** л/с, л.с, ЛС 123, ЛСЧ 0000000111, «л с 164», кв., квартира 76, лиц. счет 88 — берётся последнее совпадение. */
    private static final Pattern APARTMENT_HINT = Pattern.compile(
            "(?:кв(?:артир)?а?|л[./\\s-]?с[./\\sч]*|лиц[.\\s-]*(?:счет|счёт))[^0-9a-zа-я]{0,4}(\\d{1,9})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Маска лицевого счёта в адресе: «…,38,86» -> квартира 86. */
    private static final Pattern ACCOUNT_MASK_TAIL = Pattern.compile(
            "[,.]\\d{1,3}[,.](\\d{2,3})(?=\\D|$)");
    /** Все 20-значные номера счетов в тексте (для определения нашего счёта). */
    private static final Pattern ACCOUNT20 = Pattern.compile("\\d{20}");
    private static final Pattern OUR_ACCOUNT_GUESS = Pattern.compile("40\\d{18}");
    /** Адресный хвост вида «Д 12 К 2 КВ 164»: дом, корпус, квартира. */
    private static final Pattern ADDRESS_TRIPLE = Pattern.compile(
            "(?:Д[.\\s]?\\s*(\\d{1,4}))\\s*(?:К[.\\s]?\\s*(\\d{1,3}))?\\s*(?:КВ[.\\s]?\\s*(\\d{1,4}))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Явные подсказки «л/с N», «лицевой счёт N». */
    private static final Pattern LS_EXPLICIT = Pattern.compile(
            "(?:л[/\\s.]?с[\\sч.]*|лиц[.\\s-]*(?:счет|счёт))[^0-9]{0,3}(\\d{1,9})(?![0-9])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Явная подсказка «кв./квартира N». */
    private static final Pattern KV_EXPLICIT = Pattern.compile(
            "(?:кв(?:артир)?а?)[^0-9а-я]{0,3}(\\d{1,4})(?![0-9])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final String PROBLEM_DATE = "не удалось определить дату операции";
    public static final String PROBLEM_AMOUNT = "не удалось определить сумму";
    public static final String PROBLEM_TYPE = "не удалось определить тип операции (поступление/списание)";
    public static final String PROBLEM_APARTMENT = "не удалось определить номер квартиры";

    private static final int MAX_SHEET_ROWS = 200_000;
    private static final int PARSE_TIMEOUT_SECONDS = 120;

    private final LedgerService ledgerService;
    private final ChargeRepository chargeRepository;
    private final PaymentRepository paymentRepository;
    private final SaldoRepository saldoRepository;
    private final ExecutorService parseExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "statement-parser");
        t.setDaemon(true);
        return t;
    });

    public StatementImportService(LedgerService ledgerService,
                                  ChargeRepository chargeRepository,
                                  PaymentRepository paymentRepository,
                                  SaldoRepository saldoRepository) {
        this.ledgerService = ledgerService;
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
        this.saldoRepository = saldoRepository;
    }

    @PreDestroy
    public void shutdown() {
        parseExecutor.shutdownNow();
    }

    // ==================== модель данных ====================

    /**
     * Одна операция выписки после разбора (или с описанием проблемы).
     * {@code typeKnown}: TRUE — поступление, FALSE — списание, null — тип
     * не определён (пользователь должен выбрать его в предпросмотре).
     */
    public record ImportRow(int orderNo, LocalDate date, LocalDateTime executedAt, BigDecimal amount,
                            String party, String purpose, Boolean typeKnown, Integer apartment,
                            String problem) {

        /** Поступление ли это (неизвестный тип трактуется как «нет»). */
        public boolean incoming() {
            return Boolean.TRUE.equals(typeKnown);
        }

        public String timeText() {
            if (executedAt == null) {
                return "";
            }
            return "%02d:%02d".formatted(executedAt.getHour(), executedAt.getMinute());
        }

        public ImportRow withOrderNo(int newOrderNo) {
            return new ImportRow(newOrderNo, date, executedAt, amount, party, purpose,
                    typeKnown, apartment, problem);
        }

        public ImportRow withApartment(Integer newApartment) {
            return new ImportRow(orderNo, date, executedAt, amount, party, purpose,
                    typeKnown, newApartment, problem);
        }

        public ImportRow withAmount(BigDecimal newAmount) {
            return new ImportRow(orderNo, date, executedAt, newAmount, party, purpose,
                    typeKnown, apartment, problem);
        }

        public ImportRow withType(Boolean newTypeKnown) {
            return new ImportRow(orderNo, date, executedAt, amount, party, purpose,
                    newTypeKnown, apartment, problem);
        }

        public ImportRow withDate(LocalDate newDate) {
            LocalDateTime newTime = newDate == null ? executedAt : newDate.atStartOfDay();
            return new ImportRow(orderNo, newDate, newTime, amount, party, purpose,
                    typeKnown, apartment, problem);
        }
    }

    /** Результат импорта: сколько записей создано и какие строки были пропущены. */
    public record ImportResult(int paymentsCreated, int chargesCreated, int saldoCreated,
                               List<String> problems, Set<Integer> apartments) {
    }

    // ==================== публичный разбор ====================

    public List<ImportRow> parse(MultipartFile file) throws IOException {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        byte[] content = file.getBytes();
        if (name.endsWith(".csv") || looksLikeCsv(content)) {
            return renumber(parseCsv(new String(content, StandardCharsets.UTF_8)));
        }
        List<List<String[]>> sheets = readWorkbook(content);

        // Выписка операций и платёжные поручения могут находиться на разных листах.
        List<ImportRow> tableRows = new ArrayList<>();
        for (List<String[]> grid : sheets) {
            tableRows.addAll(parseOperationTable(grid));
        }
        List<ImportRow> blockRows = new ArrayList<>();
        for (List<String[]> grid : sheets) {
            blockRows.addAll(parseBlocks(grid));
        }
        if (!tableRows.isEmpty() || !blockRows.isEmpty()) {
            tableRows = deduplicateExact(tableRows);
            Set<String> unmatchedTableRows = new HashSet<>();
            for (ImportRow row : tableRows) {
                unmatchedTableRows.add(exactDuplicateKey(row));
            }
            for (ImportRow row : blockRows) {
                if (!unmatchedTableRows.remove(exactDuplicateKey(row))) {
                    tableRows.add(row);
                }
            }
            return renumber(tableRows);
        }
        throw new IllegalArgumentException(
                "в файле нет ни таблицы операций («Дата проводки / Сумма по дебету / Сумма по кредиту»), "
                        + "ни блоков платёжных поручений («ПЛАТЕЖНОЕ ПОРУЧЕНИЕ № …»).");
    }

    /**
     * Убирает только полные дубликаты одной и той же операции (дата+время,
     * контрагент, сумма, тип), возникшие из-за повторного чтения одного листа.
     * Разные платежи одного лица в один день остаются — они не дубликаты.
     */
    private List<ImportRow> deduplicateExact(List<ImportRow> rows) {
        List<ImportRow> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ImportRow r : rows) {
            if (seen.add(exactDuplicateKey(r))) {
                unique.add(r);
            }
        }
        return unique;
    }

    private static String exactDuplicateKey(ImportRow row) {
        return row.executedAt() + "|" + row.party() + "|" + row.amount() + "|" + row.typeKnown();
    }

    /** Последовательная нумерация строк предпросмотра. */
    private static List<ImportRow> renumber(List<ImportRow> rows) {
        List<ImportRow> result = new ArrayList<>(rows.size());
        int order = 0;
        for (ImportRow r : rows) {
            result.add(r.withOrderNo(++order));
        }
        return result;
    }

    // ==================== чтение .xlsx (SAX, потоково) ====================

    private List<List<String[]>> readWorkbook(byte[] content) throws IOException {
        Future<List<List<String[]>>> future = parseExecutor.submit(() -> loadSheets(content));
        try {
            return future.get(PARSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            future.cancel(true);
            throw new IllegalArgumentException(
                    "файл слишком большой для обработки (лимит " + PARSE_TIMEOUT_SECONDS + " с).");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("разбор файла прерван", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof IllegalStateException ise) {
                throw new IllegalArgumentException("файл повреждён или не является документом Excel (.xlsx): "
                        + Optional.ofNullable(ise.getMessage()).orElse("нет данных"), ise);
            }
            throw new IOException(cause.getMessage() == null ? cause.getClass().getSimpleName()
                    : cause.getMessage(), cause);
        }
    }

    private List<List<String[]>> loadSheets(byte[] content) throws Exception {
        List<List<String[]>> sheets = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(content))) {
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings strings = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();
            java.util.Iterator<InputStream> sheetStream = reader.getSheetsData();
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("ru-RU"));
            while (sheetStream.hasNext()) {
                List<String[]> grid = new ArrayList<>();
                org.xml.sax.XMLReader parser = SAXHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(
                        styles, null, strings, new GridCollector(grid), formatter, false));
                try (InputStream sheet = sheetStream.next()) {
                    parser.parse(new org.xml.sax.InputSource(sheet));
                }
                if (grid.size() > MAX_SHEET_ROWS) {
                    throw new IllegalArgumentException("слишком большой лист: " + grid.size()
                            + " строк (максимум " + MAX_SHEET_ROWS + ")");
                }
                sheets.add(grid);
            }
        }
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("в файле нет ни одного листа.");
        }
        return sheets;
    }

    // ==================== стратегия 1: таблица операций ====================

    /** Колонки находим по подписям заголовков, а не по жёстким индексам. */
    private record TableLayout(int headerRow, int dateCol, int debitAccCol, int creditAccCol,
                               int debitSumCol, int creditSumCol, int purposeCol) {
    }

    private List<ImportRow> parseOperationTable(List<String[]> grid) {
        TableLayout layout = findTableLayout(grid);
        if (layout == null) {
            return List.of();
        }
        String ourAccount = guessOurAccount(countAccounts(grid));
        List<ImportRow> rows = new ArrayList<>();
        int order = 0;
        for (int r = layout.headerRow() + 1; r < grid.size(); r++) {
            DateParts dp = parseDateTimeText(cell(grid, r, layout.dateCol()));
            if (dp == null) {
                continue; // служебные/пустые строки между периодами
            }
            BigDecimal debitSum = parseAmount(cell(grid, r, layout.debitSumCol()));
            BigDecimal creditSum = parseAmount(cell(grid, r, layout.creditSumCol()));
            String debitAcc = nvl(cell(grid, r, layout.debitAccCol()));
            String creditAcc = nvl(cell(grid, r, layout.creditAccCol()));
            String purpose = stripBankPrefix(trimToEmpty(cell(grid, r, layout.purposeCol())));

            // Сумма есть только с одной стороны; при сомнении сторону уточняем
            // по позиции нашего счёта (у поступления он в кредит, у списания — в дебет).
            Boolean typeKnown;
            BigDecimal amount;
            if (creditSum != null && creditSum.signum() > 0) {
                typeKnown = true;
                amount = creditSum;
            } else if (debitSum != null && debitSum.signum() > 0) {
                typeKnown = false;
                amount = debitSum;
            } else {
                rows.add(new ImportRow(++order, dp.date(), dp.dateTime(), null,
                        "", purpose, null, null, PROBLEM_AMOUNT));
                continue;
            }
            if (ourAccount != null) {
                boolean ourOnDebit = containsAccount(debitAcc, ourAccount);
                boolean ourOnCredit = containsAccount(creditAcc, ourAccount);
                if (ourOnCredit && !ourOnDebit) {
                    typeKnown = true;
                } else if (ourOnDebit && !ourOnCredit) {
                    typeKnown = false;
                }
            }

            // Контрагент и квартира всегда в ПРОТИВОПОЛОЖНОЙ колонке счетов:
            // ячейка с нашим счётом содержит лишь реквизиты самого кооператива.
            String counterpartyCell = typeKnown ? debitAcc : creditAcc;
            String party = extractPartyName(counterpartyCell);
            Integer apartment = extractApartment(purpose, counterpartyCell, party);

            String problem = null;
            if (amount.signum() <= 0) {
                problem = PROBLEM_AMOUNT;
            } else if (apartment == null) {
                problem = PROBLEM_APARTMENT;
            }
            rows.add(new ImportRow(++order, dp.date(), dp.dateTime(), amount,
                    party, purpose, typeKnown, apartment, problem));
        }
        return rows;
    }

    private TableLayout findTableLayout(List<String[]> grid) {
        int lastScan = Math.min(grid.size(), 40);
        for (int r = 0; r < lastScan; r++) {
            String[] row = grid.get(r);
            int dateCol = -1;
            int debitSumCol = -1;
            int creditSumCol = -1;
            int purposeCol = -1;
            int debitAccCol = -1;
            int creditAccCol = -1;
            for (int c = 0; c < row.length; c++) {
                String v = row[c];
                if (v == null) {
                    continue;
                }
                String t = v.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                if (dateCol < 0 && (t.startsWith("дата проводки") || t.equals("дата"))) {
                    dateCol = c;
                } else if (debitSumCol < 0 && t.contains("по дебету")) {
                    debitSumCol = c;
                } else if (creditSumCol < 0 && t.contains("по кредиту")) {
                    creditSumCol = c;
                } else if (purposeCol < 0 && t.startsWith("назначение")) {
                    purposeCol = c;
                } else if (debitAccCol < 0 && t.equals("дебет")) {
                    debitAccCol = c;
                } else if (creditAccCol < 0 && t.equals("кредит")) {
                    creditAccCol = c;
                }
            }
            if (dateCol >= 0 && (debitSumCol >= 0 || creditSumCol >= 0)) {
                // Подписи «Дебет»/«Кредит» могут стоять на строку ВЫШЕ или НИЖЕ
                // заголовков сумм — сканируем соседние строки в обе стороны.
                if (debitAccCol < 0 || creditAccCol < 0) {
                    for (int up = Math.max(0, r - 2); up <= Math.min(grid.size() - 1, r + 2); up++) {
                        if (up == r) {
                            continue;
                        }
                        String[] neighbour = grid.get(up);
                        if (neighbour == null) {
                            continue;
                        }
                        for (int c = 0; c < neighbour.length; c++) {
                            String t = neighbour[c] == null ? null : neighbour[c].trim().toLowerCase(Locale.ROOT);
                            if ("дебет".equals(t) && debitAccCol < 0 && isAccountColumnNear(grid, r, c, debitSumCol)) {
                                debitAccCol = c;
                            } else if ("кредит".equals(t) && creditAccCol < 0 && isAccountColumnNear(grid, r, c, creditSumCol)) {
                                creditAccCol = c;
                            }
                        }
                    }
                }
                if (debitAccCol < 0) {
                    debitAccCol = findAccountColumn(grid, r, debitSumCol);
                }
                if (creditAccCol < 0) {
                    creditAccCol = findAccountColumn(grid, r, creditSumCol);
                }
                return new TableLayout(r, dateCol, debitAccCol, creditAccCol,
                        debitSumCol, creditSumCol, purposeCol);
            }
        }
        return null;
    }

    /** Колонка со 20-значными счетами должна лежать левее колонки сумм этой же стороны. */
    private boolean isAccountColumnNear(List<String[]> grid, int headerRow, int accCol, int sumCol) {
        if (sumCol >= 0 && accCol > sumCol) {
            return false;
        }
        for (int r = headerRow + 1; r <= Math.min(headerRow + 3, grid.size() - 1); r++) {
            String v = cell(grid, r, accCol);
            if (v != null && ACCOUNT20.matcher(v.replaceAll("\\s", "")).find()) {
                return true;
            }
        }
        return false;
    }

    /** Ищем подпись «Дебет»/«Кредит» чуть выше колонки суммы — это колонка счетов. */
    private int findAccountColumn(List<String[]> grid, int headerRow, int sumCol) {
        if (sumCol < 0) {
            return -1;
        }
        for (int r = Math.max(0, headerRow - 2); r <= headerRow; r++) {
            String[] row = grid.get(r);
            if (row == null) {
                continue;
            }
            for (int c = Math.max(0, sumCol - 6); c <= sumCol && c < row.length; c++) {
                String t = row[c] == null ? null : row[c].trim().toLowerCase(Locale.ROOT);
                if ("дебет".equals(t) || "кредит".equals(t)) {
                    return c;
                }
            }
        }
        return Math.max(0, sumCol - 1);
    }

    /** Частотность всех 20-значных счетов по сетке — по ней определяем «наш» счёт. */
    private Map<String, Integer> countAccounts(List<String[]> grid) {
        Map<String, Integer> counts = new HashMap<>();
        for (String[] row : grid) {
            for (String v : row) {
                if (v == null || v.indexOf('4') < 0) {
                    continue;
                }
                Matcher m = ACCOUNT20.matcher(v.replaceAll("\\s", ""));
                while (m.find()) {
                    counts.merge(m.group(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private String guessOurAccount(Map<String, Integer> counts) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getKey().startsWith("40") && e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private boolean containsAccount(String cellValue, String account) {
        return cellValue != null && account != null
                && cellValue.replaceAll("\\s", "").contains(account);
    }

    // ==================== стратегия 2: блоки поручений ====================

    private List<ImportRow> parseBlocks(List<String[]> grid) {
        List<Integer> starts = blockStarts(grid);
        if (starts.size() < 3) {
            return List.of(); // это не лист с поручениями
        }
        String ourAccount = guessOurAccount(countAccounts(grid));
        if (ourAccount == null) {
            Matcher m = OUR_ACCOUNT_GUESS.matcher(flatten(grid, 300));
            if (m.find()) {
                ourAccount = m.group();
            }
        }
        List<ImportRow> rows = new ArrayList<>();
        int order = 0;
        for (int bi = 0; bi < starts.size(); bi++) {
            int start = starts.get(bi);
            int end = (bi + 1 < starts.size()) ? starts.get(bi + 1) : grid.size();
            BlockData data = readBlock(grid, start, end, bi > 0 ? starts.get(bi - 1) : -1, ourAccount);
            Integer apartment = extractApartment(data.purpose(), data.party());
            String problem = null;
            if (data.date() == null) {
                problem = PROBLEM_DATE;
            } else if (data.amount() == null || data.amount().signum() <= 0) {
                problem = PROBLEM_AMOUNT;
            } else if (data.typeKnown() == null) {
                problem = PROBLEM_TYPE;
            } else if (apartment == null) {
                problem = PROBLEM_APARTMENT;
            }
            rows.add(new ImportRow(++order, data.date(), data.executedAt(), data.amount(),
                    data.party(), data.purpose(), data.typeKnown(), apartment, problem));
        }
        return rows;
    }

    private List<Integer> blockStarts(List<String[]> grid) {
        List<Integer> starts = new ArrayList<>();
        for (int r = 0; r < grid.size(); r++) {
            String[] row = grid.get(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < Math.min(row.length, 6); c++) {
                String text = row[c];
                if (text != null && ORDER_NO.matcher(text).find()) {
                    starts.add(r);
                    break;
                }
            }
        }
        return starts;
    }

    private record BlockData(LocalDate date, LocalDateTime executedAt, BigDecimal amount,
                             Boolean typeKnown, String party, String purpose) {
    }

    private BlockData readBlock(List<String[]> grid, int start, int end, int prevStart, String ourAccount) {
        LocalDate opDate = null;
        LocalDateTime opTime = null;
        int postLabelRow = -1;
        int writtenOffLabelRow = -1;

        // Шапка блока — между концом предыдущего блока и началом этого.
        int windowFrom = Math.max(0, prevStart + 1);
        for (int r = start - 1; r >= windowFrom; r--) {
            String[] row = grid.get(r);
            if (row == null) {
                continue;
            }
            for (int c = 0; c < row.length; c++) {
                String t = row[c];
                if (t == null) {
                    continue;
                }
                String tl = t.trim().toLowerCase(Locale.ROOT);
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
                    opTime = dp.dateTime();
                }
            }
            if (postLabelRow >= 0 && writtenOffLabelRow >= 0 && opDate != null) {
                break;
            }
        }
        Boolean typeKnown = null;
        if (postLabelRow >= 0 && writtenOffLabelRow >= 0) {
            // У поступления метка «Поступ. в банк плат.» стоит НИЖЕ «Списано со сч. плат.»
            typeKnown = postLabelRow > writtenOffLabelRow;
        }

        BigDecimal amount = null;
        String payer = null;
        String payee = null;
        String purpose = null;
        Boolean typeByAccount = null;
        for (int r = start; r < end; r++) {
            String[] row = grid.get(r);
            if (row == null) {
                continue;
            }
            String first = cell(grid, r, 1);
            String labelZ = cell(grid, r, 25);
            String value29 = cell(grid, r, 29);

            if (amount == null && labelZ != null && labelZ.equalsIgnoreCase("сумма")) {
                amount = parseAmount(value29);
            }
            if (first != null) {
                String fl = first.trim().toLowerCase(Locale.ROOT);
                if (fl.equals("плательщик") && payer == null) {
                    payer = nearestNameAbove(grid, r, start);
                } else if (fl.equals("получатель") && payee == null) {
                    payee = nearestNameBelow(grid, r, end);
                } else if (purpose == null && fl.contains("назначение платеж") && first.length() > 25) {
                    purpose = stripPurposePrefix(first);
                }
            }
            if (ourAccount != null && typeByAccount == null
                    && labelZ != null && labelZ.trim().startsWith("Сч. №") && value29 != null
                    && containsAccount(value29, ourAccount)) {
                // наш счёт в группе «Плательщик» => поступление, в «Получатель» => списание
                typeByAccount = labelGroupAbove(grid, r, start);
            }
        }
        if (purpose == null) {
            purpose = "";
        }
        if (typeKnown == null) {
            typeKnown = typeByAccount;
        }
        if (typeKnown == null && (payer != null || payee != null)) {
            String low = (nvl(payee) + " " + nvl(payer)).toLowerCase(Locale.ROOT);
            if (low.contains("кооператив") || low.contains("дубна")) {
                typeKnown = payee != null;
            }
        }
        String party = Boolean.TRUE.equals(typeKnown) ? payer : payee;
        LocalDateTime executedAt = opDate == null ? null
                : opDate.atTime(opTime == null ? 0 : opTime.getHour(),
                opTime == null ? 0 : opTime.getMinute(),
                opTime == null ? 0 : opTime.getSecond());
        return new BlockData(opDate, executedAt, amount, typeKnown,
                shortParty(party == null ? "" : party), purpose);
    }

    /** true, если группа реквизитов относится к плательщику (поступление), false — к получателю. */
    private Boolean labelGroupAbove(List<String[]> grid, int fromRow, int blockStart) {
        for (int r = fromRow; r >= blockStart; r--) {
            String t = cell(grid, r, 1);
            if (t == null) {
                continue;
            }
            String tl = t.trim().toLowerCase(Locale.ROOT);
            if (tl.startsWith("плательщик")) {
                return true;
            }
            if (tl.startsWith("получатель")) {
                return false;
            }
        }
        return null;
    }

    private DateParts parseDateAnyCell(String[] row) {
        for (int c = 0; c < Math.min(row.length, 24); c++) {
            DateParts dp = parseDateTimeText(row[c]);
            if (dp != null) {
                return dp;
            }
        }
        return null;
    }

    private String nearestNameAbove(List<String[]> grid, int labelRow, int fromRow) {
        for (int r = labelRow - 1; r >= Math.max(fromRow, labelRow - 4); r--) {
            String t = cell(grid, r, 1);
            if (isName(t)) {
                return t;
            }
        }
        return null;
    }

    private String nearestNameBelow(List<String[]> grid, int labelRow, int toRow) {
        for (int r = labelRow + 1; r < Math.min(toRow, labelRow + 3); r++) {
            String t = cell(grid, r, 1);
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

    // ==================== разбор CSV ====================

    private boolean looksLikeCsv(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, 4096), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return head.contains("дата") && (head.contains("назначение") || head.contains("summa")
                || head.contains("сумма")) && !head.contains("<?xml");
    }

    private List<ImportRow> parseCsv(String text) {
        List<ImportRow> rows = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("[;\t,]");
            DateParts dp = parseDateTimeText(parts[0].trim());
            if (parts.length < 2 || dp == null) {
                continue; // шапка и служебные строки
            }
            rows.add(new ImportRow(0, dp.date(), dp.dateTime(), null, "",
                    String.join(" ", Arrays.asList(parts).subList(1, parts.length)).trim(),
                    null, null, PROBLEM_AMOUNT));
        }
        return rows;
    }

    // ==================== общие утилиты ====================

    private record DateParts(LocalDate date, LocalDateTime dateTime) {
    }

    /** Поддерживает ISO («2018-01-15 05:45:38»), русскоязычный вид («15.01.2018», «15/1/18») и serial Excel. */
    private DateParts parseDateTimeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.replace('\u00A0', ' ').trim();
        Matcher m = DATE_TIME.matcher(s);
        if (m.find()) {
            try {
                LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
                int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
                int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
                return new DateParts(date, date.atTime(Math.min(hour, 23), Math.min(minute, 59),
                        Math.min(second, 59)));
            } catch (RuntimeException ex) {
                return null;
            }
        }
        // Excel serial: «43103.24» или «43103,24» — только если число целиком и в диапазоне лет.
        String num = s.replace(',', '.');
        if (num.matches("\\d{5}(\\.\\d+)?")) {
            try {
                double serial = Double.parseDouble(num);
                if (serial >= SERIAL_MIN && serial <= SERIAL_MAX) {
                    LocalDate epoch = LocalDate.of(1899, 12, 30);
                    LocalDate d = epoch.plusDays((long) Math.floor(serial));
                    long dayMillis = Math.round((serial % 1) * 86_400_000d);
                    LocalDateTime dt = d.atStartOfDay().plusNanos(dayMillis * 1_000_000L);
                    return new DateParts(d, dt);
                }
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        try {
            LocalDate date = LedgerService.parseDateText(s.split("[ T]")[0]);
            return new DateParts(date, date.atStartOfDay());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Номер квартиры: сначала из назначения, затем из реквизитов контрагента. */
    private Integer extractApartment(String purpose, String fallbackText) {
        return extractApartment(purpose, fallbackText, null);
    }

    /**
     * Квартира ищется по цепочке эвристик: явные подсказки в назначении
     * (л/с &gt; кв.) → другие числа назначения → адрес контрагента
     * («КВ 164», «Д 12 К 2», «12-2-24») → маска лицевого счёта в адресе
     * («…,38,86»). Первая успешная попытка даёт результат.
     */
    private Integer extractApartment(String purpose, String fallbackText, String partyName) {
        Integer explicit = explicitApartmentHint(purpose);
        if (explicit != null) {
            return explicit;
        }
        Integer fromPurpose = lastApartmentMatch(purpose);
        if (fromPurpose != null) {
            return fromPurpose;
        }
        Integer fromAddress = addressApartment(fallbackText);
        if (fromAddress != null) {
            return fromAddress;
        }
        Integer fromParty = apartmentFromParty(partyName);
        if (fromParty != null) {
            return fromParty;
        }
        Integer fromFallbackHint = lastApartmentMatch(fallbackText);
        if (fromFallbackHint != null) {
            return fromFallbackHint;
        }
        Integer fromPartyTail = apartmentFromParty(fallbackText);
        if (fromPartyTail != null) {
            return fromPartyTail;
        }
        return lastMaskTailMatch(fallbackText);
    }

    /**
     * Квартира из строки контрагента вида
     * «ПАО СБЕРБАНК//ИВАНОВ И.И.//326320605993//МОСКВА Г ОСЕННИЙ Б-Р Д 12 К 2 КВ 164//…»:
     * явное «КВ N», адресная маска «ДОМ-КОРПУС-КВАРТИРА», хвост адреса «…,38,86».
     */
    private Integer apartmentFromParty(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Integer kv = explicitApartmentHint(text);
        if (kv != null) {
            return kv;
        }
        Integer triple = addressApartment(text);
        if (triple != null) {
            return triple;
        }
        // Сегменты после двойного слэша: ищем адресную часть (после длинного номера карты/ИНН).
        for (String seg : text.split("//")) {
            String s = seg.trim();
            if (s.matches("\\d{10,20}")) {
                continue; // номер карты/счета — не адрес
            }
            if (s.matches(".*\\d{1,4}[,.]\\s*\\d{1,4}.*") && !s.matches("\\d+")) {
                Integer tail = lastMaskTailMatch(s);
                if (tail != null) {
                    return tail;
                }
            }
        }
        return null;
    }

    /** Явные подсказки: сначала л/с (в т.ч. маска 0000000134), затем «кв./квартира N». */
    private Integer explicitApartmentHint(String text) {
        if (text == null) {
            return null;
        }
        Matcher ls = LS_EXPLICIT.matcher(text);
        Integer fromLs = null;
        while (ls.find()) {
            Integer v = normalizeAccountNumber(ls.group(1));
            if (v != null) {
                fromLs = v;
            }
        }
        if (fromLs != null) {
            return fromLs;
        }
        Matcher kv = KV_EXPLICIT.matcher(text);
        Integer fromKv = null;
        while (kv.find()) {
            int v = Integer.parseInt(kv.group(1));
            if (v > 0 && v < 1000) {
                fromKv = v;
            }
        }
        return fromKv;
    }

    /** Чистый номер лицевого счёта: длинные маски типа 0000000134 -> 134. */
    private Integer normalizeAccountNumber(String digits) {
        try {
            int v = Integer.parseInt(digits.replaceFirst("^0+(?!$)", ""));
            return (v > 0 && v < 1000) ? v : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Квартира из адресного хвоста: «… Д 12 К 2 КВ 164» или «ОСЕННИЙ Б-Р 12-2-24». */
    private Integer addressApartment(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = ADDRESS_TRIPLE.matcher(text);
        Integer last = null;
        while (m.find()) {
            if (m.group(3) != null) {
                int v = Integer.parseInt(m.group(3));
                if (v > 0 && v < 1000) {
                    last = v;
                }
            }
        }
        if (last != null) {
            return last;
        }
        Matcher triple = Pattern.compile("(?<![\\d/.])(\\d{1,4})-(\\d{1,3})-(\\d{1,4})(?![\\d/.])")
                .matcher(text.toUpperCase(Locale.ROOT));
        while (triple.find()) {
            int apt = Integer.parseInt(triple.group(3));
            if (apt > 0 && apt < 1000) {
                last = apt;
            }
        }
        return last;
    }

    /** Последнее совпадение общей подсказки («л/с N», «кв. N», «лиц. счет N») с маской 0000000134 -> 134. */
    private Integer lastApartmentMatch(String text) {
        if (text == null) {
            return null;
        }
        Integer last = null;
        Matcher m = APARTMENT_HINT.matcher(text);
        while (m.find()) {
            Integer v = normalizeAccountNumber(m.group(1));
            if (v != null) {
                last = v;
            }
        }
        return last;
    }

    private Integer lastMaskTailMatch(String text) {
        if (text == null) {
            return null;
        }
        Integer last = null;
        Matcher m = ACCOUNT_MASK_TAIL.matcher(text);
        while (m.find()) {
            int value = Integer.parseInt(m.group(1));
            if (value > 0 && value < 1000) {
                last = value;
            }
        }
        return last;
    }

    /**
     * Имя контрагента из ячейки реквизитов: «302... | ИНН | ПАО СБЕРБАНК//ИВАНОВ И.И.//карта//АДРЕС//»
     * -> «ИВАНОВ И.И.». Берётся первая осмысленная текстовая строка и сегмент после «//».
     */
    private String extractPartyName(String accountCell) {
        String base = shortParty(accountCell);
        if (base.isEmpty()) {
            return base;
        }
        String[] seg = base.split("//");
        if (seg.length >= 2 && !seg[1].trim().isEmpty()) {
            String name = seg[1].trim();
            return name.length() > 120 ? name.substring(0, 120) : name;
        }
        return base;
    }

    /** Человекочитаемое имя: первая текстовая строка ячейки реквизитов (без счетов/БИКов). */
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

    /** Убирает техническую префиксную часть назначения («Дата оплаты ..., условия банка ...»). */
    private String stripBankPrefix(String purpose) {
        if (purpose == null) {
            return "";
        }
        String p = purpose.replaceFirst("(?i)^Дата оплаты\\s*[\\d/:. ]+,?\\s*", "");
        p = p.replaceFirst("(?i)^сумма\\s*[\\d.,-]+\\s*,?\\s*", "");
        p = p.replaceFirst("(?i)^в т\\.?ч\\.?условия банка\\s*[\\d.,-]+\\.?\\s*", "");
        p = p.replaceFirst("(?i)^Назначение платежа:?\\s*", "");
        return p.trim();
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

    private String cell(List<String[]> grid, int rowIdx, int colIdx) {
        if (rowIdx < 0 || rowIdx >= grid.size() || colIdx < 0) {
            return null;
        }
        String[] row = grid.get(rowIdx);
        if (row == null || colIdx >= row.length) {
            return null;
        }
        String v = row[colIdx];
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String flatten(List<String[]> grid, int maxRows) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < Math.min(grid.size(), maxRows); r++) {
            for (String v : grid.get(r)) {
                if (v != null) {
                    sb.append(v).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private String trimToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** SAX-коллектор: собирает лист в «сетку» уже отформатированных текстовых значений. */
    private static final class GridCollector
            implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final List<String[]> grid;
        private String[] currentRow = new String[0];

        GridCollector(List<String[]> grid) {
            this.grid = grid;
        }

        @Override
        public void startRow(int rowNum) {
            while (grid.size() < rowNum) {
                grid.add(new String[0]);
            }
            currentRow = new String[0];
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

        static int cellRefToColumn(String ref) {
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

    // ==================== импорт ====================

    @org.springframework.transaction.annotation.Transactional
    public ImportResult importRows(List<ImportRow> rows) {
        int payments = 0;
        int charges = 0;
        long before = saldoRepository.count();
        List<String> problems = new ArrayList<>();
        Set<Integer> touchedApartments = new LinkedHashSet<>();

        for (ImportRow row : rows) {
            // Проблема считается актуальной только по фактическим данным строки:
            // если пользователь вписал квартиру/дату/сумму в таблице предпросмотра,
            // устаревшая метка «проблемы» игнорируется и строка импортируется.
            String effectiveProblem = recomputeProblem(row);
            if (effectiveProblem != null) {
                problems.add("строка №" + row.orderNo() + ": " + effectiveProblem);
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

    /**
     * Пересчитывает проблему строки по её фактическим данным. Нужно потому, что
     * метка problem() ставится на этапе разбора файла; после ручных правок в
     * предпросмотре (например, пользователь вписал номер квартиры) старая метка
     * может остаться устаревшей и ошибочно блокировать импорт.
     */
    public static String recomputeProblem(ImportRow row) {
        if (row.date() == null) {
            return PROBLEM_DATE;
        }
        if (row.amount() == null || row.amount().signum() <= 0) {
            return PROBLEM_AMOUNT;
        }
        if (row.typeKnown() == null) {
            return PROBLEM_TYPE;
        }
        if (row.apartment() == null || row.apartment() <= 0) {
            return PROBLEM_APARTMENT;
        }
        return null;
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
                row.apartment(), YearMonth.from(row.date()).atDay(1), row.amount());
    }
}
