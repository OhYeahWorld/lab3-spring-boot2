package ru.lab3.accounting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfig {
    @Bean
    public Clock appClock(@Value("${app.time-zone:Europe/Moscow}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
