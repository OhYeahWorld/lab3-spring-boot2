package ru.lab3.accounting.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lab3.accounting.model.PendingImportRow;
import ru.lab3.accounting.repository.PendingImportRowRepository;

import java.util.List;

@Service
public class PendingImportService {
    private final PendingImportRowRepository repository;

    public PendingImportService(PendingImportRowRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int saveUnresolved(String sourceFile, List<StatementImportService.ImportRow> rows) {
        List<PendingImportRow> unresolved = rows.stream()
                .filter(row -> StatementImportService.recomputeProblem(row) != null)
                .map(row -> PendingImportRow.from(sourceFile, row))
                .toList();
        repository.saveAll(unresolved);
        return unresolved.size();
    }

    @Transactional(readOnly = true)
    public List<PendingImportRow> list() {
        return repository.findAllByOrderByCreatedAtAscIdAsc();
    }

    @Transactional
    public void save(PendingImportRow row) {
        repository.save(row);
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }
}
