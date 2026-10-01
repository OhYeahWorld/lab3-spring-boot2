package ru.lab3.accounting.controller;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.lab3.accounting.dto.ChargeForm;
import ru.lab3.accounting.dto.PaymentForm;
import ru.lab3.accounting.dto.SaldoForm;
import ru.lab3.accounting.exception.BalanceIntegrityException;
import ru.lab3.accounting.exception.DuplicateRecordException;
import ru.lab3.accounting.model.Charge;
import ru.lab3.accounting.model.Payment;
import ru.lab3.accounting.service.LedgerService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

@Controller
@RequestMapping("/data")
public class DataController {
    private final LedgerService ledgerService;
    private final Clock clock;

    public DataController(LedgerService ledgerService, Clock clock) {
        this.ledgerService = ledgerService;
        this.clock = clock;
    }

    @GetMapping("/saldo")
    public String saldo(Model model) {
        if (!model.containsAttribute("saldoForm")) {
            SaldoForm form = new SaldoForm();
            form.setPeriod(YearMonth.now(clock).toString());
            form.setOpeningBalance(BigDecimal.ZERO.setScale(2));
            model.addAttribute("saldoForm", form);
        }
        model.addAttribute("rows", ledgerService.listSaldo());
        return "data/saldo";
    }

    @PostMapping("/saldo")
    public String createSaldo(@Valid @ModelAttribute("saldoForm") SaldoForm form,
                              BindingResult bindingResult,
                              RedirectAttributes redirectAttributes,
                              Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("rows", ledgerService.listSaldo());
            return "data/saldo";
        }
        try {
            ledgerService.createSaldo(form);
            redirectAttributes.addFlashAttribute("success", "Период сальдо сохранён. Время операции записано автоматически.");
        } catch (DuplicateRecordException | BalanceIntegrityException | IllegalArgumentException ex) {
            model.addAttribute("rows", ledgerService.listSaldo());
            model.addAttribute("error", ex.getMessage());
            return "data/saldo";
        }
        return "redirect:/data/saldo";
    }

    @GetMapping("/charges")
    public String charges(@RequestParam(required = false) Long edit, Model model) {
        ChargeForm form = new ChargeForm();
        if (edit != null) {
            Charge c = ledgerService.listCharges(null).stream()
                    .filter(x -> x.getId().equals(edit))
                    .findFirst()
                    .orElse(null);
            if (c != null) {
                form.setId(c.getId());
                form.setApartmentNumber(c.getApartmentNumber());
                form.setPeriod(c.getPeriod().toString().substring(0, 7));
                form.setAmount(c.getAmount());
                form.setDescription(c.getDescription());
            }
        }
        if (form.getPeriod() == null) {
            form.setPeriod(YearMonth.now(clock).toString());
        }
        if (!model.containsAttribute("chargeForm")) {
            model.addAttribute("chargeForm", form);
        }
        model.addAttribute("rows", ledgerService.listCharges(null));
        return "data/charges";
    }

    @PostMapping("/charges")
    public String saveCharge(@Valid @ModelAttribute("chargeForm") ChargeForm form,
                             BindingResult bindingResult,
                             RedirectAttributes redirectAttributes,
                             Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("rows", ledgerService.listCharges(null));
            return "data/charges";
        }
        try {
            if (form.getId() == null) {
                ledgerService.createCharge(form);
                redirectAttributes.addFlashAttribute("success", "Начисление добавлено.");
            } else {
                ledgerService.updateCharge(form.getId(), form);
                redirectAttributes.addFlashAttribute("success", "Начисление изменено; последующие сальдо пересчитаны.");
            }
        } catch (DuplicateRecordException | BalanceIntegrityException | IllegalArgumentException ex) {
            model.addAttribute("rows", ledgerService.listCharges(null));
            model.addAttribute("error", ex.getMessage());
            return "data/charges";
        }
        return "redirect:/data/charges";
    }

    @PostMapping("/charges/{id}/delete")
    public String deleteCharge(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            ledgerService.deleteCharge(id);
            redirectAttributes.addFlashAttribute("success", "Начисление удалено; последующие сальдо пересчитаны.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/data/charges";
    }

    @GetMapping("/payments")
    public String payments(@RequestParam(required = false) Long edit, Model model) {
        PaymentForm form = new PaymentForm();
        if (edit != null) {
            Payment p = ledgerService.listPayments(null).stream()
                    .filter(x -> x.getId().equals(edit))
                    .findFirst()
                    .orElse(null);
            if (p != null) {
                form.setId(p.getId());
                form.setApartmentNumber(p.getApartmentNumber());
                form.setPeriod(p.getPeriod().toString().substring(0, 7));
                form.setPaymentDate(p.getPaymentDate().toString());
                form.setAmount(p.getAmount());
                form.setPaymentReference(p.getPaymentReference());
            }
        }
        if (form.getPeriod() == null) {
            form.setPeriod(YearMonth.now(clock).toString());
        }
        if (form.getPaymentDate() == null) {
            form.setPaymentDate(LocalDate.now(clock).toString());
        }
        if (!model.containsAttribute("paymentForm")) {
            model.addAttribute("paymentForm", form);
        }
        model.addAttribute("rows", ledgerService.listPayments(null));
        return "data/payments";
    }

    @PostMapping("/payments")
    public String savePayment(@Valid @ModelAttribute("paymentForm") PaymentForm form,
                              BindingResult bindingResult,
                              RedirectAttributes redirectAttributes,
                              Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("rows", ledgerService.listPayments(null));
            return "data/payments";
        }
        try {
            if (form.getId() == null) {
                ledgerService.createPayment(form);
                redirectAttributes.addFlashAttribute("success", "Платёж добавлен.");
            } else {
                ledgerService.updatePayment(form.getId(), form);
                redirectAttributes.addFlashAttribute("success", "Платёж изменён; последующие сальдо пересчитаны.");
            }
        } catch (DuplicateRecordException | BalanceIntegrityException | IllegalArgumentException ex) {
            model.addAttribute("rows", ledgerService.listPayments(null));
            model.addAttribute("error", ex.getMessage());
            return "data/payments";
        }
        return "redirect:/data/payments";
    }

    @PostMapping("/payments/{id}/delete")
    public String deletePayment(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            ledgerService.deletePayment(id);
            redirectAttributes.addFlashAttribute("success", "Платёж удалён; последующие сальдо пересчитаны.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/data/payments";
    }
}
