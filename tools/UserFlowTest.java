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
import java.util.ArrayList;
import java.util.List;

public class UserFlowTest {
    static Class<?> c;

    public static void main(String[] args) throws Exception {
        StatementImportService svc = new StatementImportService(null, null, null, null);
        ParsedRowsStore store = new ParsedRowsStore();
        ImportController controller = new ImportController(svc, null, store);

        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        MockMultipartFile file = new MockMultipartFile("file", "v.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
        MockHttpSession session = new MockHttpSession();

        Model m1 = new ConcurrentModel();
        controller.parse(file, session, m1);
        List<?> parsed = (List<?>) m1.getAttribute("parsedRows");
        c = parsed.get(0).getClass();

        // Пользователь вписывает номера квартир в проблемные строки
        int fixed = 0;
        for (Object o : parsed) {
            String problem = str(o, "getProblem");
            if (problem.contains("квартиры")) {
                c.getMethod("setApartment", String.class).invoke(o, "123");
                fixed++;
            }
        }
        System.out.println("manually filled rows: " + fixed);

        // Браузер отправляет форму как есть
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/data/import/edit");
        req.setSession(session);
        int idx = 0;
        for (Object o : parsed) {
            send(req, idx, "orderNo", str(o, "getOrderNo"));
            send(req, idx, "date", str(o, "getDate"));
            send(req, idx, "time", str(o, "getTime"));
            send(req, idx, "amount", str(o, "getAmount"));
            send(req, idx, "party", str(o, "getParty"));
            send(req, idx, "purpose", str(o, "getPurpose"));
            send(req, idx, "incoming", str(o, "getIncoming"));
            send(req, idx, "apartment", str(o, "getApartment"));
            send(req, idx, "problem", str(o, "getProblem"));
            idx++;
        }
        req.setParameter("fileName", "v.xlsx");

        // «Сохранить правки в таблице»
        Model m2 = new ConcurrentModel();
        controller.edit(req, session, "v.xlsx", m2);
        List<?> safe = (List<?>) m2.getAttribute("parsedRows");

        int lost = 0;
        idx = 0;
        for (Object orig : parsed) {
            String want = str(orig, "getApartment");
            String got = str(safe.get(idx), "getApartment");
            if (!want.equals(got)) {
                lost++;
                System.out.println("LOST row " + (idx + 1) + ": expected '" + want + "' got '" + got
                        + "' problem='" + str(safe.get(idx), "getProblem") + "'");
            }
            idx++;
        }
        System.out.println("rows=" + safe.size() + " lostEdits=" + lost);

        // Теперь коммит с заглушкой репозиториев нельзя — проверим recomputeProblem напрямую
        int clearedProblems = 0;
        for (Object o : safe) {
            String pr = str(o, "getProblem");
            if ("123".equals(str(o, "getApartment")) && pr.isEmpty()) clearedProblems++;
        }
        System.out.println("filled rows with cleared problem: " + clearedProblems + "/" + fixed);
        if (lost == 0 && clearedProblems == fixed) System.out.println("USER FLOW OK");
        else System.out.println("USER FLOW FAILED");
    }

    static void send(MockHttpServletRequest r, int i, String f, String v) {
        r.setParameter("rows[" + i + "]." + f, v);
    }

    static String str(Object o, String g) throws Exception {
        Object v = c.getMethod(g).invoke(o);
        return v == null ? "" : String.valueOf(v);
    }
}
