import ru.lab3.accounting.service.LedgerService;
import ru.lab3.accounting.service.StatementImportService;
import ru.lab3.accounting.repository.ChargeRepository;
import ru.lab3.accounting.repository.PaymentRepository;
import ru.lab3.accounting.repository.SaldoRepository;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Прогон реального парсера (скомпилированного сервиса) по настоящему файлу выписки. */
public class RunRealParser {
    public static void main(String[] args) throws Exception {
        ChargeRepository charges = noop(ChargeRepository.class);
        PaymentRepository payments = noop(PaymentRepository.class);
        SaldoRepository saldo = noop(SaldoRepository.class);
        LedgerService ledger = null; // parse() не трогает БД
        StatementImportService svc = new StatementImportService(ledger, charges, payments, saldo);

        byte[] content = Files.readAllBytes(Path.of(args[0]));
        MultipartFileStub file = new MultipartFileStub(content);

        long t0 = System.currentTimeMillis();
        List<StatementImportService.ImportRow> rows = svc.parse(file);
        long ms = System.currentTimeMillis() - t0;

        long problems = rows.stream().filter(r -> r.problem() != null).count();
        long incoming = rows.stream().filter(r -> r.incoming() && r.problem() == null).count();
        long outgoing = rows.stream().filter(r -> !r.incoming() && r.problem() == null).count();
        System.out.printf("RESULT total=%d ok=%d problems=%d incoming=%d outgoing=%d time=%dms%n",
                rows.size(), rows.size() - problems, problems, incoming, outgoing, ms);
        rows.stream().filter(r -> r.problem() != null).forEach(r ->
                System.out.printf("PROBLEM #%d [%s] apt=%s sum=%s purpose=%s party=%s%n",
                        r.orderNo(), r.problem(), r.apartment(), r.amount(),
                        cut(r.purpose()), cut(r.party())));
        System.out.println("--- первые 8 ---");
        rows.stream().limit(8).forEach(r -> System.out.printf("#%d %s %s sum=%s in=%b apt=%s party=%s purp=%s%s%n",
                r.orderNo(), r.date(), r.timeText(), r.amount(), r.incoming(), r.apartment(),
                cut(r.party()), cut(r.purpose()), r.problem() == null ? "" : " PROBLEM:" + r.problem()));
        System.out.println("--- последние 4 ---");
        rows.subList(Math.max(0, rows.size() - 4), rows.size()).forEach(r ->
                System.out.printf("#%d %s %s sum=%s in=%b apt=%s party=%s purp=%s%s%n",
                        r.orderNo(), r.date(), r.timeText(), r.amount(), r.incoming(), r.apartment(),
                        cut(r.party()), cut(r.purpose()), r.problem() == null ? "" : " PROBLEM:" + r.problem()));
    }

    static String cut(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /** Минимальная заглушка MultipartFile без spring-test. */
    static class MultipartFileStub implements org.springframework.web.multipart.MultipartFile {
        private final byte[] content;
        MultipartFileStub(byte[] content) { this.content = content; }
        public String getName() { return "file"; }
        public String getOriginalFilename() { return "vypiska.xlsx"; }
        public String getContentType() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
        public boolean isEmpty() { return content.length == 0; }
        public long getSize() { return content.length; }
        public byte[] getBytes() { return content; }
        public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }
        public void transferTo(java.io.File dest) throws java.io.IOException { java.nio.file.Files.write(dest.toPath(), content); }
    }

    @SuppressWarnings("unchecked")
    static <T> T noop(Class<T> iface) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class[]{iface},
                (proxy, method, mArgs) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    return null;
                });
    }
}
