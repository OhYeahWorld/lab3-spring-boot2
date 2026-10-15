package ru.lab3.accounting.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.lab3.accounting.service.LedgerService;
import ru.lab3.accounting.service.StatementImportService;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Загрузка банковской выписки (Excel): предпросмотр, редактирование распознанных
 * строк (квартира, сумма, тип операции) и импорт в базу. Разобранный список
 * хранится в сессии, поэтому правки можно вносить сколько угодно раз до импорта,
 * а затем корректировать уже внесённые записи на страницах «Платежи»/«Начисления».
 */
@Controller
@RequestMapping("/data/import")
public class ImportController {

    private static final String SESSION_ROWS = "importParsedRows";
    private static final String SESSION_FILE = "importFileName";

    private final StatementImportService importService;

    public ImportController(StatementImportService importService) {
        this.importService = importService;
    }

    @GetMapping
    public String page(Model model, HttpSession session) {
        @SuppressWarnings("unchecked")
        List<StatementImportService.ImportRow> rows =
                (List<StatementImportService.ImportRow>) session.getAttribute(SESSION_ROWS);
        model.addAttribute("parsedRows", rows == null ? List.of() : rows);
        model.addAttribute("fileName", session.getAttribute(SESSION_FILE));
        return "data/import";
    }

    @PostMapping("/preview")
    public String preview(@RequestParam("file") MultipartFile file,
                          Model model,
                          HttpSession session,
                          RedirectAttributes redirectAttributes) {
        if (file == null || file.isEmpty()) {
            model.addAttribute("error", "Выберите файл выписки (.xlsx).");
            model.addAttribute("parsedRows", List.of());
            return "data/import";
        }
        try {
            List<StatementImportService.ImportRow> rows = importService.parse(file);
            long broken = rows.stream().filter(r -> r.problem() != null).count();
            StringBuilder notice = new StringBuilder("Файл разобран: операций — ").append(rows.size())
                    .append(". Проверьте список: при необходимости исправьте квартиру, сумму или тип операции "
                            + "прямо в таблице и нажмите «Применить изменения».");
            if (broken > 0) {
                notice.append(" Строк с проблемами: ").append(broken)
                        .append(" — они будут пропущены при импорте.");
            }
            session.setAttribute(SESSION_ROWS, rows);
            session.setAttribute(SESSION_FILE, safeName(file.getOriginalFilename()));
            model.addAttribute("notice", notice.toString());
            model.addAttribute("fileName", safeName(file.getOriginalFilename()));
            model.addAttribute("parsedRows", rows);
        } catch (IOException | IllegalArgumentException ex) {
            // Ошибку показываем сразу и НЕ сохраняем пустой список: импорт без данных невозможен.
            session.removeAttribute(SESSION_ROWS);
            session.removeAttribute(SESSION_FILE);
            model.addAttribute("error", "Не удалось разобрать файл: " + messageOf(ex));
            model.addAttribute("parsedRows", List.of());
        }
        return "data/import";
    }

    /** Применение правок из таблицы предпросмотра без записи в базу. */
    @PostMapping("/apply")
    public String apply(@RequestParam(value = "apartment", required = false) List<String> apartments,
                        @RequestParam(value = "amount", required = false) List<String> amounts,
                        @RequestParam(value = "incoming", required = false) List<String> incomingFlags,
                        Model model,
                        HttpSession session) {
        List<StatementImportService.ImportRow> stored = currentRows(session, model);
        if (stored == null) return "data/import";
        List<StatementImportService.ImportRow> edited = applyEdits(stored, apartments, amounts, incomingFlags);
        session.setAttribute(SESSION_ROWS, edited);
        model.addAttribute("notice", "Изменения применены к списку. Нажмите «Импортировать», чтобы внести их в базу.");
        model.addAttribute("fileName", session.getAttribute(SESSION_FILE));
        model.addAttribute("parsedRows", edited);
        return "data/import";
    }

