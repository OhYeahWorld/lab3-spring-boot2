import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import ru.lab3.accounting.controller.ImportController;
import ru.lab3.accounting.service.ParsedRowsStore;
import ru.lab3.accounting.service.StatementImportService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Проверяет сценарий пользователя:
 * 1) загрузить файл -> таблица (часть квартир пустая, часть типов «—»)
 * 2) пользователь вписывает квартиры и выбирает тип, жмёт «Сохранить правки»
 * 3) правки НЕ должны потеряться
 * 4) поля, не отправленные формой, не восстанавливаются из сохранённого разбора.
 */
public class EditRoundTripTest {

    record Cell(String orderNo, String date, String time, String amount, String party,
                String purpose, String incoming, String apartment, String problem) {}

    static List<Cell> cells(List<?> rows) throws Exception {
        var out = new java.util.ArrayList<Cell>();
        for (Object o : rows) {
            Class<?> c = o.getClass();
            out.add(new Cell(
                    s(c, o, "getOrderNo"), s(c, o, "getDate"), s(c, o, "getTime"), s(c, o, "getAmount"),
                    s(c, o, "getParty"), s(c, o, "getPurpose"), s(c, o, "getIncoming"),
                    s(c, o, "getApartment"), s(c, o, "getProblem")));
        }
        return out;
    }

    static String s(Class<?> c, Object o, String g) throws Exception {
        Object v = c.getMethod(g).invoke(o);
        return v == null ? "" : String.valueOf(v);
    }

    /** Имитация полного или обрезанного POST-запроса браузера. */
    static MockHttpServletRequest formRequest(MockHttpSession session, List<Cell> cells, boolean truncateSome) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/data/import/edit");
        req.setSession(session);
        int idx = 0;
        for (Cell c : cells) {
            req.setParameter("rows[" + idx + "].orderNo", c.orderNo());
            req.setParameter("rows[" + idx + "].date", c.date());
            req.setParameter("rows[" + idx + "].time", c.time());
            req.setParameter("rows[" + idx + "].amount", c.amount());
            req.setParameter("rows[" + idx + "].party", c.party());
            req.setParameter("rows[" + idx + "].purpose", c.purpose());
            // имитация обрезанной формы: у каждой третьей строки поля не дошли
            boolean cut = truncateSome && idx % 3 == 2;
            if (!cut) {
                req.setParameter("rows[" + idx + "].incoming", c.incoming());
                req.setParameter("rows[" + idx + "].apartment", c.apartment());
            }
            req.setParameter("rows[" + idx + "].problem", c.problem());
            idx++;
        }
        req.setParameter("fileName", "выписка.xlsx");
        return req;
    }

    public static void main(String[] args) throws Exception {
        StatementImportService svc = new StatementImportService(null, null, null, null);
        ParsedRowsStore store = new ParsedRowsStore();
        ImportController controller = new ImportController(svc, null, store);

        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        MockMultipartFile file = new MockMultipartFile("file", "выписка.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        MockHttpSession session = new MockHttpSession();
        Model m1 = new ConcurrentModel();
        controller.parse(file, session, m1);
        List<?> parsed = (List<?>) m1.getAttribute("parsedRows");
        System.out.println("parse -> rows=" + parsed.size());

        // Пользователь вписывает квартиры там, где пусто, и выбирает тип там, где «—»
        List<Cell> edited = new java.util.ArrayList<>();
        int filledApt = 0, filledType = 0;
        for (Cell c : cells(parsed)) {
            String apt = c.apartment(), inc = c.incoming();
            if (apt.isEmpty()) { apt = "164"; filledApt++; }
            if (inc.isEmpty()) { inc = "0"; filledType++; }
            edited.add(new Cell(c.orderNo(), c.date(), c.time(), c.amount(), c.party(), c.purpose(), inc, apt, c.problem()));
        }
        System.out.println("user filled apartments: " + filledApt + ", types: " + filledType);

        // «Сохранить правки» — полная форма
        Model m2 = new ConcurrentModel();
        controller.edit(formRequest(session, edited, false), session, "выписка.xlsx", m2);
        List<Cell> after = cells((List<?>) m2.getAttribute("parsedRows"));
        for (int i = 0; i < after.size(); i++) {
            Cell want = edited.get(i), got = after.get(i);
            if (!want.apartment().equals(got.apartment())) {
                throw new AssertionError("КВАРТИРА ПОТЕРЯНА в строке " + i + ": было " + want.apartment() + ", стало [" + got.apartment() + "]");
            }
            if (!want.incoming().equals(got.incoming())) {
                throw new AssertionError("ТИП ПОТЕРЯН в строке " + i + ": было " + want.incoming() + ", стало [" + got.incoming() + "]");
            }
        }
        System.out.println("edit with full form -> all edits preserved (" + after.size() + " rows)");

        // Поля, отсутствующие в POST, не должны подтягиваться из исходной строки.
        Model m3 = new ConcurrentModel();
        controller.edit(formRequest(session, edited, true), session, "выписка.xlsx", m3);
        List<Cell> after2 = cells((List<?>) m3.getAttribute("parsedRows"));
        for (int i = 0; i < after2.size(); i++) {
            Cell got = after2.get(i);
            if (i % 3 == 2 && (!got.apartment().isEmpty() || !got.incoming().isEmpty())) {
                throw new AssertionError("ОБРЕЗАННАЯ ФОРМА: отсутствующие поля восстановлены из сессии в строке "
                        + i + ": apartment=[" + got.apartment() + "], incoming=[" + got.incoming() + "]");
            }
        }
        System.out.println("edit with truncated form -> absent fields remain empty (" + after2.size() + " rows)");

        // Полностью пустая форма не должна восстанавливаться из сохранённого разбора.
        MockHttpServletRequest emptyReq = new MockHttpServletRequest("POST", "/data/import/edit");
        emptyReq.setSession(session);
        Model m4 = new ConcurrentModel();
        controller.edit(emptyReq, session, "выписка.xlsx", m4);
        List<Cell> after3 = cells((List<?>) m4.getAttribute("parsedRows"));
        if (!after3.isEmpty() || m4.getAttribute("error") == null) {
            throw new AssertionError("пустая форма: ожидалась ошибка без восстановления строк из сессии");
        }
        System.out.println("empty form -> rows are not recovered from session");
        System.out.println("ROUNDTRIP OK");
    }
}
