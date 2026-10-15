package ru.lab3.accounting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;


public class PaymentForm {
    private Long id;

    @NotNull
    @Positive
    private Integer apartmentNumber;

    @NotBlank
    private String period;

    @NotBlank
    private String paymentTime;

    @NotBlank(message = "Укажите сумму, например 4590,22")
    private String amount;

    @Size(max = 100)
    private String paymentReference = "";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getApartmentNumber() { return apartmentNumber; }
    public void setApartmentNumber(Integer apartmentNumber) { this.apartmentNumber = apartmentNumber; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getPaymentTime() { return paymentTime; }
    public void setPaymentTime(String paymentTime) { this.paymentTime = paymentTime; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount == null ? null : amount.trim(); }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
}
