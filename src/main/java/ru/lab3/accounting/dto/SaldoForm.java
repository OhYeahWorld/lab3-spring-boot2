package ru.lab3.accounting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public class SaldoForm {
    @NotNull
    @Positive
    private Integer apartmentNumber;

    @NotBlank
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "Укажите месяц в формате ГГГГ-ММ")
    private String period;

    @NotNull
    @DecimalMin(value = "-999999999.99")
    private BigDecimal openingBalance;

    public Integer getApartmentNumber() { return apartmentNumber; }
    public void setApartmentNumber(Integer apartmentNumber) { this.apartmentNumber = apartmentNumber; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }
}
