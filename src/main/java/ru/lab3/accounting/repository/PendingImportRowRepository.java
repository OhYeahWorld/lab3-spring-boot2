package ru.lab3.accounting.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.lab3.accounting.model.PendingImportRow;

import java.util.List;

public interface PendingImportRowRepository extends JpaRepository<PendingImportRow, Long> {
    List<PendingImportRow> findAllByOrderByCreatedAtAscIdAsc();
}
