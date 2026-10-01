package ru.lab3.accounting.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.lab3.accounting.model.Charge;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ChargeRepository extends JpaRepository<Charge, Long> {
    boolean existsByApartmentNumberAndPeriodAndAmount(
            Integer apartmentNumber, LocalDate period, BigDecimal amount);

    boolean existsByApartmentNumberAndPeriodAndAmountAndIdNot(
            Integer apartmentNumber, LocalDate period, BigDecimal amount, Long id);

    @Query("select coalesce(sum(c.amount), 0) from Charge c where c.apartmentNumber = :apartment and c.period = :period")
    BigDecimal sumAmount(@Param("apartment") Integer apartment, @Param("period") LocalDate period);

    List<Charge> findByApartmentNumberOrderByPeriodDescIdDesc(Integer apartmentNumber);

    List<Charge> findByApartmentNumberAndPeriodBetweenOrderByPeriodAscIdAsc(
            Integer apartmentNumber, LocalDate from, LocalDate to);
}
