package com.decisionrail.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser authentication over real HTTP, with a real cookie jar.
 *
 * <p>Deliberately not MockMvc. The properties that matter here are cookie attributes, CSRF token round
 * trips, and session identity changing on login and logout, and those only exist once a real client and
 * a real servlet container exchange headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BrowserSessionIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void noListeners(DynamicPropertyRegistry registry) {
        // Authentication does not involve the broker; listeners would only add reconnect noise.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;

    private HttpClient client;

    @BeforeEach
    void freshBrowser() {
        // A cookie manager per test is what makes each test a distinct browser.
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(TIMEOUT).build();
    }

    @Test
    void anAnonymousCallerLearnsItIsAnonymousAndReceivesACsrfToken() throws Exception {
        Reply identity = get("/ui/identity");

        assertThat(identity.status()).isEqualTo(200);
        assertThat(identity.body().path("authenticated").asBoolean()).isFalse();
        assertThat(identity.body().path("username").isNull()).isTrue();
        // No capability is granted before sign-in.
        JsonNode capabilities = identity.body().path("capabilities");
        assertThat(capabilities.path("viewPayments").asBoolean()).isFalse();
        assertThat(capabilities.path("administerDelivery").asBoolean()).isFalse();
        // Authenticated identity must never be cached by a proxy.
        assertThat(identity.header("cache-control")).contains("no-store");
        // The token needed to sign in is issued here, readable by script on purpose.
        assertThat(csrfToken()).isNotBlank();
        assertThat(setCookieFor(identity, "XSRF-TOKEN")).doesNotContain("HttpOnly");
    }

    @Test
    void signingInWithoutACsrfTokenIsRefused() throws Exception {
        get("/ui/identity");
        Reply refused = postForm("/ui/session", "username=demo-merchant&password=demo-test-password-123", null);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");
        // And nothing was authenticated as a side effect.
        assertThat(get("/ui/identity").body().path("authenticated").asBoolean()).isFalse();
    }

    @Test
    void signingInWithTheWrongPasswordIsRefusedWithoutRevealingWhichHalfWasWrong() throws Exception {
        get("/ui/identity");
        Reply refused = login("demo-merchant", "not-the-right-password-1");

        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.body().path("code").asText()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(refused.body().path("detail").asText()).doesNotContain("demo-merchant");
        assertThat(get("/ui/identity").body().path("authenticated").asBoolean()).isFalse();
    }

    @Test
    void aMerchantSignsInAndReceivesOnlyMerchantCapabilities() throws Exception {
        get("/ui/identity");
        String sessionBeforeLogin = sessionCookie();

        Reply signedIn = login("demo-merchant", "demo-test-password-123");
        assertThat(signedIn.status()).isEqualTo(200);
        assertThat(signedIn.body().path("authenticated").asBoolean()).isTrue();
        assertThat(signedIn.body().path("username").asText()).isEqualTo("demo-merchant");
        assertThat(signedIn.body().path("roles")).singleElement()
                .satisfies(role -> assertThat(role.asText()).isEqualTo("ROLE_MERCHANT"));

        JsonNode capabilities = signedIn.body().path("capabilities");
        assertThat(capabilities.path("viewPayments").asBoolean()).isTrue();
        assertThat(capabilities.path("createPayments").asBoolean()).isTrue();
        assertThat(capabilities.path("viewReplay").asBoolean()).isTrue();
        assertThat(capabilities.path("viewPolicies").asBoolean()).isTrue();
        // A merchant never administers delivery or registers policy.
        assertThat(capabilities.path("registerPolicies").asBoolean()).isFalse();
        assertThat(capabilities.path("administerDelivery").asBoolean()).isFalse();
        assertThat(capabilities.path("configureShadow").asBoolean()).isFalse();

        // Session fixation: the identity is not attached to whatever id the caller arrived with.
        assertThat(sessionCookie()).isNotNull().isNotEqualTo(sessionBeforeLogin);
        // The session alone now authorises merchant reads.
        assertThat(get("/ui/payments?limit=1").status()).isEqualTo(200);
        assertThat(get("/ui/accounts").status()).isEqualTo(200);
    }

    @Test
    void anAdministratorGetsDeliveryControlAndNoMerchantAccess() throws Exception {
        get("/ui/identity");
        Reply signedIn = login("admin", "admin-test-password-123");

        assertThat(signedIn.status()).isEqualTo(200);
        JsonNode capabilities = signedIn.body().path("capabilities");
        assertThat(capabilities.path("administerDelivery").asBoolean()).isTrue();
        assertThat(capabilities.path("registerPolicies").asBoolean()).isTrue();
        assertThat(capabilities.path("configureShadow").asBoolean()).isTrue();
        // An administrator must not be able to reach a tenant's money or replay jobs.
        assertThat(capabilities.path("viewPayments").asBoolean()).isFalse();
        assertThat(capabilities.path("createPayments").asBoolean()).isFalse();

        assertThat(get("/ui/ops/delivery").status()).isEqualTo(200);
        assertThat(get("/ui/ops/outbox/failed").status()).isEqualTo(200);
        assertThat(get("/ui/payments?limit=1").status()).isEqualTo(403);
        assertThat(get("/ui/accounts").status()).isEqualTo(403);
        assertThat(get("/ui/replay-jobs").status()).isEqualTo(403);
    }

    @Test
    void theOperationsIdentityStaysMetricsOnly() throws Exception {
        get("/ui/identity");
        Reply signedIn = login("operations", "operations-test-password-123");

        // It can sign in and is told plainly that it has no dashboard capability. Being able to
        // authenticate is not the same as being granted anything.
        assertThat(signedIn.status()).isEqualTo(200);
        JsonNode capabilities = signedIn.body().path("capabilities");
        assertThat(capabilities.path("viewMetrics").asBoolean()).isTrue();
        for (String granted : List.of("viewPayments", "createPayments", "viewAccounts", "viewPolicies",
                "registerPolicies", "viewReplay", "createReplay", "viewShadowComparisons",
                "administerDelivery", "configureShadow")) {
            assertThat(capabilities.path(granted).asBoolean()).as("operations must not gain %s", granted).isFalse();
        }
        assertThat(get("/ui/payments?limit=1").status()).isEqualTo(403);
        assertThat(get("/ui/ops/delivery").status()).isEqualTo(403);
        assertThat(get("/ui/policies").status()).isEqualTo(403);
    }

    @Test
    void aMerchantIsRefusedAdministrativeRoutesWithoutFallingThroughToTheBroadRule() throws Exception {
        get("/ui/identity");
        login("demo-merchant", "demo-test-password-123");

        // The merchant rule is the last match in the chain; an administrative path must be caught by
        // its own rule before reaching it.
        assertThat(get("/ui/ops/delivery").status()).isEqualTo(403);
        assertThat(get("/ui/ops/outbox/failed").status()).isEqualTo(403);
        assertThat(get("/ui/ops/shadow").status()).isEqualTo(403);
        assertThat(postJson("/ui/policies", "{\"versionId\":\"x1\",\"definition\":{\"rules\":[]}}").status()).isEqualTo(403);
        // Policy reads are shared with administrators and stay available.
        assertThat(get("/ui/policies").status()).isEqualTo(200);
    }

    @Test
    void logoutDestroysTheSessionOnTheServerSoTheOldCookieCannotBeReplayed() throws Exception {
        get("/ui/identity");
        login("demo-merchant", "demo-test-password-123");
        String sessionBefore = sessionCookie();
        assertThat(get("/ui/payments?limit=1").status()).isEqualTo(200);

        Reply loggedOut = delete("/ui/session");
        assertThat(loggedOut.status()).isEqualTo(204);
        assertThat(get("/ui/identity").body().path("authenticated").asBoolean()).isFalse();
        assertThat(get("/ui/payments?limit=1").status()).isEqualTo(401);

        // Replaying the exact pre-logout cookie on a fresh client must not work either: the session was
        // destroyed server-side, not merely dropped by this browser.
        HttpClient replay = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpResponse<String> replayed = replay.send(HttpRequest.newBuilder(uri("/ui/payments?limit=1"))
                .header("Cookie", "JSESSIONID=" + sessionBefore).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(replayed.statusCode()).isEqualTo(401);
    }

    /**
     * A transition must leave the browser able to make its next request.
     *
     * <p>Authentication rotates the CSRF token, and a successful login short-circuits the filter chain,
     * so the filter that normally writes the token cookie never runs on that response. The browser was
     * therefore left holding the pre-login value, which the server had already replaced. The previous
     * logout test did not notice because it read the payment list in between, and that unrelated GET was
     * what materialised the rotated token. These tests deliberately make no intervening request.
     */
    @Test
    void signingInIssuesTheRotatedCsrfTokenOnTheLoginResponseItself() throws Exception {
        get("/ui/identity");
        String beforeLogin = csrfToken();
        assertThat(beforeLogin).isNotBlank();

        Reply signedIn = login("demo-merchant", "demo-test-password-123");

        assertThat(signedIn.status()).isEqualTo(200);
        // Rotation expires the old cookie and then issues its replacement, so this response carries two
        // XSRF-TOKEN headers. What matters is that a usable one is among them: before the fix the expiry
        // was the only one sent, and asserting merely that some header was present would have passed.
        assertThat(issuedCookies(signedIn, "XSRF-TOKEN"))
                .anySatisfy(value -> assertThat(value).doesNotContain("HttpOnly"));
        assertThat(csrfToken()).isNotBlank().isNotEqualTo(beforeLogin);
    }

    @Test
    void aMutationImmediatelyAfterSigningInIsAcceptedWithNoInterveningRequest() throws Exception {
        get("/ui/identity");
        login("demo-merchant", "demo-test-password-123");

        // The only token this client can possibly hold is the one the login response issued.
        Reply loggedOut = delete("/ui/session");

        assertThat(loggedOut.status()).isEqualTo(204);
        assertThat(get("/ui/identity").body().path("authenticated").asBoolean()).isFalse();
    }

    @Test
    void signingOutIssuesAFreshTokenSoTheNextSignInNeedsNoPageReload() throws Exception {
        get("/ui/identity");
        login("demo-merchant", "demo-test-password-123");
        String whileSignedIn = csrfToken();

        Reply loggedOut = delete("/ui/session");
        assertThat(loggedOut.status()).isEqualTo(204);
        // Destroying the session must not leave the browser with nothing to prove its next request came
        // from it, so the token cookie is replaced rather than deleted.
        assertThat(issuedCookies(loggedOut, "XSRF-TOKEN")).isNotEmpty();
        assertThat(csrfToken()).isNotBlank().isNotEqualTo(whileSignedIn);

        // And the replacement is usable: a second sign-in on the same client, nothing in between.
        Reply signedInAgain = login("demo-merchant", "demo-test-password-123");
        assertThat(signedInAgain.status()).isEqualTo(200);
        assertThat(signedInAgain.body().path("authenticated").asBoolean()).isTrue();
    }

    @Test
    void aSignedOutBrowserStillCannotMutateWithTheTokenItWasGiven() throws Exception {
        get("/ui/identity");
        login("demo-merchant", "demo-test-password-123");
        delete("/ui/session");

        // The fresh token proves origin, never identity. Without a session it buys nothing.
        Reply refused = postJson("/ui/payments/authorizations",
                "{\"accountId\":\"11111111-1111-1111-1111-111111111111\",\"amountMinor\":100,"
                        + "\"currency\":\"CAD\",\"country\":\"CA\"}");
        assertThat(refused.status()).isEqualTo(401);
    }

    @Test
    void aStateChangingBrowserRequestWithoutAValidCsrfTokenIsRefused() throws Exception {
        get("/ui/identity");
        login("demo-merchant", "demo-test-password-123");

        // Authenticated by cookie, but with no token: exactly the shape of a cross-site forgery.
        Reply forged = postJsonWithoutCsrf("/ui/payments/authorizations",
                "{\"accountId\":\"11111111-1111-1111-1111-111111111111\",\"amountMinor\":100,\"currency\":\"CAD\",\"country\":\"CA\"}");
        assertThat(forged.status()).isEqualTo(403);
        assertThat(forged.body().path("code").asText()).isEqualTo("CSRF_TOKEN_INVALID");

        // A wrong token is refused too, not just a missing one.
        Reply wrongToken = send(HttpRequest.newBuilder(uri("/ui/payments/authorizations"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "csrf-check-00000001")
                .header("X-XSRF-TOKEN", "not-the-real-token")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
        assertThat(wrongToken.status()).isEqualTo(403);
    }

    @Test
    void aBrowserSessionCannotAuthenticateTheStatelessApiAndBasicStillWorksThere() throws Exception {
        get("/ui/identity");
        login("demo-merchant", "demo-test-password-123");
        assertThat(get("/ui/payments?limit=1").status()).isEqualTo(200);

        // The session cookie is sent automatically by the client, and must buy nothing on /v1.
        Reply viaSession = get("/v1/activity");
        assertThat(viaSession.status())
                .as("a browser session must not be an alternative way into the stateless API")
                .isEqualTo(401);

        // Basic auth on the same path is unaffected by any of this.
        Reply viaBasic = send(HttpRequest.newBuilder(uri("/v1/activity"))
                .header("Authorization", basic("demo-merchant", "demo-test-password-123"))
                .timeout(TIMEOUT).GET().build());
        assertThat(viaBasic.status()).isEqualTo(200);
    }

    @Test
    void unknownAndDeniedApiPathsStayApiErrorsRatherThanBecomingTheDashboardPage() throws Exception {
        get("/ui/identity");
        login("demo-merchant", "demo-test-password-123");

        for (String path : List.of("/ui/not-a-real-endpoint", "/v1/not-a-real-endpoint",
                "/ui/ops/delivery", "/actuator/not-a-real-endpoint")) {
            Reply reply = get(path);
            assertThat(reply.status()).as("%s must not be 200", path).isNotEqualTo(200);
            assertThat(reply.contentType()).as("%s must not be served as HTML", path).doesNotContain("text/html");
        }

        // A missing dashboard asset is a genuine 404, so a broken build cannot masquerade as a page.
        Reply missingAsset = get("/dashboard/assets/does-not-exist.js");
        assertThat(missingAsset.status()).isEqualTo(404);
    }

    // ----- helpers -----

    private record Reply(int status, JsonNode body, HttpResponse<String> raw) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse("");
        }

        String contentType() {
            return raw.headers().firstValue("content-type").orElse("");
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Reply get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT).GET().build());
    }

    private Reply delete(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT)
                .header("X-XSRF-TOKEN", csrfToken()).DELETE().build());
    }

    private Reply login(String username, String password) throws Exception {
        return postForm("/ui/session",
                "username=" + username + "&password=" + password, csrfToken());
    }

    private Reply postForm(String path, String body, String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        return send(request.build());
    }

    private Reply postJson(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private Reply postJsonWithoutCsrf(String path, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "csrf-check-00000002")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body() == null || response.body().isBlank()
                || !response.headers().firstValue("content-type").orElse("").contains("json")
                ? json.nullNode()
                : json.readTree(response.body());
        return new Reply(response.statusCode(), body, response);
    }

    /** Reads the CSRF token the server issued into this client's cookie jar. */
    private String csrfToken() {
        return cookieValue("XSRF-TOKEN");
    }

    private String sessionCookie() {
        return cookieValue("JSESSIONID");
    }

    private String cookieValue(String name) {
        CookieManager manager = (CookieManager) client.cookieHandler().orElseThrow();
        return manager.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(java.net.HttpCookie::getValue)
                .findFirst().orElse(null);
    }

    /**
     * The {@code Set-Cookie} headers for one name that actually issue a value.
     *
     * <p>Expiring a cookie is also a {@code Set-Cookie} header, so a test that only checks a header was
     * present cannot tell an issued token from a deleted one.
     */
    private static List<String> issuedCookies(Reply reply, String name) {
        return reply.raw().headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .filter(value -> !value.startsWith(name + "=;") && !value.contains("Max-Age=0"))
                .toList();
    }

    private static String setCookieFor(Reply reply, String name) {
        Optional<String> header = reply.raw().headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith(name + "=")).findFirst();
        return header.orElse("");
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
