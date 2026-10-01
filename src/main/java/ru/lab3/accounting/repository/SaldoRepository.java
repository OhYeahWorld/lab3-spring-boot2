package ru.lab3.accounting.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.lab3.accounting.model.Saldo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SaldoRepository extends JpaRepository<Saldo, Long> {
    boolean existsByApartmentNumberAndPeriod(Integer apartmentNumber, LocalDate period);

    Optional<Saldo> findByApartmentNumberAndPeriod(Integer apartmentNumber, LocalDate period);

    Optional<Saldo> findTopByApartmentNumberAndPeriodLessThanOrderByPeriodDesc(Integer apartmentNumber, LocalDate period);

    List<Saldo> findByApartmentNumberAndPeriodGreaterThanEqualOrderByPeriodAsc(Integer apartmentNumber, LocalDate period);

    List<Saldo> findByApartmentNumberOrderByPeriodAsc(Integer apartmentNumber);

    long countByApartmentNumber(Integer apartmentNumber);
}
