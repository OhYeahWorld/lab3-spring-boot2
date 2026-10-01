package ru.lab3.accounting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component("timeFormat")
public class TimeFormat {
    private final DateTimeFormatter formatter;
    private final ZoneId zoneId;

    public TimeFormat(@Value("${app.time-zone:Europe/Moscow}") String zone) {
        this.zoneId = ZoneId.of(zone);
        this.formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    }

    public String format(Instant instant) {
        return instant == null ? "—" : formatter.withZone(zoneId).format(instant);
    }
}
