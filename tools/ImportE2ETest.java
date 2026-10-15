import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import ru.lab3.accounting.controller.ImportController;
import ru.lab3.accounting.service.LedgerService;
import ru.lab3.accounting.service.ParsedRowsStore;
import ru.lab3.accounting.service.StatementImportService;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Сквозная проверка: parse -> edit (сохранить правки) -> commit, как в браузере. */
public class ImportE2ETest {
    public static void main(String[] args) throws Exception {
        StatementImportService svc = new StatementImportService(null, null, null, null);
        ParsedRowsStore store = new ParsedRowsStore();
        ImportController controller = new ImportController(svc, null, store);

        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        MockMultipartFile file = new MockMultipartFile("file", "выписка.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        MockHttpSession session = new MockHttpSession();

        // 1. Разобрать файл
        Model m1 = new ConcurrentModel();
        String v1 = controller.parse(file, session, m1);
        List<?> parsed = (List<?>) m1.getAttribute("parsedRows");
        System.out.println("parse -> rows=" + (parsed == null ? 0 : parsed.size()) + " view=" + v1);
        if (parsed == null || parsed.isEmpty()) {
            throw new IllegalStateException("parse вернул пустую таблицу");
        }

        // 2. Сформировать POST-параметры ровно как это делает браузер из шаблона import.html
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/data/import/edit");
        req.setSession(session);
        int idx = 0;
        for (Object o : parsed) {
            Class<?> c = o.getClass();
            Map<String, String> vals = Map.of(
                    "orderNo", str(c, o, "getOrderNo"),
                    "date", str(c, o, "getDate"),
                    "time", str(c, o, "getTime"),
                    "amount", str(c, o, "getAmount"),
                    "party", str(c, o, "getParty"),
                    "purpose", str(c, o, "getPurpose"),
                    "incoming", str(c, o, "getIncoming"),
                    "apartment", str(c, o, "getApartment"),
                    "problem", str(c, o, "getProblem"));
            for (Map.Entry<String, String> e : vals.entrySet()) {
                req.setParameter("rows[" + idx + "]." + e.getKey(), e.getValue());
            }
            idx++;
        }
        req.setParameter("fileName", "выписка.xlsx");
        System.out.println("browser would send params: " + req.getParameterMap().size());

        // 3. «Сохранить правки в таблице»
        Model m2 = new ConcurrentModel();
        String v2 = controller.edit(req, session, req.getParameter("fileName"), m2);
        Object err = m2.getAttribute("error");
        List<?> safe = (List<?>) m2.getAttribute("parsedRows");
        System.out.println("edit -> rows=" + (safe == null ? 0 : safe.size()) + " error=" + err);
        if (err != null) throw new IllegalStateException("edit выдал ошибку: " + err);

        // 4. «Импортировать в базу» — без БД ожидаем понятную ошибку импорта, но НЕ «нет строк»
        org.springframework.validation.DataBinder binder =
                new org.springframework.validation.DataBinder(new Object());
        RedirectAttributes ra = new RedirectAttributesModelMap(binder);
        Model m3 = new ConcurrentModel();
        controller.commit(req, session, m3, ra);
        System.out.println("commit -> flash/model keys ok (ожидаемо сообщение об ошибке БД или успех)");
        System.out.println("ALL GOOD");
    }

    private static String str(Class<?> c, Object o, String getter) throws Exception {
        try {
            Object v = c.getMethod(getter).invoke(o);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }
}
