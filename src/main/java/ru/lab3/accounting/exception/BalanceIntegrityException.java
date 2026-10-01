package ru.lab3.accounting.exception;

public class BalanceIntegrityException extends RuntimeException {
    public BalanceIntegrityException(String message) {
        super(message);
    }
}
