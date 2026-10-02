package ru.lab3.accounting.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.lab3.accounting.dto.ChargeForm;
import ru.lab3.accounting.dto.PaymentForm;
import ru.lab3.accounting.dto.SaldoForm;
import ru.lab3.accounting.exception.BalanceIntegrityException;
import ru.lab3.accounting.exception.DuplicateRecordException;
import ru.lab3.accounting.model.Charge;
import ru.lab3.accounting.model.Payment;
import ru.lab3.accounting.model.Saldo;
import ru.lab3.accounting.repository.ChargeRepository;
import ru.lab3.accounting.repository.PaymentRepository;
import ru.lab3.accounting.repository.SaldoRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

@Service
public class LedgerService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final SaldoRepository saldoRepository;
    private final ChargeRepository chargeRepository;
    private final PaymentRepository paymentRepository;

    public LedgerService(SaldoRepository saldoRepository,
                         ChargeRepository chargeRepository,
                         PaymentRepository paymentRepository) {
        this.saldoRepository = saldoRepository;
        this.chargeRepository = chargeRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Saldo createSaldo(SaldoForm form) {
        LocalDate period = parsePeriod(form.getPeriod());
        Integer apartment = form.getApartmentNumber();

        if (saldoRepository.existsByApartmentNumberAndPeriod(apartment, period)) {
            throw new DuplicateRecordException("Сальдо для квартиры " + apartment + " за " + form.getPeriod() + " уже существует.");
        }

        BigDecimal previousClosing = saldoRepository
                .findTopByApartmentNumberAndPeriodLessThanOrderByPeriodDesc(apartment, period)
                .map(Saldo::getClosingBalance)
                .orElse(form.getOpeningBalance());

        if (saldoRepository.findTopByApartmentNumberAndPeriodLessThanOrderByPeriodDesc(apartment, period).isPresent()
                && normalize(form.getOpeningBalance()).compareTo(normalize(previousClosing)) != 0) {
            throw new BalanceIntegrityException(
                    "Неверное входящее сальдо. Для следующего периода оно должно быть равно " +
                            money(previousClosing) + ".");
        }

        BigDecimal opening = normalize(previousClosing);
        BigDecimal closing = calculateClosing(apartment, period, opening);

        Saldo saldo = new Saldo();
        saldo.setApartmentNumber(apartment);
        saldo.setPeriod(period);
        saldo.setOpeningBalance(opening);
        saldo.setClosingBalance(closing);

        Saldo saved = saveSafely(saldo, "Не удалось сохранить сальдо: запись с такими реквизитами уже существует.");
        recalculateFrom(apartment, period);
        return saved;
    }

    @Transactional
    public Charge createCharge(ChargeForm form) {
        LocalDate period = parsePeriod(form.getPeriod());
        Integer apartment = form.getApartmentNumber();
        String description = normalizeDescription(form.getDescription());
        BigDecimal amount = normalizePositive(form.getAmount());

        ensureSaldoExists(apartment, period);
        ensureChargeUnique(apartment, period, amount, null);

        Charge charge = new Charge();
        charge.setApartmentNumber(apartment);
        charge.setPeriod(period);
        charge.setAmount(amount);
        charge.setDescription(description);

        Charge saved = saveSafely(charge, "Начисление с такими реквизитами уже существует — дубль отклонён.");
        recalculateFrom(apartment, period);
        return saved;
    }

    @Transactional
    public Charge updateCharge(Long id, ChargeForm form) {
        Charge charge = chargeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Начисление не найдено."));

        Integer oldApartment = charge.getApartmentNumber();
        LocalDate oldPeriod = charge.getPeriod();

        Integer newApartment = form.getApartmentNumber();
        LocalDate newPeriod = parsePeriod(form.getPeriod());
        BigDecimal newAmount = normalizePositive(form.getAmount());
        String newDescription = normalizeDescription(form.getDescription());

        ensureSaldoExists(newApartment, newPeriod);
        ensureChargeUnique(newApartment, newPeriod, newAmount, id);

        charge.setApartmentNumber(newApartment);
        charge.setPeriod(newPeriod);
        charge.setAmount(newAmount);
        charge.setDescription(newDescription);
        saveSafely(charge, "Изменение создало бы дубликат начисления — операция отклонена.");

        recalculateFrom(oldApartment, oldPeriod);
        if (!oldApartment.equals(newApartment)) {
            recalculateFrom(newApartment, newPeriod);
        } else {
            LocalDate start = oldPeriod.isBefore(newPeriod) ? oldPeriod : newPeriod;
            recalculateFrom(newApartment, start);
        }
        return charge;
    }

    @Transactional
    public void deleteCharge(Long id) {
        Charge charge = chargeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Начисление не найдено."));
        Integer apartment = charge.getApartmentNumber();
        LocalDate period = charge.getPeriod();
        chargeRepository.delete(charge);
        chargeRepository.flush();
        recalculateFrom(apartment, period);
    }

    @Transactional
    public Payment createPayment(PaymentForm form) {
        LocalDate period = parsePeriod(form.getPeriod());
        LocalDateTime paymentTime = parseDateTime(form.getPaymentTime(), "время платежа");
        Integer apartment = form.getApartmentNumber();
        BigDecimal amount = normalizePositive(form.getAmount());
        String reference = form.getPaymentReference().trim();

        ensureSaldoExists(apartment, period);
        ensurePaymentDateInPeriod(paymentTime.toLocalDate(), period);
        ensurePaymentUnique(apartment, period, paymentTime, amount, null);

        Payment payment = new Payment();
        payment.setApartmentNumber(apartment);
        payment.setPeriod(period);
        payment.setPaymentTime(paymentTime);
        payment.setAmount(amount);
        payment.setPaymentReference(reference);

        Payment saved = saveSafely(payment, "Платёж с такими реквизитами уже существует — дубль отклонён.");
        recalculateFrom(apartment, period);
        return saved;
    }

    @Transactional
    public Payment updatePayment(Long id, PaymentForm form) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Платёж не найден."));

        Integer oldApartment = payment.getApartmentNumber();
        LocalDate oldPeriod = payment.getPeriod();

        Integer newApartment = form.getApartmentNumber();
        LocalDate newPeriod = parsePeriod(form.getPeriod());
        LocalDateTime newPaymentTime = parseDateTime(form.getPaymentTime(), "время платежа");
        BigDecimal newAmount = normalizePositive(form.getAmount());
        String newReference = form.getPaymentReference().trim();

        ensureSaldoExists(newApartment, newPeriod);
        ensurePaymentDateInPeriod(newPaymentTime.toLocalDate(), newPeriod);
        ensurePaymentUnique(newApartment, newPeriod, newPaymentTime, newAmount, id);

        payment.setApartmentNumber(newApartment);
        payment.setPeriod(newPeriod);
        payment.setPaymentTime(newPaymentTime);
        payment.setAmount(newAmount);
        payment.setPaymentReference(newReference);
        saveSafely(payment, "Изменение создало бы дубликат платежа — операция отклонена.");

        recalculateFrom(oldApartment, oldPeriod);
        if (!oldApartment.equals(newApartment)) {
            recalculateFrom(newApartment, newPeriod);
        } else {
            LocalDate start = oldPeriod.isBefore(newPeriod) ? oldPeriod : newPeriod;
            recalculateFrom(newApartment, start);
        }
        return payment;
    }

    @Transactional
    public void deletePayment(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Платёж не найден."));
        Integer apartment = payment.getApartmentNumber();
        LocalDate period = payment.getPeriod();
        paymentRepository.delete(payment);
        paymentRepository.flush();
        recalculateFrom(apartment, period);
    }

    @Transactional(readOnly = true)
    public List<Saldo> listSaldo() {
        return saldoRepository.findAll().stream()
                .sorted(Comparator.comparing(Saldo::getApartmentNumber).thenComparing(Saldo::getPeriod).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Charge> listCharges(Integer apartment) {
        return apartment == null
                ? chargeRepository.findAll().stream().sorted(Comparator.comparing(Charge::getPeriod).reversed().thenComparing(Charge::getId).reversed()).toList()
                : chargeRepository.findByApartmentNumberOrderByPeriodDescIdDesc(apartment);
    }

    @Transactional(readOnly = true)
    public List<Payment> listPayments(Integer apartment) {
        return apartment == null
                ? paymentRepository.findAll().stream().sorted(Comparator.comparing(Payment::getPeriod).reversed().thenComparing(Payment::getId).reversed()).toList()
                : paymentRepository.findByApartmentNumberOrderByPeriodDescIdDesc(apartment);
    }

    @Transactional(readOnly = true)
    public boolean saldoExists(Integer apartment, LocalDate period) {
        return saldoRepository.existsByApartmentNumberAndPeriod(apartment, period);
    }

    private void ensureSaldoExists(Integer apartment, LocalDate period) {
        if (!saldoRepository.existsByApartmentNumberAndPeriod(apartment, period)) {
            throw new BalanceIntegrityException(
                    "Сначала создайте сальдо для квартиры " + apartment + " за " + period + ". После этого начисление/платёж сможет изменить итоговое сальдо.");
        }
    }

    private void ensureChargeUnique(Integer apartment, LocalDate period, BigDecimal amount, Long excludeId) {
        boolean exists = excludeId == null
                ? chargeRepository.existsByApartmentNumberAndPeriodAndAmount(apartment, period, amount)
                : chargeRepository.existsByApartmentNumberAndPeriodAndAmountAndIdNot(apartment, period, amount, excludeId);
        if (exists) {
            throw new DuplicateRecordException("Такое начисление уже есть в базе. Новая запись отклонена.");
        }
    }

    private void ensurePaymentUnique(Integer apartment, LocalDate period, LocalDateTime paymentTime,
                                     BigDecimal amount, Long excludeId) {
        boolean exists = excludeId == null
                ? paymentRepository.existsByApartmentNumberAndPeriodAndPaymentTimeAndAmount(apartment, period, paymentTime, amount)
                : paymentRepository.existsByApartmentNumberAndPeriodAndPaymentTimeAndAmountAndIdNot(apartment, period, paymentTime, amount, excludeId);
        if (exists) {
            throw new DuplicateRecordException("Такой платёж уже есть в базе. Новая запись отклонена.");
        }
    }

    private BigDecimal calculateClosing(Integer apartment, LocalDate period, BigDecimal opening) {
        BigDecimal charges = normalize(chargeRepository.sumAmount(apartment, period));
        BigDecimal payments = normalize(paymentRepository.sumAmount(apartment, period));
        return opening.add(charges).subtract(payments).setScale(2, RoundingMode.HALF_UP);
    }

    /** Пересчитывает только уже заведённые периоды, не создавая недостающих записей. */
    private void recalculateFrom(Integer apartment, LocalDate period) {
        List<Saldo> rows = saldoRepository.findByApartmentNumberAndPeriodGreaterThanEqualOrderByPeriodAsc(apartment, period);
        if (rows.isEmpty()) {
            return;
        }

        Saldo previous = saldoRepository.findTopByApartmentNumberAndPeriodLessThanOrderByPeriodDesc(apartment, period).orElse(null);
        for (Saldo current : rows) {
            BigDecimal opening = previous == null
                    ? normalize(current.getOpeningBalance())
                    : normalize(previous.getClosingBalance());
            BigDecimal closing = calculateClosing(apartment, current.getPeriod(), opening);
            current.setOpeningBalance(opening);
            current.setClosingBalance(closing);
            try {
                saldoRepository.save(current);
            } catch (DataIntegrityViolationException ex) {
                throw new BalanceIntegrityException("Нарушена последовательность сальдо для квартиры " + apartment + ".");
            }
            previous = current;
        }
    }

    private LocalDate parsePeriod(String text) {
        try {
            return YearMonth.parse(text).atDay(1);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Период должен быть указан как ГГГГ-ММ.");
        }
    }

    private LocalDateTime parseDateTime(String text, String label) {
        try {
            // Формат input type="datetime-local": 2017-01-15T10:30 или 2017-01-15T10:30:00
            return LocalDateTime.parse(text);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Некорректное " + label +
                    ". Ожидается время из выписки в формате ГГГГ-ММ-ДДЧЧ:ММ.");
        }
    }

    private void ensurePaymentDateInPeriod(LocalDate paymentDate, LocalDate period) {
        if (!YearMonth.from(paymentDate).atDay(1).equals(period)) {
            throw new BalanceIntegrityException("Дата платежа из выписки должна находиться внутри указанного периода.");
        }
    }

    private BigDecimal normalizePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("Сумма должна быть больше нуля.");
        }
        return normalize(value);
    }

    private BigDecimal normalize(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeDescription(String text) {
        return text == null ? "" : text.trim();
    }

    private String money(BigDecimal amount) {
        return normalize(amount).toPlainString();
    }

    private <T> T saveSafely(T entity, String message) {
        try {
            if (entity instanceof Saldo s) return (T) saldoRepository.save(s);
            if (entity instanceof Charge c) return (T) chargeRepository.save(c);
            if (entity instanceof Payment p) return (T) paymentRepository.save(p);
            throw new IllegalArgumentException("Неподдерживаемый тип записи.");
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateRecordException(message);
        }
    }
}
