package ru.lab3.accounting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;


public class SaldoForm {
    @NotNull
    @Positive
    private Integer apartmentNumber;

    @NotBlank(message = "Укажите период, например 2018-01 или январь 2018")
    private String period;

    private String openingBalance = "0";

    public Integer getApartmentNumber() { return apartmentNumber; }
    public void setApartmentNumber(Integer apartmentNumber) { this.apartmentNumber = apartmentNumber; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(String openingBalance) { this.openingBalance = openingBalance == null ? null : openingBalance.trim(); }
}
