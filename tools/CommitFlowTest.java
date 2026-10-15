import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.DataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import ru.lab3.accounting.controller.ImportController;
import ru.lab3.accounting.repository.ChargeRepository;
import ru.lab3.accounting.repository.PaymentRepository;
import ru.lab3.accounting.repository.SaldoRepository;
import ru.lab3.accounting.service.LedgerService;
import ru.lab3.accounting.service.ParsedRowsStore;
import ru.lab3.accounting.service.StatementImportService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CommitFlowTest {
    static Class<?> c;
    static int created = 0;

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

        for (Object o : parsed) {
            if (str(o, "getProblem").contains("квартиры")) {
                c.getMethod("setApartment", String.class).invoke(o, "123");
            }
        }

        // «Сохранить правки» — как пользователь
        MockHttpServletRequest req = buildRequest(parsed, session);
        Model m2 = new ConcurrentModel();
        controller.edit(req, session, "v.xlsx", m2);
        List<?> safe = (List<?>) m2.getAttribute("parsedRows");

        // Пользователь заново отправляет форму со СТРАНИЦЫ ПОСЛЕ сохранения (значения из m2!)
        MockHttpServletRequest req2 = buildRequest(safe, session);

        // Подставной сервис с «памятью», чтобы посчитать импортированные строки
        LedgerService fakeLedger = fakeLedger();
        StatementImportService realSvc = new StatementImportService(fakeLedger,
                proxy(ChargeRepository.class), proxy(PaymentRepository.class), proxy(SaldoRepository.class));
        ImportController controller2 = new ImportController(realSvc, fakeLedger, store);

        RedirectAttributes ra = new RedirectAttributesModelMap(new DataBinder(new Object()));
        Model m3 = new ConcurrentModel();
        controller2.commit(req2, session, m3, ra);
        System.out.println("success flash: " + ra.getFlashAttributes().get("success"));
        Object errFlash = ra.getFlashAttributes().get("error");
        System.out.println("error flash: " + errFlash);
        System.out.println("created records: " + created);
        if (created >= 43 || (errFlash == null && created > 0)) System.out.println("COMMIT FLOW OK");
        else { System.out.println("COMMIT FLOW FAILED"); System.exit(1); }
    }

    static MockHttpServletRequest buildRequest(List<?> rows, MockHttpSession session) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/data/import/edit");
        req.setSession(session);
        int idx = 0;
        for (Object o : rows) {
            for (String f : new String[]{"orderNo","date","time","amount","party","purpose","incoming","apartment","problem"}) {
                req.setParameter("rows[" + idx + "]." + f, str(o, "get" + Character.toUpperCase(f.charAt(0)) + f.substring(1)));
            }
            idx++;
        }
        req.setParameter("fileName", "v.xlsx");
        return req;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> iface) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                (p, m, a) -> {
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    static LedgerService fakeLedger() {
        SaldoRepository saldoRepo = proxy(SaldoRepository.class);
        ChargeRepository chargeRepo = proxy(ChargeRepository.class);
        PaymentRepository paymentRepo = proxy(PaymentRepository.class);
        return new LedgerService(saldoRepo, chargeRepo, paymentRepo) {
            @Override
            public ru.lab3.accounting.model.Payment createPayment(ru.lab3.accounting.dto.PaymentForm form) {
                created++;
                return null;
            }
            @Override
            public ru.lab3.accounting.model.Charge createCharge(ru.lab3.accounting.dto.ChargeForm form) {
                created++;
                return null;
            }
            @Override
            public void recalculateAll(java.util.Collection<Integer> apartments) {
                // no-op
            }
        };
    }

    static String str(Object o, String g) throws Exception {
        Object v = c.getMethod(g).invoke(o);
        return v == null ? "" : String.valueOf(v);
    }
}
