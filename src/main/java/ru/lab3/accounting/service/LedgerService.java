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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * до указанного месяца. Недостающие предыдущие периоды создаются автоматически,
     * чтобы начисления и платежи из выписки можно было вносить без ручного создания
     * сальдо. Входящее первого созданного периода — 0, если квартира раньше не
     * встречалась; иначе — входящее самого раннего существующего периода (оно и
     * есть «нулевое» сальдо лицевому счёту). Все периоды после созданных
     * пересчитываются, поэтому цепочка остаётся замкнутой.
     */
    @Transactional
    public void ensureSaldoChain(Integer apartment, LocalDate period) {
        List<Saldo> existingRows = saldoRepository.findByApartmentNumberOrderByPeriodAsc(apartment);
        YearMonth firstExisting = existingRows.isEmpty()
                ? null
                : YearMonth.from(existingRows.get(0).getPeriod());
        YearMonth target = YearMonth.from(period);

        if (existingRows.isEmpty() || (firstExisting != null && !firstExisting.isAfter(target))) {
            // Цепочка уже начинается не позже нужного месяца — просто дополняем
            // недостающие месяцы между последним существующим и целевым.
            fillGap(apartment, existingRows, target);
            return;
        }

        // Цепочка начинается ПОЗЖЕ целевого месяца: вставляем месяцы перед ней.
        BigDecimal opening = normalize(existingRows.get(0).getOpeningBalance());
        List<Saldo> created = new ArrayList<>();
        YearMonth cursor = firstExisting.minusMonths(1);
        while (!cursor.isBefore(target)) {
            Saldo saldo = new Saldo();
            saldo.setApartmentNumber(apartment);
            saldo.setPeriod(cursor.atDay(1));
            saldo.setOpeningBalance(opening);
            saldo.setClosingBalance(calculateClosing(apartment, cursor.atDay(1), opening));
            saveSafely(saldo, "Не удалось создать промежуточный период сальдо.");
            created.add(saldo);
            opening = saldo.getClosingBalance();
            cursor = cursor.minusMonths(1);
        }
        // Первый из созданных (самый ранний месяц) становится нулевым периодом.
        if (!created.isEmpty()) {
            Saldo earliest = created.get(created.size() - 1);
            earliest.setOpeningBalance(ZERO);
            earliest.setClosingBalance(calculateClosing(apartment, earliest.getPeriod(), ZERO));
            saveSafely(earliest, "Не удалось сохранить нулевой период сальдо.");
            recalculateFrom(apartment, earliest.getPeriod());
        }
    }

    /** Дополняет недостающие месяцы после последнего существующего (включая целевой). */
    private void fillGap(Integer apartment, List<Saldo> existingRows, YearMonth target) {
        YearMonth lastExisting = existingRows.isEmpty()
                ? null
                : YearMonth.from(existingRows.get(existingRows.size() - 1).getPeriod());
        if (lastExisting != null && !lastExisting.isBefore(target)) {
            return; // целевой период уже есть
        }
        BigDecimal running;
        YearMonth cursor;
        if (existingRows.isEmpty()) {
            running = ZERO;
            cursor = target;
        } else {
            running = normalize(existingRows.get(existingRows.size() - 1).getClosingBalance());
            cursor = lastExisting.plusMonths(1);
        }
        while (!cursor.isAfter(target)) {
            LocalDate monthStart = cursor.atDay(1);
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

    /**
     * Быстрый пересчёт сразу многих квартир: по одному запросу на таблицу вместо
     * трёх запросов на каждую строку сальдо. Используется импортом выписки,
     * где затронуты сотни лицевых счетов за один проход.
     */
    @Transactional
    public void recalculateAll(java.util.Collection<Integer> apartments) {
        Set<Integer> targets = new LinkedHashSet<>(apartments);
        if (targets.isEmpty()) {
            return;
        }
        Map<java.time.YearMonth, BigDecimal> chargeTotals = new HashMap<>();
        for (Object[] t : chargeRepository.sumAmountByApartmentAndPeriod(targets)) {
            chargeTotals.put(monthKey((LocalDate) t[0]), normalize((BigDecimal) t[1]));
        }
        Map<java.time.YearMonth, BigDecimal> paymentTotals = new HashMap<>();
        for (Object[] t : paymentRepository.sumAmountByApartmentAndPeriod(targets)) {
            paymentTotals.put(monthKey((LocalDate) t[0]), normalize((BigDecimal) t[1]));
        }

        List<Saldo> rows = new ArrayList<>(saldoRepository.findByApartmentNumberInOrderByPeriodAsc(new ArrayList<>(targets)));
        // Пересчёт «на лет» не должен зависеть от порядка обхода — сортируем один раз.
        rows.sort(Comparator.comparing(Saldo::getApartmentNumber).thenComparing(Saldo::getPeriod));

        List<Saldo> changed = new ArrayList<>();
        Integer currentApartment = null;
        Saldo previous = null;
        for (Saldo row : rows) {
            if (!row.getApartmentNumber().equals(currentApartment)) {
                currentApartment = row.getApartmentNumber();
                previous = null;
            }
            BigDecimal opening = previous == null
                    ? normalize(row.getOpeningBalance())
                    : normalize(previous.getClosingBalance());
            java.time.YearMonth key = monthKey(row.getPeriod());
            BigDecimal closing = opening
                    .add(chargeTotals.getOrDefault(key, ZERO))
                    .subtract(paymentTotals.getOrDefault(key, ZERO))
                    .setScale(2, RoundingMode.HALF_UP);
            if (opening.compareTo(normalize(row.getOpeningBalance())) != 0
                    || closing.compareTo(normalize(row.getClosingBalance())) != 0) {
                row.setOpeningBalance(opening);
                row.setClosingBalance(closing);
                changed.add(row);
            }
            previous = row;
        }
        for (Saldo row : changed) {
            // Каждую строку сохраняем отдельно: конфликт версии одной записи
            // не должен отменять пересчёт остальных периодов.
            try {
                saveSafely(row, "Не удалось сохранить сальдо при пересчёте.");
            } catch (DataIntegrityViolationException |
                     org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
                throw new BalanceIntegrityException(
                        "Нарушена последовательность сальдо для квартиры " + row.getApartmentNumber() + ".");
            }
        }
    }

    private static java.time.YearMonth monthKey(LocalDate period) {
        return java.time.YearMonth.from(period);
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

    /**
     * Разбор даты в любом распространённом виде: 2018-01-15, 15.01.2018,
     * 15/01/2018, 15 01 2018. День может отсутствовать — тогда берётся 1-е число.
     */
    public static LocalDate parseDateText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Укажите дату.");
        }
        String s = text.replace('\u00A0', ' ').trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{1,4})[.\\-/ ](\\d{1,2})(?:[.\\-/ ](\\d{1,4}))?(?:$|[ T].*$)").matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Не удалось понять дату \"" + text
                    + "\". Примеры: 2018-01-15, 15.01.2018.");
        }
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        Integer c = m.group(3) == null ? null : Integer.parseInt(m.group(3));
        int year, month, day;
        if (a > 31) {            // 2018-01-15
            year = a;
            month = b;
            day = c == null ? 1 : c;
        } else if (c != null) {  // 15.01.2018 или 15/1/18
            day = a;
            month = b;
            year = c < 100 ? 2000 + c : c;
        } else {                 // только месяц и год: 01.2018
            month = a;
            year = b < 100 ? 2000 + b : b;
            day = 1;
        }
        if (month < 1 || month > 12 || day < 1 || day > 31 || year < 1900 || year > 2200) {
            throw new IllegalArgumentException("Некорректная дата \"" + text + "\".");
        }
        return LocalDate.of(year, month, Math.min(day, LocalDate.of(year, month, 1).lengthOfMonth()));
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
            if (entity instanceof Saldo s) return (T) saldoRepository.saveAndFlush(s);
            if (entity instanceof Charge c) return (T) chargeRepository.saveAndFlush(c);
            if (entity instanceof Payment p) return (T) paymentRepository.saveAndFlush(p);
            throw new IllegalArgumentException("Неподдерживаемый тип записи.");
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateRecordException(message);
        }
    }
}
