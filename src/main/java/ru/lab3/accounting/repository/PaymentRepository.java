package ru.lab3.accounting.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.lab3.accounting.model.Payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    boolean existsByPaymentReference(String paymentReference);

    boolean existsByApartmentNumberAndPeriodAndPaymentTimeAndAmount(
            Integer apartmentNumber, LocalDate period, LocalDateTime paymentTime, BigDecimal amount);

    boolean existsByApartmentNumberAndPeriodAndPaymentTimeAndAmountAndIdNot(
            Integer apartmentNumber, LocalDate period, LocalDateTime paymentTime, BigDecimal amount, Long id);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.apartmentNumber = :apartment and p.period = :period")
    BigDecimal sumAmount(@Param("apartment") Integer apartment, @Param("period") LocalDate period);

    @Query("select p.period, sum(p.amount) from Payment p where p.apartmentNumber in :apartments group by p.period")
    List<Object[]> sumAmountByApartmentAndPeriod(@Param("apartments") java.util.Collection<Integer> apartments);

    List<Payment> findByApartmentNumberOrderByPeriodDescIdDesc(Integer apartmentNumber);

    List<Payment> findByApartmentNumberAndPeriodBetweenOrderByPeriodAscIdAsc(
            Integer apartmentNumber, LocalDate from, LocalDate to);
}