    @PostMapping("/run")
    public String run(@RequestParam(value = "apartment", required = false) List<String> apartments,
                      @RequestParam(value = "amount", required = false) List<String> amounts,
                      @RequestParam(value = "incoming", required = false) List<String> incomingFlags,
                      HttpSession session,
                      RedirectAttributes redirectAttributes) {
        @SuppressWarnings("unchecked")
        List<StatementImportService.ImportRow> stored =
                (List<StatementImportService.ImportRow>) session.getAttribute(SESSION_ROWS);
        if (stored == null || stored.isEmpty()) {
            redirectAttributes.addFlashAttribute("error",
                    "Список операций пуст или устарел. Загрузите файл выписки заново.");
            return "redirect:/data/import";
        }
        List<StatementImportService.ImportRow> edited = applyEdits(stored, apartments, amounts, incomingFlags);
        long validCount = edited.stream().filter(r -> r.problem() == null).count();
        if (validCount == 0) {
            redirectAttributes.addFlashAttribute("error",
                    "Нет ни одной корректной операции для импорта — исправьте строки в предпросмотре.");
            return "redirect:/data/import";
        }
        try {
            StatementImportService.ImportResult result = importService.importRows(edited);
            int imported = result.paymentsCreated() + result.chargesCreated();
            if (imported == 0) {
                redirectAttributes.addFlashAttribute("error",
                        "Не удалось импортировать ни одну операцию. Проблемы: " + summarize(result.problems()));
                return "redirect:/data/import";
            }
            StringBuilder message = new StringBuilder("Импорт завершён: добавлено платежей — ")
                    .append(result.paymentsCreated())
                    .append(", начислений — ").append(result.chargesCreated())
                    .append(", создано периодов сальдо — ").append(result.saldoCreated())
                    .append(". Затронуто квартир: ").append(result.apartments().size())
                    .append(". Данные видны на страницах «Платежи» и «Начисления».");
            if (!result.problems().isEmpty()) {
                message.append(" Пропущено строк с проблемами — ").append(result.problems().size())
                        .append(": ").append(summarize(result.problems()));
            }
            session.removeAttribute(SESSION_ROWS);
            session.removeAttribute(SESSION_FILE);
            redirectAttributes.addFlashAttribute("success", message.toString());
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute("error", "Импорт не выполнен: " + messageOf(ex));
        }
        return "redirect:/data/import";
    }

    @PostMapping("/clear")
    public String clear(HttpSession session, RedirectAttributes redirectAttributes) {
        session.removeAttribute(SESSION_ROWS);
        session.removeAttribute(SESSION_FILE);
        redirectAttributes.addFlashAttribute("notice", "Список очищен.");
        return "redirect:/data/import";
    }

    // ================= helpers =================

    private List<StatementImportService.ImportRow> currentRows(HttpSession session, Model model) {
        @SuppressWarnings("unchecked")
        List<StatementImportService.ImportRow> stored =
                (List<StatementImportService.ImportRow>) session.getAttribute(SESSION_ROWS);
        if (stored == null || stored.isEmpty()) {
            model.addAttribute("error", "Список операций пуст. Сначала загрузите файл выписки.");
            model.addAttribute("parsedRows", List.of());
            return null;
        }
        return stored;
    }

    /** Применяет поля формы (порядок полей совпадает с порядком строк в списке) к сохранённым строкам. */
    private List<StatementImportService.ImportRow> applyEdits(
            List<StatementImportService.ImportRow> stored,
            List<String> apartments, List<String> amounts, List<String> incomingFlags) {
        List<StatementImportService.ImportRow> result = new ArrayList<>(stored.size());
        for (int i = 0; i < stored.size(); i++) {
            StatementImportService.ImportRow row = stored.get(i);
            Integer apartment = parseInteger(at(apartments, i));
            BigDecimal amount = parseDecimal(at(amounts, i));
            boolean incoming = "1".equals(at(incomingFlags, i));

            if (apartment != null && apartment > 0) {
                row = row.withApartment(apartment);
            }
            if (amount != null && amount.signum() > 0) {
                row = row.withAmount(amount);
            }
            // Тип (поступление/списание) можно переключить только у строки с корректными датой и суммой.
            if (row.date() != null && row.amount() != null && row.amount().signum() > 0
                    && row.incoming() != incoming) {
                row = new StatementImportService.ImportRow(row.orderNo(), row.date(), row.amount(),
                        row.party(), row.purpose(), incoming, row.apartment(), null);
            }
            result.add(row);
        }
        return result;
    }

    private static String at(List<String> list, int index) {
        return list == null || index >= list.size() ? null : list.get(index);
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LedgerService.parseAmountText(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) return "выписка.xlsx";
        // Имя файла приходит от клиента — вырезаем пути, чтобы не «протащить» их в страницу.
        String base = name.replace('\\', '/');
        return base.substring(base.lastIndexOf('/') + 1);
    }

    private static String summarize(List<String> problems) {
        if (problems.isEmpty()) return "";
        String head = problems.subList(0, Math.min(5, problems.size())).stream()
                .collect(java.util.stream.Collectors.joining("; "));
        return problems.size() > 5 ? head + "; … всего " + problems.size() : head;
    }

    private static String messageOf(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current && current.getMessage() == null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
