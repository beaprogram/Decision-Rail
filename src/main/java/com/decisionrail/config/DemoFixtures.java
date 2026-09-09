package com.decisionrail.config;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "app.demo-enabled", havingValue = "true")
public class DemoFixtures implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public DemoFixtures(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("11111111-1111-1111-1111-111111111111", "demo-merchant", "CAD");
        seed("22222222-2222-2222-2222-222222222222", "other-merchant", "CAD");
        seed("33333333-3333-3333-3333-333333333333", "demo-merchant", "USD");
    }

    private void seed(String id, String merchant, String currency) {
        // Repeat startup never refills a spent demo account.
        jdbc.update("INSERT INTO accounts(id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,?,1000000,1000000) ON CONFLICT DO NOTHING",
                UUID.fromString(id), merchant, currency);
    }
}
