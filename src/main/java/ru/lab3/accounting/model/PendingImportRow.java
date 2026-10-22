package ru.lab3.accounting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import ru.lab3.accounting.service.StatementImportService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "pending_import_rows")
public class PendingImportRow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_file", nullable = false, columnDefinition = "TEXT")
    private String sourceFile;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    @Column(name = "operation_date")
    private LocalDate date;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "amount", precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "party", nullable = false, columnDefinition = "TEXT")
    private String party = "";

    @Column(name = "purpose", nullable = false, columnDefinition = "TEXT")
    private String purpose = "";

    @Column(name = "type_known")
    private Boolean typeKnown;

    @Column(name = "apartment_number")
    private Integer apartmentNumber;

    @Column(name = "problem", nullable = false, columnDefinition = "TEXT")
    private String problem = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PendingImportRow() {
    }

    public static PendingImportRow from(String sourceFile, StatementImportService.ImportRow row) {
        PendingImportRow pending = new PendingImportRow();
        pending.sourceFile = sourceFile == null ? "" : sourceFile;
        pending.apply(row);
        return pending;
    }

    public void apply(StatementImportService.ImportRow row) {
        orderNo = row.orderNo();
        date = row.date();
        executedAt = row.executedAt();
        amount = row.amount();
        party = row.party() == null ? "" : row.party();
        purpose = row.purpose() == null ? "" : row.purpose();
        typeKnown = row.typeKnown();
        apartmentNumber = row.apartment();
        problem = StatementImportService.recomputeProblem(row);
    }

    public StatementImportService.ImportRow toImportRow() {
        return new StatementImportService.ImportRow(orderNo, date, executedAt, amount, party, purpose,
                typeKnown, apartmentNumber, problem);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSourceFile() { return sourceFile; }
    public Integer getOrderNo() { return orderNo; }
    public LocalDate getDate() { return date; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public BigDecimal getAmount() { return amount; }
    public String getParty() { return party; }
    public String getPurpose() { return purpose; }
    public Boolean getTypeKnown() { return typeKnown; }
    public Integer getApartmentNumber() { return apartmentNumber; }
    public String getProblem() { return problem; }
    public Instant getCreatedAt() { return createdAt; }
    public void setProblem(String problem) { this.problem = problem == null ? "" : problem; }
}
