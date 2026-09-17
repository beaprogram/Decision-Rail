package com.decisionrail.publicdemo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The public portfolio deployment, as configuration.
 *
 * <p>Off by default. Nothing here changes the application's guarantees; what it adds is one more
 * identity and a set of budgets so that a shared, publicly reachable instance stays usable and
 * bounded. The visitor is an ordinary MERCHANT as far as every ownership rule is concerned - it has its
 * own accounts and payments and cannot see anyone else's - and the budgets are applied on the server to
 * both API chains, so the stateless {@code /v1} API is not a way around them.
 *
 * @param enabled                   whether the visitor identity exists and the budgets apply
 * @param visitorUsername           the shared public identity. Its password is public by design; what
 *                                  the limits protect is the instance, not that account
 * @param visitorPassword           supplied outside source control like every other credential
 * @param commandsPerMinute         state-changing requests the visitor may make per minute, across
 *                                  both API chains
 * @param replayJobsPerHour         replay jobs the visitor may start per hour
 * @param maxRunningReplayJobs      replay jobs the visitor may have in flight at once
 * @param reconciliationPerMinute   reconciliation reports the visitor may request per minute; the
 *                                  report walks each account's whole history
 * @param maxPaymentsPerAccount     payments a visitor account may accumulate before authorizations
 *                                  are refused; this is what keeps histories, and therefore
 *                                  reconciliation, bounded
 * @param authFailuresPerWindow     failed sign-in or Basic attempts a client address may make per
 *                                  window before it is refused for the rest of it
 * @param authFailureWindow         the window those failures are counted over
 */
@ConfigurationProperties(prefix = "app.public-demo")
public record PublicDemoProperties(
        boolean enabled,
        String visitorUsername,
        String visitorPassword,
        int commandsPerMinute,
        int replayJobsPerHour,
        int maxRunningReplayJobs,
        int reconciliationPerMinute,
        int maxPaymentsPerAccount,
        int authFailuresPerWindow,
        Duration authFailureWindow) {

    public PublicDemoProperties {
        if (visitorUsername == null || visitorUsername.isBlank()) visitorUsername = "visitor";
        if (commandsPerMinute <= 0) commandsPerMinute = 30;
        if (replayJobsPerHour <= 0) replayJobsPerHour = 6;
        if (maxRunningReplayJobs <= 0) maxRunningReplayJobs = 1;
        if (reconciliationPerMinute <= 0) reconciliationPerMinute = 10;
        if (maxPaymentsPerAccount <= 0) maxPaymentsPerAccount = 300;
        if (authFailuresPerWindow <= 0) authFailuresPerWindow = 10;
        if (authFailureWindow == null || authFailureWindow.isZero() || authFailureWindow.isNegative()) {
            authFailureWindow = Duration.ofMinutes(15);
        }
    }

    /** Whether this identity is the shared public visitor, and therefore budgeted. */
    public boolean isVisitor(String username) {
        return enabled && username != null && username.equals(visitorUsername);
    }
}
