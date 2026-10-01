package ru.lab3.accounting.controller;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public String handleUnexpected(Exception ex, Model model) {
        Throwable root = rootCause(ex);
        String message = root.getMessage();
        model.addAttribute("errorMessage", message == null || message.isBlank()
                ? "Неизвестная ошибка приложения."
                : message);
        return "error/error";
    }

    private Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
