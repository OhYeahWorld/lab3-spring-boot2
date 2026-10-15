package ru.lab3.accounting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;


public class ChargeForm {
    private Long id;

    @NotNull
    @Positive
    private Integer apartmentNumber;

    @NotBlank
    private String period;

    @NotBlank(message = "Укажите сумму, например 4590,22")
    private String amount;

    @Size(max = 255)
    private String description = "";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getApartmentNumber() { return apartmentNumber; }
    public void setApartmentNumber(Integer apartmentNumber) { this.apartmentNumber = apartmentNumber; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount == null ? null : amount.trim(); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
