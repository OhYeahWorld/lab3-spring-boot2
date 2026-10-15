import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ooxml.util.SAXHelper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/** Одиночная проверка нового парсера выписки (тот же алгоритм, что в StatementImportService). */
public class VerifyParser {

    record ImportRow(String orderNo, String date, double amount, boolean incoming,
                     Integer apartment, String party, String purpose, String problem) {}

    static final DataFormatter FORMATTER = new DataFormatter(Locale.forLanguageTag("ru-RU"));

    public static void main(String[] args) throws Exception {
        byte[] content = Files.readAllBytes(Path.of(args[0]));
        List<List<String[]>> sheets = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(content))) {
            XSSFReader reader = new XSSFReader(pkg);
            SharedStrings strings = reader.getSharedStringsTable();
            StylesTable styles = reader.getStylesTable();
            Iterator<InputStream> sheetStream = reader.getSheetsData();
            int idx = 0;
            while (sheetStream.hasNext()) {
                List<String[]> grid = new ArrayList<>();
                XMLReader parser = SAXHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings,
                        new GridCollector(grid), FORMATTER, false));
                try (InputStream sheet = sheetStream.next()) {
                    parser.parse(new InputSource(sheet));
                }
                sheets.add(grid);
                System.out.println("sheet " + (idx++) + ": rows=" + grid.size());
            }
        }

        List<ImportRow> best = null;
        for (List<String[]> grid : sheets) {
            List<ImportRow> tableRows = parseOperationTable(grid);
            List<ImportRow> blockRows = parseBlocks(grid);
            List<ImportRow> chosen = tableRows.size() >= blockRows.size() ? tableRows : blockRows;
            System.out.println("  table-format rows: " + tableRows.size() + ", blocks-format rows: " + blockRows.size());
            if (best == null || chosen.size() > best.size()) best = chosen;
        }

        // дедупликация точных повторов
        List<ImportRow> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ImportRow r : best) {
            String key = r.orderNo() + "|" + r.date() + "|" + r.amount() + "|" + r.incoming();
            if (r.problem() == null && !seen.add(key)) continue;
            unique.add(r);
        }

        long problems = unique.stream().filter(r -> r.problem() != null).count();
        long incoming = unique.stream().filter(r -> r.incoming() && r.problem() == null).count();
        long outgoing = unique.stream().filter(r -> !r.incoming() && r.problem() == null).count();
        long withApt = unique.stream().filter(r -> r.apartment() != null).count();
        System.out.printf("%nRESULT: total=%d ok=%d problems=%d incoming=%d outgoing=%d withApartment=%d%n",
                unique.size(), unique.size() - problems, problems, incoming, outgoing, withApt);
        unique.stream().filter(r -> r.problem() != null).limit(15)
                .forEach(r -> System.out.println("PROBLEM: №" + r.orderNo() + " — " + r.problem()));
        System.out.println("--- первые 6 строк ---");
        unique.stream().limit(6).forEach(r -> System.out.println(
                "ПП №" + r.orderNo() + " | " + r.date() + " | " + String.format("%.2f", r.amount()) + " | "
                        + (r.incoming() ? "поступление" : "списание") + " | кв=" + r.apartment()
                        + " | " + safe(r.party(), 35) + " | " + safe(r.purpose(), 70)));
        System.out.println("--- последние 3 строки ---");
        unique.subList(Math.max(0, unique.size() - 3), unique.size()).forEach(r -> System.out.println(
                "ПП №" + r.orderNo() + " | " + r.date() + " | " + String.format("%.2f", r.amount()) + " | "
                        + (r.incoming() ? "поступление" : "списание") + " | кв=" + r.apartment()
                        + " | " + safe(r.party(), 35) + " | " + safe(r.purpose(), 70)
                        + (r.problem() != null ? " | ПРОБЛЕМА: " + r.problem() : "")));
    }

    static String safe(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) : s); }

    // ================= табличный формат (лист «ВЫПИСКА ОПЕРАЦИЙ ПО ЛИЦЕВОМУ СЧЕТУ») ====

    static List<ImportRow> parseOperationTable(List<String[]> grid) {
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
                else if (t.equals("счет")) found.putIfAbsent("debitAccount", c);
                else if (t.equals("кредит")) found.putIfAbsent("creditAccount", c);
                else if (t.contains("по дебету")) found.putIfAbsent("debit", c);
                else if (t.contains("по кредиту")) found.putIfAbsent("credit", c);
                else if (t.contains("№ документа") || t.contains("no документа") || t.matches(".*документа.?")) found.putIfAbsent("doc", c);
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

        for (int i = headerRow + 1; i < grid.size(); i++) {
            String dateText = cell(grid, i, cols.get("date"));
            if (dateText == null || !isDateLike(dateText)) continue;
            LocalDateHolder date = parseDateTime(dateText);
            String doc = cell(grid, i, cols.get("doc"));
            String purpose = cell(grid, i, cols.get("purpose"));
            String debitAcc = debitAccCol == null ? null : cell(grid, i, debitAccCol);
            String creditAcc = creditAccCol == null ? null : cell(grid, i, creditAccCol);
            String debitSum = cell(grid, i, cols.getOrDefault("debit", -2));
            String creditSum = cell(grid, i, cols.getOrDefault("credit", -2));

            double amount;
            boolean incoming;
            AmountWithSign d = parseAmount(debitSum);
            AmountWithSign cr = parseAmount(creditSum);
            if (cr != null && cr.value > 0) { amount = cr.value; incoming = true; }
            else if (d != null && d.value > 0) { amount = d.value; incoming = false; }
            else { rows.add(new ImportRow(nvl(doc), date == null ? null : date.iso(), 0, false, null, null, purpose, "не найдена сумма операции")); continue; }
            if (date == null) {
                rows.add(new ImportRow(nvl(doc), null, amount, incoming, null, null, purpose, "не найдена дата операции"));
                continue;
            }

            String payerName = firstLine(debitAcc, 1);
            String payeeName = firstLine(creditAcc, 1);
            String accountTail = lastAccountTail(debitAcc, creditAcc);
            String ourAccount = findOurAccount(purpose, debitAcc, creditAcc);
            // наш счёт = тот, который встречается чаще всего как кредит-счёт (для поступлений) — упрощённо: из имени получателя
            boolean ourIsPayer = ourAccount != null && containsAccount(debitAcc, ourAccount);
            incoming = !ourIsPayer;

            String party = incoming ? cleanParty(payerName) : cleanParty(payeeName);
            Integer apartment = extractApartment(purpose);
            if (apartment == null) apartment = extractKvFromAddress(incoming ? debitAcc : creditAcc);

            String problem = null;
            if (amount <= 0) problem = "не найдена корректная сумма";
            rows.add(new ImportRow(nvl(doc), date.iso(), amount, incoming, apartment, party,
                    stripPrefix(purpose), problem));
        }
        return rows;
    }

    record AmountWithSign(double value) {}

    static String findOurAccount(String purpose, String debitAcc, String creditAcc) {
        Matcher m = Pattern.compile("\\d{20}").matcher(nvl(purpose));
        while (m.find()) {
            String acc = m.group();
            if (containsAccount(debitAcc, acc) || containsAccount(creditAcc, acc)) return acc;
        }
        return null;
    }

    static boolean containsAccount(String cellValue, String account) {
        return cellValue != null && account != null && cellValue.replace(" ", "").contains(account);
    }

    static String lastAccountTail(String debitAcc, String creditAcc) {
        String s = (nvl(debitAcc) + " " + nvl(creditAcc)).replaceAll("\\D", "");
        return s.length() >= 4 ? s.substring(s.length() - 4) : s;
    }

    static String firstLine(String cellValue, int skip) {
        if (cellValue == null) return null;
        String[] parts = cellValue.split("\\R");
        int idx = Math.min(skip, parts.length - 1);
        return parts[idx].trim();
    }

    static String cleanParty(String name) {
        if (name == null) return null;
        String s = name.trim();
        // «ПАО СБЕРБАНК//ИВАНОВ И И//...» -> «ИВАНОВ И И»
        String[] seg = s.split("//");
        if (seg.length >= 2 && !seg[1].isBlank()) return s;
        return s;
    }

    static String stripPrefix(String purpose) {
        if (purpose == null) return null;
        return purpose.replaceFirst("^Дата оплаты\\s+[^,]*,", "").trim();
    }

    static Integer extractKvFromAddress(String accountCell) {
        if (accountCell == null) return null;
        Matcher m = Pattern.compile("\\bКВ\\s*(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE).matcher(accountCell);
        Integer last = null;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v > 0 && v < 1000) last = v;
            } catch (NumberFormatException ignored) {}
        }
        return last;
    }

    // ================= блочный формат («ПЛАТЕЖНОЕ ПОРУЧЕНИЕ № …») =====================

    static final Pattern ORDER_PATTERN =
            Pattern.compile("(?:ПЛАТЕЖНОЕ\\s+ПОРУЧЕНИЕ|РЕКВИЗИТЫ)\\s*№?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    static List<ImportRow> parseBlocks(List<String[]> grid) {
        List<ImportRow> rows = new ArrayList<>();
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

            LocalDateHolder date = null;
            Double amount = null;
            boolean incoming = false, outgoing = false;
            String payer = null, payee = null, ourAccount = null, purpose = null;
            Integer apartment = null;

            // Дата операции и признаки движения — в шапке операции НАД блоком поручения.
            for (int j = Math.max(0, i - 9); j < i; j++) {
                String b = cell(grid, j, 1);
                String r = cell(grid, j, 17);
                if (b != null && r != null && isDateLike(b) && isDateLike(r)) {
                    LocalDateHolder d = parseDateTime(b);
                    if (d == null) d = parseDateTime(r);
                    if (d != null) date = d;
                    break;
                }
            }
            // Номер нашего счёта обычно указан в тексте над блоком («…(4070…)», заголовок выписки).
            for (int j = Math.max(0, i - 30); j < i; j++) {
                String any = cell(grid, j, 1);
                if (any == null) continue;
                Matcher m = Pattern.compile("(40\\d{18})").matcher(any);
                if (m.find()) { ourAccount = m.group(1); break; }
            }

            for (int j = i; j < end; j++) {
                String b = cell(grid, j, 1);
                String z = cell(grid, j, 25);
                String ad = cell(grid, j, 29);
                if (z != null) {
                    String t = z.trim();
                    if (t.equals("Сумма") && amount == null) {
                        AmountWithSign a = parseAmount(ad);
                        if (a != null) amount = a.value;
                    } else if (t.startsWith("Сч. №") && ad != null && ourAccount != null
                            && ad.replaceAll("\\s", "").equals(ourAccount)) {
                        if (labelAbove(grid, j, i, "Плательщик")) incoming = true;
                        else if (labelAbove(grid, j, i, "Получатель")) outgoing = true;
                    }
                }
                if (b != null) {
                    String t = b.trim();
                    if (t.equals("Плательщик") && payer == null) payer = partyNameAbove(grid, j, i);
                    if (t.equals("Получатель") && payee == null) payee = partyNameAbove(grid, j, i);
                    if (purpose == null && t.contains("Назначение") && !t.equalsIgnoreCase("Назначение платежа")) {
                        purpose = t.replaceFirst("(?i)^.*Назначение\\s+платежа\\s*:?\\s*", "").trim();
                        Integer apt = extractApartment(purpose);
                        if (apt != null) apartment = apt;
                    }
                }
            }

            if (date == null && purpose != null) {
                Matcher dm = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})").matcher(purpose);
                if (dm.find()) date = new LocalDateHolder(Integer.parseInt(dm.group(3)),
                        Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(1)));
            }
            if (ourAccount == null && payer != null && payee != null) {
                // если не удалось определить наш счёт — считаем поступлением всё к кооперативу
                String low = payee.toLowerCase(Locale.ROOT);
                if (low.contains("кооператив") || low.contains("дубна")) incoming = true;
                else {
                    low = payer.toLowerCase(Locale.ROOT);
                    if (low.contains("кооператив") || low.contains("дубна")) outgoing = true;
                }
            }

            String problem = null;
            if (date == null) problem = "не найдена дата операции";
            else if (amount == null || amount <= 0) problem = "не найдена корректная сумма";
            else if (incoming == outgoing) problem = "не удалось определить тип операции (поступление/списание)";

            rows.add(new ImportRow(orderNo, date == null ? null : date.iso(),
                    amount == null ? 0 : amount, incoming, apartment,
                    incoming ? payer : payee, purpose, problem));
            i = Math.max(i, end - 1);
        }
        return rows;
    }

    static boolean labelAbove(List<String[]> grid, int fromRow, int blockStart, String label) {
        for (int j = fromRow; j >= blockStart; j--) {
            String b = cell(grid, j, 1);
            if (b == null) continue;
            String t = b.trim();
            if (t.equals(label)) return true;
            if (t.equals("Плательщик") || t.equals("Получатель")) return false;
        }
        return false;
    }

    static String partyNameAbove(List<String[]> grid, int labelRow, int blockStart) {
        for (int j = labelRow - 1; j >= blockStart; j--) {
            String b = cell(grid, j, 1);
            if (b == null) continue;
            String t = b.trim();
            if (t.isEmpty()) continue;
            if (t.equals("Банк Плательщика") || t.equals("Банк Получателя") || t.equals("ИНН")
                    || t.startsWith("Сч. №") || t.equals("БИК") || t.matches("(?i)^г[.]?\\s.*")) break;
            if (t.matches("(?i)^инн\\b.*")) continue;
            return t;
        }
        return null;
    }

    // ======================= общие утилиты =======================

    record LocalDateHolder(int y, int m, int d) {
        String iso() { return String.format("%04d-%02d-%02d", y, m, d); }
    }

    static Integer extractApartment(String purpose) {
        if (purpose == null) return null;
        Matcher m = Pattern.compile("(?:кв(?:артир)?а?|л[./\\s-]?с[./\\sч]*|лиц[.\\s-]*(?:счет|счёт))[^0-9a-zа-я]{0,4}(\\d{1,4})",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(purpose);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last == null) return null;
        try {
            int v = Integer.parseInt(last);
            return (v > 0 && v < 1000) ? v : null;
        } catch (NumberFormatException e) { return null; }
    }

    static boolean isDateLike(String text) {
        return text != null && text.matches("\\d{1,4}[./-]\\d{1,2}[./-]\\d{2,4}.*");
    }

    static LocalDateHolder parseDateTime(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("^(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})").matcher(text.trim());
        if (m.find()) return new LocalDateHolder(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        m = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})").matcher(text.trim());
        if (m.find()) return new LocalDateHolder(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        return null;
    }

    static AmountWithSign parseAmount(String text) {
        if (text == null) return null;
        String s = text.replace('\u00A0', ' ').trim();
        if (s.isEmpty()) return null;
        Matcher dash = Pattern.compile("^(-?\\d+)-(\\d{2})$").matcher(s);
        if (dash.matches()) return new AmountWithSign(Double.parseDouble(dash.group(1) + "." + dash.group(2)));
        String cleaned = s.replace(" ", "").replace(",", ".");
        if (cleaned.chars().filter(c -> c == '.').count() > 1) cleaned = cleaned.replaceAll("\\.(?=\\D)", "");
        try { return new AmountWithSign(Double.parseDouble(cleaned)); } catch (NumberFormatException e) { return null; }
    }

    static String nvl(String s) { return s == null ? "" : s; }

    static String cell(List<String[]> grid, int rowIdx, int colIdx) {
        if (rowIdx < 0 || rowIdx >= grid.size() || colIdx < 0) return null;
        String[] row = grid.get(rowIdx);
        if (colIdx >= row.length) return null;
        String v = row[colIdx];
        return v == null || v.isBlank() ? null : v;
    }

    static final class GridCollector implements XSSFSheetXMLHandler.SheetContentsHandler {
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
