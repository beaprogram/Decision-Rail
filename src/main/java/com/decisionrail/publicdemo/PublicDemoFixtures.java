package com.decisionrail.publicdemo;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The visitor merchant and its funded accounts, present whenever the public demo is on.
 *
 * <p>Data, not schema: the merchant row and the accounts are what the configured visitor identity
 * needs to exist as a tenant, and they are written idempotently so a restart adds nothing and never
 * refills a spent balance. The accounts have fixed ids so the seeding script, the walkthrough and the
 * documentation can name them. Nothing here touches any other merchant's rows.
 *
 * <p>Three accounts, deliberately: two in CAD so a visitor whose first account has reached its history
 * cap has somewhere to continue, and one in USD so currency handling is visible without mixing
 * currencies inside an account, which the schema forbids. The USD account is small on purpose - below
 * the policy's elevated-amount threshold - so that an authorization the policy approves can still be
 * declined for insufficient funds, which is the distinction the dashboard draws between a risk
 * decision and a funding outcome. A larger balance would make that case impossible to show.
 */
@Component
@ConditionalOnProperty(name = "app.public-demo.enabled", havingValue = "true")
public class PublicDemoFixtures implements ApplicationRunner {
    public static final UUID CAD_PRIMARY = UUID.fromString("aaaa0001-0000-4000-8000-000000000001");
    public static final UUID CAD_SECONDARY = UUID.fromString("aaaa0001-0000-4000-8000-000000000002");
    public static final UUID USD = UUID.fromString("aaaa0001-0000-4000-8000-000000000003");

    private final JdbcTemplate jdbc;
    private final PublicDemoProperties properties;

    public PublicDemoFixtures(JdbcTemplate jdbc, PublicDemoProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String visitor = properties.visitorUsername();
        jdbc.update("INSERT INTO merchants (id) VALUES (?) ON CONFLICT DO NOTHING", visitor);
        seed(CAD_PRIMARY, visitor, "CAD", 5_000_000);
        seed(CAD_SECONDARY, visitor, "CAD", 5_000_000);
        seed(USD, visitor, "USD", 60_000);
    }

    private void seed(UUID id, String merchant, String currency, long openingBalanceMinor) {
        jdbc.update("""
                INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, id, merchant, currency, openingBalanceMinor, openingBalanceMinor);
    }
}
