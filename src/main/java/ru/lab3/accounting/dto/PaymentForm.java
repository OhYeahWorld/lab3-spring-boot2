package ru.lab3.accounting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class PaymentForm {
    private Long id;

    @NotNull
    @Positive
    private Integer apartmentNumber;

    @NotBlank
    private String period;

    @NotBlank
    private String paymentDate;

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal amount;

    @NotBlank
    @Size(max = 100)
    private String paymentReference;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getApartmentNumber() { return apartmentNumber; }
    public void setApartmentNumber(Integer apartmentNumber) { this.apartmentNumber = apartmentNumber; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getPaymentDate() { return paymentDate; }
    public void setPaymentDate(String paymentDate) { this.paymentDate = paymentDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }
}
