import ru.lab3.accounting.repository.PendingImportRowRepository;
import ru.lab3.accounting.service.PendingImportService;

import java.lang.reflect.Proxy;
import java.util.List;

final class ImportTestSupport {
    private ImportTestSupport() {
    }

    static PendingImportService pendingImportService() {
        PendingImportRowRepository repository = (PendingImportRowRepository) Proxy.newProxyInstance(
                PendingImportRowRepository.class.getClassLoader(),
                new Class<?>[]{PendingImportRowRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("saveAll")) {
                        return args[0];
                    }
                    if (method.getReturnType() == List.class) {
                        return List.of();
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
        return new PendingImportService(repository);
    }
}
