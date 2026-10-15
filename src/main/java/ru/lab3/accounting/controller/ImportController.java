package ru.lab3.accounting.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.lab3.accounting.service.StatementImportService;

import java.io.IOException;
import java.util.List;

/**
 * Загрузка банковской выписки (Excel) на страницу /data/import.
 * Сначала показывается предпросмотр разобранных строк, затем — подтверждение импорта.
 */
@Controller
public class ImportController {

    private final StatementImportService importService;

    public ImportController(StatementImportService importService) {
        this.importService = importService;
    }

    @GetMapping("/data/import")
    public String page(Model model) {
        if (!model.containsAttribute("parsedRows")) {
            model.addAttribute("parsedRows", List.of());
        }
        return "data/import";
    }

    private static final String SESSION_FILE = "statementFile";

    @PostMapping("/data/import/preview")
    public String preview(@RequestParam("file") MultipartFile file,
                          HttpSession session,
                          RedirectAttributes redirectAttributes,
                          Model model) {
        try {
            List<StatementImportService.ImportRow> rows = validateAndParse(file);
            session.setAttribute(SESSION_FILE, new StoredUpload(file.getOriginalFilename(), file.getBytes()));
            model.addAttribute("parsedRows", rows);
            model.addAttribute("fileName", file.getOriginalFilename());
            long bad = rows.stream().filter(r -> r.problem() != null).count();
            if (bad > 0) {
                model.addAttribute("notice",
                        "Разобрано операций: " + rows.size() + ", из них с проблемами: " + bad
                                + ". Проблемные строки при импорте будут пропущены.");
            } else {
                model.addAttribute("notice",
                        "Разобрано операций: " + rows.size() + ". Проверьте список и нажмите «Импортировать».");
            }
            return "data/import";
        } catch (IllegalArgumentException | IOException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
            return "redirect:/data/import";
        }
    }

    @PostMapping("/data/import/run")
    public String run(HttpSession session, RedirectAttributes redirectAttributes) {
        Object stored = session.getAttribute(SESSION_FILE);
        session.removeAttribute(SESSION_FILE);
        if (!(stored instanceof StoredUpload upload)) {
            redirectAttributes.addFlashAttribute("error",
                    "Файл выписки устарел — выберите его заново и сначала выполните предпросмотр.");
            return "redirect:/data/import";
        }
        try {
            MultipartFile file = upload.toMultipart();
            validateAndParse(file); // повторная проверка того же файла
            StatementImportService.ImportResult result = importService.importWorkbook(file);
            StringBuilder message = new StringBuilder("Импорт завершён: платежей — ")
                    .append(result.paymentsCreated())
                    .append(", начислений — ").append(result.chargesCreated())
                    .append(", новых периодов сальдо — ").append(result.saldoCreated())
                    .append(". Квартиры: ")
                    .append(result.apartments().isEmpty() ? "—" : result.apartments());
            if (!result.problems().isEmpty()) {
                message.append(" Пропущено строк с проблемами: ").append(result.problems().size()).append(" — ")
                        .append(String.join("; ", result.problems()));
            }
            redirectAttributes.addFlashAttribute("success", message.toString());
        } catch (IllegalArgumentException | IOException ex) {
            redirectAttributes.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/data/import";
    }

    /** Файл выписки хранится между шагами «предпросмотр → импорт» в сессии. */
    private record StoredUpload(String filename, byte[] content) {
        MultipartFile toMultipart() {
            return new org.springframework.web.multipart.MultipartFile() {
                @Override public String getName() { return "file"; }
                @Override public String getOriginalFilename() { return filename; }
                @Override public String getContentType() { return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
                @Override public boolean isEmpty() { return content.length == 0; }
                @Override public long getSize() { return content.length; }
                @Override public byte[] getBytes() { return content; }
                @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }
                @Override public void transferTo(java.io.File dest) throws java.io.IOException {
                    java.nio.file.Files.write(dest.toPath(), content);
                }
            };
        }
    }

    private List<StatementImportService.ImportRow> validateAndParse(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Выберите файл выписки Excel (.xlsx).");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".xlsx")) {
            throw new IllegalArgumentException("Поддерживается только формат .xlsx. "
                    + "Сохраните выписку в Excel 2007+ и повторите загрузку.");
        }
        return importService.parse(file);
    }
}
