package ru.lab3.accounting.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.lab3.accounting.repository.ReportRepository;
import ru.lab3.accounting.repository.report.ApartmentReportRow;
import ru.lab3.accounting.repository.report.DebtorReportRow;
import ru.lab3.accounting.repository.report.TurnoverApartmentView;
import ru.lab3.accounting.repository.report.TurnoverCell;
import ru.lab3.accounting.repository.report.TurnoverRow;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/reports")
public class ReportController {
    private final ReportRepository reportRepository;
    private final Clock clock;

    public ReportController(ReportRepository reportRepository, Clock clock) {
        this.reportRepository = reportRepository;
        this.clock = clock;
    }

    @GetMapping("/turnover")
    public String turnover(@RequestParam(defaultValue = "2017") int year, Model model) {
        List<TurnoverRow> rows = reportRepository.turnover(year);
        Map<Integer, List<TurnoverRow>> grouped = new LinkedHashMap<>();
        for (TurnoverRow row : rows) {
            grouped.computeIfAbsent(row.apartmentNumber(), key -> new ArrayList<>()).add(row);
        }

        List<TurnoverApartmentView> apartments = grouped.entrySet().stream()
                .map(entry -> {
                    List<TurnoverRow> source = entry.getValue();
                    BigDecimal opening = source.stream()
                            .map(TurnoverRow::openingBalance)
                            .filter(v -> v != null)
                            .findFirst()
                            .orElse(null);
                    BigDecimal outgoing = source.stream()
                            .map(TurnoverRow::outgoingBalance)
                            .filter(v -> v != null)
                            .findFirst()
                            .orElse(BigDecimal.ZERO.setScale(2));

                    List<TurnoverCell> months = new ArrayList<>(12);
                    for (int month = 1; month <= 12; month++) {
                        int currentMonth = month;
                        TurnoverRow r = source.stream()
                                .filter(x -> x.monthNo() == currentMonth)
                                .findFirst()
                                .orElse(null);
                        BigDecimal charges = r == null || r.charges() == null ? BigDecimal.ZERO.setScale(2) : r.charges();
                        BigDecimal payments = r == null || r.payments() == null ? BigDecimal.ZERO.setScale(2) : r.payments();
                        // В ячейке показываем входящее сальдо на начало месяца.
                        BigDecimal monthOpening = r == null ? opening : r.monthOpeningBalance();
                        boolean hasStoredData = monthOpening != null || charges.signum() != 0 || payments.signum() != 0;
                        months.add(new TurnoverCell(month, charges, payments, monthOpening,
                                r == null ? null : r.actionAt(), hasStoredData));
                    }
                    return new TurnoverApartmentView(entry.getKey(), opening, months, outgoing);
                })
                .toList();

        model.addAttribute("year", year);
        model.addAttribute("apartments", apartments);
        return "reports/turnover";
    }

    @GetMapping("/apartment")
    public String apartment(@RequestParam int apartment,
                            @RequestParam(defaultValue = "2017") int year,
                            Model model) {
        List<ApartmentReportRow> rows = reportRepository.apartment(apartment, year);
        BigDecimal totalCharges = rows.stream()
                .map(ApartmentReportRow::charges)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPayments = rows.stream()
                .map(ApartmentReportRow::payments)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal opening = rows.stream()
                .map(ApartmentReportRow::openingBalance)
                .filter(v -> v != null)
                .findFirst()
                .orElse(BigDecimal.ZERO.setScale(2));
        BigDecimal ending = rows.stream()
                .map(ApartmentReportRow::closingBalance)
                .filter(v -> v != null)
                .reduce((first, second) -> second)
                .orElse(null);
        BigDecimal previousDebtOrOverpayment = opening;
        BigDecimal payable = previousDebtOrOverpayment.add(totalCharges).subtract(totalPayments).setScale(2);

        model.addAttribute("apartment", apartment);
        model.addAttribute("year", year);
        model.addAttribute("rows", rows);
        model.addAttribute("totalCharges", totalCharges);
        model.addAttribute("totalPayments", totalPayments);
        model.addAttribute("opening", opening);
        model.addAttribute("ending", ending);
        model.addAttribute("previousDebtOrOverpayment", previousDebtOrOverpayment);
        model.addAttribute("payable", payable);
        model.addAttribute("generatedAt", clock.instant());
        return "reports/apartment";
    }

    @GetMapping("/debtors")
    public String debtors(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                          Model model) {
        LocalDate selected = asOf == null ? LocalDate.of(2017, 10, 1) : asOf;
        List<DebtorReportRow> rows = reportRepository.debtors(selected);
        model.addAttribute("asOf", selected);
        model.addAttribute("rows", rows);
        model.addAttribute("generatedAt", clock.instant());
        return "reports/debtors";
    }
}
