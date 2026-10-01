package ru.lab3.accounting.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.lab3.accounting.service.LedgerService;

@Controller
public class HomeController {
    private final LedgerService ledgerService;

    public HomeController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("saldoCount", ledgerService.listSaldo().size());
        model.addAttribute("chargeCount", ledgerService.listCharges(null).size());
        model.addAttribute("paymentCount", ledgerService.listPayments(null).size());
        return "index";
    }
}
