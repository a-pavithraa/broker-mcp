package com.broker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Configuration
@Profile("demo")
public class DemoProfileConfig {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Bean
    @Primary
    Clock demoClock(@Value("${broker.demo.clock:2026-03-18T11:00:00+05:30}") String demoClock) {
        return Clock.fixed(ZonedDateTime.parse(demoClock).toInstant(), INDIA);
    }
}
