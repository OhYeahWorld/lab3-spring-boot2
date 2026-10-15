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

        BigDecimal opening = form.getOpeningBalance() == null || form.getOpeningBalance().isBlank()
                ? ZERO : parseAmountText(form.getOpeningBalance());

        java.util.Optional<Saldo> existing =
                saldoRepository.findByApartmentNumberAndPeriod(apartment, period);
        if (existing.isPresent()) {
            // Повторная запись того же периода просто обновляется — без ошибок.
            Saldo saldo = existing.get();
            saldo.setOpeningBalance(opening);
            saldo.setClosingBalance(calculateClosing(apartment, period, opening));
            Saldo saved = saveSafely(saldo, "Не удалось сохранить сальдо.");
            recalculateFrom(apartment, period);
            return saved;
        }

        ensureSaldoChain(apartment, period);

        Saldo saldo = new Saldo();
        saldo.setApartmentNumber(apartment);
        saldo.setPeriod(period);
        saldo.setOpeningBalance(opening);
        saldo.setClosingBalance(calculateClosing(apartment, period, opening));

        Saldo saved = saveSafely(saldo, "Не удалось сохранить сальдо: запись с такими реквизитами уже существует.");
        recalculateFrom(apartment, period);
        return saved;
    }

    /**
     * Гарантирует существование непрерывной цепочки сальдо для квартиры вплоть
     * до указанного месяца. Недостающие предыдущие периоды создаются автоматически
     * (с нулевым входящим остатком для самого первого), чтобы начисления и
     * платежи из выписки можно было вносить без ручного создания сальдо.
     */
    @Transactional
    public void ensureSaldoChain(Integer apartment, LocalDate period) {
        List<Saldo> existingRows = saldoRepository.findByApartmentNumberOrderByPeriodAsc(apartment);
        YearMonth firstNeeded = existingRows.isEmpty()
                ? YearMonth.from(period)
                : minMonth(YearMonth.from(existingRows.get(0).getPeriod()), YearMonth.from(period));

        BigDecimal running = existingRows.isEmpty()
                ? ZERO
                : normalize(existingRows.get(0).getOpeningBalance());

        LocalDate nextExisting = existingRows.isEmpty()
                ? null
                : existingRows.get(0).getPeriod();

        YearMonth cursor = firstNeeded;
        while (!cursor.isAfter(YearMonth.from(period))) {
            LocalDate monthStart = cursor.atDay(1);
            if (nextExisting != null && nextExisting.equals(monthStart)) {
                running = normalize(findOpening(existingRows, monthStart));
                nextExisting = nextExistingPeriod(existingRows, monthStart);
                cursor = cursor.plusMonths(1);
                continue;
            }
            Saldo saldo = new Saldo();
            saldo.setApartmentNumber(apartment);
            saldo.setPeriod(monthStart);
            saldo.setOpeningBalance(running);
            saldo.setClosingBalance(calculateClosing(apartment, monthStart, running));
            saveSafely(saldo, "Не удалось создать промежуточный период сальдо.");
            running = saldo.getClosingBalance();
            cursor = cursor.plusMonths(1);
        }
    }

    private static YearMonth minMonth(YearMonth a, YearMonth b) {
        return a.isBefore(b) ? a : b;
    }

    private static BigDecimal findOpening(List<Saldo> rows, LocalDate period) {
        return rows.stream().filter(r -> r.getPeriod().equals(period))
                .map(Saldo::getOpeningBalance).findFirst().orElse(ZERO);
    }

    private static LocalDate nextExistingPeriod(List<Saldo> rows, LocalDate after) {
        return rows.stream().map(Saldo::getPeriod).filter(p -> p.isAfter(after))
                .min(LocalDate::compareTo).orElse(null);
    }

    @Transactional
    public Charge createCharge(ChargeForm form) {
        LocalDate period = parsePeriod(form.getPeriod());
        Integer apartment = form.getApartmentNumber();
        String description = normalizeDescription(form.getDescription());
        BigDecimal amount = parseAmountText(form.getAmount());

        ensureSaldoChain(apartment, period);

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
        BigDecimal newAmount = parseAmountText(form.getAmount());
        String newDescription = normalizeDescription(form.getDescription());

        ensureSaldoChain(newApartment, newPeriod);

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
        LocalDateTime paymentTime = parseDateTime(form.getPaymentTime(), "время/дату платежа");
        Integer apartment = form.getApartmentNumber();
        BigDecimal amount = parseAmountText(form.getAmount());
        String reference = form.getPaymentReference() == null ? "" : form.getPaymentReference().trim();

        ensureSaldoChain(apartment, period);
        paymentTime = alignToPeriod(paymentTime, period);

        if (!reference.isEmpty() && paymentRepository.existsByPaymentReference(reference)) {
            throw new DuplicateRecordException("Платёж с реквизитами \"" + reference
                    + "\" уже загружен — повторная загрузка того же поручения отклонена.");
        }

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
        LocalDateTime newPaymentTime = parseDateTime(form.getPaymentTime(), "время/дату платежа");
        BigDecimal newAmount = parseAmountText(form.getAmount());
        String newReference = form.getPaymentReference() == null ? "" : form.getPaymentReference().trim();

        ensureSaldoChain(newApartment, newPeriod);
        newPaymentTime = alignToPeriod(newPaymentTime, newPeriod);

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

    /** Публичный пересчёт цепочки сальдо, начиная с указанного месяца. */
    @Transactional
    public void recalculate(Integer apartment, LocalDate period) {
        recalculateFrom(apartment, period);
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

    /**
     * Принимает период в любом удобном виде: 2018-01, 2018.01, 01.2018,
     * «январь 2018», полную дату из выписки (2018-01-15 или 15.01.2018) —
     * всё сводится к первому числу месяца.
     */
    private LocalDate parsePeriod(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Укажите период, например 2018-01.");
        }
        String s = text.trim().toLowerCase(java.util.Locale.ROOT);

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})[.\\-/](\\d{1,2})(?:[.\\-/](\\d{1,2}))?").matcher(s);
        if (m.find()) {
            return safeMonth(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        }
        m = java.util.regex.Pattern
                .compile("(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{4})").matcher(s);
        if (m.find()) {
            int first = Integer.parseInt(m.group(1));
            int second = Integer.parseInt(m.group(2));
            int year = Integer.parseInt(m.group(3));
            int month = first > 12 ? second : second; // ДД.ММ.ГГГГ: месяц — второй
            if (first > 12 && second > 12) {
                month = first;
            }
            return safeMonth(year, month);
        }
        m = java.util.regex.Pattern
                .compile("(\\d{1,2})[.\\-/](\\d{4})$").matcher(s);
        if (m.find()) {
            return safeMonth(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        }
        for (String[] pair : RU_MONTHS) {
            if (s.contains(pair[0])) {
                java.util.regex.Matcher y = java.util.regex.Pattern.compile("(\\d{4})").matcher(s);
                if (y.find()) {
                    return safeMonth(Integer.parseInt(y.group(1)), Integer.parseInt(pair[1]));
                }
            }
        }
        throw new IllegalArgumentException(
                "Не удалось понять период \"" + text + "\". Можно указать 2018-01, январь 2018 или дату из выписки.");
    }

    private static LocalDate safeMonth(int year, int month) {
        if (year < 1900 || year > 2999 || month < 1 || month > 12) {
            throw new IllegalArgumentException("Проверьте номер месяца/года в периоде.");
        }
        return LocalDate.of(year, month, 1);
    }

    private static final List<String[]> RU_MONTHS = List.of(
            new String[]{"янв", "1"}, new String[]{"фев", "2"}, new String[]{"лют", "2"},
            new String[]{"мар", "3"}, new String[]{"апр", "4"}, new String[]{"май", "5"},
            new String[]{"июн", "6"}, new String[]{"июл", "7"}, new String[]{"авг", "8"},
            new String[]{"сен", "9"}, new String[]{"окт", "10"}, new String[]{"ноя", "11"},
            new String[]{"дек", "12"});

    /**
     * Принимает дату платежа в любом виде из выписки: 2018-01-15T10:30,
     * 2018-01-15 10:30, 15.01.2018, просто дату — время по умолчанию 00:00.
     */
    private LocalDateTime parseDateTime(String text, String label) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Укажите " + label + ", например 15.01.2018 или 2018-01-15 10:30.");
        }
        String s = text.trim().replace('T', ' ');
        try {
            return LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d H:m[:ss]"));
        } catch (RuntimeException ignored) {
            // продолжаем разбор
        }
        try {
            return LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("yyyy-M-d")).atStartOfDay();
        } catch (RuntimeException ignored) {
            // продолжаем разбор
        }
        java.util.regex.Matcher dmy = java.util.regex.Pattern
                .compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?").matcher(s);
        if (dmy.matches()) {
            int day = Integer.parseInt(dmy.group(1));
            int month = Integer.parseInt(dmy.group(2));
            int year = Integer.parseInt(dmy.group(3));
            int hour = dmy.group(4) == null ? 0 : Integer.parseInt(dmy.group(4));
            int minute = dmy.group(5) == null ? 0 : Integer.parseInt(dmy.group(5));
            int second = dmy.group(6) == null ? 0 : Integer.parseInt(dmy.group(6));
            return LocalDateTime.of(year, month, day, hour, minute, second);
        }
        throw new IllegalArgumentException("Некорректная " + label + " \"" + text
                + "\". Подойдёт формат из выписки: 15.01.2018 или 2018-01-15 10:30.");
    }

    /** Если дата платежа не совпадает с выбранным месяцем — подтягиваем платёж в месяц. */
    private LocalDateTime alignToPeriod(LocalDateTime paymentTime, LocalDate period) {
        if (YearMonth.from(paymentTime).atDay(1).equals(period)) {
            return paymentTime;
        }
        int lastDay = period.lengthOfMonth();
        int day = Math.min(paymentTime.getDayOfMonth(), lastDay);
        return paymentTime.withYear(period.getYear()).withMonth(period.getMonthValue()).withDayOfMonth(day);
    }

    /** Разбор строковой суммы формы ввода (поддерживает форматы выписки). */
    public static BigDecimal parseAmountText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Укажите сумму.");
        }
        String s = text.replace('\u00A0', ' ').trim();
        java.util.regex.Matcher dash = java.util.regex.Pattern
                .compile("^(-?\\d+)-(\\d{2})$").matcher(s);
        if (dash.matches()) {
            return new BigDecimal(dash.group(1) + "." + dash.group(2));
        }
        String cleaned = s.replace(" ", "");
        long dots = cleaned.chars().filter(c -> c == '.').count();
        long commas = cleaned.chars().filter(c -> c == ',').count();
        if (dots > 1 || (commas >= 1 && dots >= 1)) {
            cleaned = cleaned.replaceAll("[.,](?=\\D)", ""); // разделители тысяч
        }
        cleaned = cleaned.replace(',', '.');
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Не удалось понять сумму \"" + text
                    + "\". Примеры: 4590.22, 4 590,22, 311-27.");
        }
    }

    private BigDecimal normalize(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeDescription(String text) {
        return text == null ? "" : text.trim();
    }

    private String money(BigDecimal amount) {
        return normalize(amount).toPlainString().replace('.', ',');
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
