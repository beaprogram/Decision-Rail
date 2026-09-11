package com.decisionrail.policy;

import com.decisionrail.decision.RuleEvaluator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Classification of invalid policy definitions, and the bounds at which they flip.
 *
 * <p>Several validation failures lived in the domain value objects rather than in the translator, so
 * they escaped as IllegalArgumentException and reached the generic handler as HTTP 500. A caller
 * submitting a negative predicate amount or an over-long description was told the server had failed,
 * with no indication of what to correct. These are ordinary input errors and belong in the existing
 * structured 400 contract.
 *
 * <p>Unexpected failures must keep their own classification, so this checks that a rebinding conflict
 * is still 409 and that valid boundary values are still accepted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PolicyValidationBoundaryTest {
    private static final String ADMIN = basic("admin", "admin-test-password-123");
    private static final String MERCHANT = basic("demo-merchant", "demo-test-password-123");

    @DynamicPropertySource
    static void noListeners(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    // ----- parser classification -----

    @Test
    void predicateAmountsOutsideTheirRangeAreValidationFailures() {
        assertRejected("$.rules[0].expression.amountMinor", rule(amountRule(-1)));
        assertRejected("$.rules[0].expression.amountMinor", rule(amountRule(-1_000_000)));
        assertRejected("$.rules[0].expression.amountMinor", rule(amountRule(1_000_000_000_001L)));
        // The inclusive bounds themselves stay valid.
        assertAccepted(rule(amountRule(0)));
        assertAccepted(rule(amountRule(1_000_000_000_000L)));
    }

    @Test
    void anOverLongDescriptionIsAValidationFailureAtItsExactBound() {
        assertAccepted(rule(amountRule(100), "d".repeat(512)));
        assertRejected("$.rules[0].description", rule(amountRule(100), "d".repeat(513)));
        assertRejected("$.rules[0].description", rule(amountRule(100), ""));
    }

    @Test
    void malformedCountryAndCurrencyCodesAreValidationFailures() {
        assertRejected("$.rules[0].expression.countries", rule(
                "{\"operator\":\"COUNTRY_IN\",\"countries\":[\"CAN\"]}"));
        assertRejected("$.rules[0].expression.countries", rule(
                "{\"operator\":\"COUNTRY_IN\",\"countries\":[\"C\"]}"));
        assertRejected("$.rules[0].expression.countries", rule(
                "{\"operator\":\"COUNTRY_IN\",\"countries\":[\"C1\"]}"));
        assertRejected("$.rules[0].expression.currencies", rule(
                "{\"operator\":\"CURRENCY_IN\",\"currencies\":[\"CADX\"]}"));
        assertRejected("$.rules[0].expression.currencies", rule(
                "{\"operator\":\"CURRENCY_IN\",\"currencies\":[\"12\"]}"));
    }

    @Test
    void anOverSizedCodeSetIsAValidationFailureAtItsExactBound() {
        assertAccepted(rule("{\"operator\":\"COUNTRY_IN\",\"countries\":[" + countryCodes(32) + "]}"));
        assertRejected("$.rules[0].expression.countries",
                rule("{\"operator\":\"COUNTRY_IN\",\"countries\":[" + countryCodes(33) + "]}"));
    }

    @Test
    void expressionDepthBeyondTheLimitIsAValidationFailureAtItsExactBound() {
        assertAccepted(rule(nestedExpression(RuleEvaluator.MAX_DEPTH)));
        assertRejected("$.rules[0].expression", rule(nestedExpression(RuleEvaluator.MAX_DEPTH + 1)));
    }

    @Test
    void anExcessiveNodeCountIsAValidationFailure() {
        // A root of 16 branches, each with 7 leaves: 1 + 16 + 112 = 129 nodes, one past the limit.
        StringBuilder branches = new StringBuilder();
        for (int branch = 0; branch < 16; branch++) {
            if (branch > 0) branches.append(',');
            StringBuilder leaves = new StringBuilder();
            for (int leaf = 0; leaf < 7; leaf++) {
                if (leaf > 0) leaves.append(',');
                leaves.append(amountRule(leaf + 1));
            }
            branches.append("{\"operator\":\"ALL\",\"children\":[").append(leaves).append("]}");
        }
        assertRejected("$.rules[0].expression",
                rule("{\"operator\":\"ALL\",\"children\":[" + branches + "]}"));
    }

    @Test
    void tooManyChildrenIsAValidationFailureAtItsExactBound() {
        assertAccepted(rule(allOf(RuleEvaluator.MAX_CHILDREN)));
        assertRejected("$.rules[0].expression.children", rule(allOf(RuleEvaluator.MAX_CHILDREN + 1)));
    }

    // ----- HTTP classification -----

    @Test
    void aRejectedDefinitionReturnsStructuredBadRequestAndPersistsNothing() throws Exception {
        long versionsBefore = policyVersionCount();

        for (String definition : List.of(
                rule(amountRule(-5)),
                rule(amountRule(100), "d".repeat(900)),
                rule("{\"operator\":\"COUNTRY_IN\",\"countries\":[\"CAN\"]}"),
                rule(nestedExpression(RuleEvaluator.MAX_DEPTH + 1)),
                rule("{\"operator\":\"REGEX_MATCH\",\"pattern\":\".*\"}"))) {
            String versionId = "invalid-" + UUID.randomUUID().toString().substring(0, 8);
            Reply reply = submit(versionId, definition, ADMIN);

            assertThat(reply.status()).as("definition should be a 400: %s", definition).isEqualTo(400);
            assertThat(reply.body().path("code").asText()).isEqualTo("INVALID_POLICY_DEFINITION");
            assertThat(reply.body().path("status").asInt()).isEqualTo(400);
            // The detail names the offending path so an author can correct the document.
            assertThat(reply.body().path("detail").asText()).contains("$.rules[0]");
            assertThat(reply.body().path("requestId").asText()).isNotBlank();
            assertThat(versionExists(versionId)).as("no invalid policy may be persisted").isFalse();
        }
        assertThat(policyVersionCount()).isEqualTo(versionsBefore);
    }

    @Test
    void validBoundaryDefinitionsAreStillAcceptedAndCanonicalised() throws Exception {
        String versionId = "boundary-" + UUID.randomUUID().toString().substring(0, 8);
        Reply created = submit(versionId, rule(amountRule(0), "d".repeat(512)), ADMIN);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("definitionHash").asText()).hasSize(64);
        assertThat(created.body().path("origin").asText()).isEqualTo("CANDIDATE");
        assertThat(versionExists(versionId)).isTrue();

        // Rebinding that identifier to different content is still a conflict, not a 400.
        Reply conflict = submit(versionId, rule(amountRule(1), "d".repeat(512)), ADMIN);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().path("code").asText()).isEqualTo("POLICY_VERSION_CONFLICT");

        // And an identical resubmission is still an idempotent 200.
        Reply repeat = submit(versionId, rule(amountRule(0), "d".repeat(512)), ADMIN);
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.body().path("definitionHash").asText())
                .isEqualTo(created.body().path("definitionHash").asText());
    }

    @Test
    void authorisationIsStillCheckedBeforeValidation() throws Exception {
        // A merchant must be refused, not handed a validation message about an admin-only route.
        Reply refused = submit("unauthorised-" + UUID.randomUUID().toString().substring(0, 8),
                rule(amountRule(-5)), MERCHANT);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body().path("code").asText()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void theBuiltInPolicyIsUnaffectedByTheTightenedValidation() throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/v1/policies/demo-v1").header("Authorization", ADMIN)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode builtin = json.readTree(result.getResponse().getContentAsString());
        assertThat(builtin.path("origin").asText()).isEqualTo("BUILTIN");
        assertThat(builtin.path("ruleCount").asInt()).isEqualTo(4);
        // definition comes back as JSON text. It is not byte-identical to what was hashed, because
        // PostgreSQL's jsonb storage normalises key order and whitespace its own way. The hash is
        // over the application's canonical form, so verifying it means re-canonicalising the parsed
        // definition, which is precisely the property that makes the hash identify meaning rather
        // than formatting.
        assertThat(builtin.path("definition").isTextual()).isTrue();
        JsonNode definition = json.readTree(builtin.path("definition").asText());
        assertThatCode(() -> PolicySchema.toRuleSet("demo-v1", definition)).doesNotThrowAnyException();
        assertThat(PolicyCanonicalizer.hash(
                PolicyCanonicalizer.canonicalJson(PolicySchema.toRuleSet("demo-v1", definition))))
                .isEqualTo(builtin.path("definitionHash").asText());
    }

    // ----- helpers -----

    private void assertRejected(String expectedPath, String definition) {
        assertThatThrownBy(() -> PolicySchema.toRuleSet("candidate", read(definition)))
                .as("should be rejected at %s: %s", expectedPath, definition)
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining(expectedPath);
    }

    private void assertAccepted(String definition) {
        assertThatCode(() -> PolicySchema.toRuleSet("candidate", read(definition)))
                .as("should be accepted: %s", definition)
                .doesNotThrowAnyException();
    }

    private static String amountRule(long amountMinor) {
        return "{\"operator\":\"AMOUNT_AT_LEAST\",\"amountMinor\":" + amountMinor + "}";
    }

    private static String allOf(int children) {
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < children; index++) {
            if (index > 0) body.append(',');
            body.append(amountRule(index + 1));
        }
        return "{\"operator\":\"ALL\",\"children\":[" + body + "]}";
    }

    /** Builds an expression whose deepest leaf sits at the given depth. */
    private static String nestedExpression(int depth) {
        String expression = amountRule(1);
        for (int level = 1; level < depth; level++) {
            expression = "{\"operator\":\"ALL\",\"children\":[" + expression + "]}";
        }
        return expression;
    }

    private static String countryCodes(int count) {
        StringBuilder codes = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) codes.append(',');
            codes.append('"').append((char) ('A' + index / 26)).append((char) ('A' + index % 26)).append('"');
        }
        return codes.toString();
    }

    private static String rule(String expression) {
        return rule(expression, "A candidate rule used for validation boundaries.");
    }

    private static String rule(String expression, String description) {
        return """
                {"rules":[{"code":"BOUNDARY","description":"%s","scoreContribution":60,
                 "flag":"HIGH_AMOUNT","terminal":false,"expression":%s}]}"""
                .formatted(description.replace("\\", "\\\\").replace("\"", "\\\""), expression);
    }

    private JsonNode read(String definition) {
        try {
            return json.readTree(definition);
        } catch (Exception unreadable) {
            throw new AssertionError("test definition is not valid JSON", unreadable);
        }
    }

    private record Reply(int status, JsonNode body) {}

    private Reply submit(String versionId, String definition, String auth) throws Exception {
        MvcResult result = mvc.perform(post("/v1/policies").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + versionId + "\",\"definition\":" + definition + "}"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Reply(result.getResponse().getStatus(), body.isBlank() ? json.nullNode() : json.readTree(body));
    }

    private boolean versionExists(String versionId) {
        return jdbc.queryForObject("SELECT count(*) FROM policy_versions WHERE version_id = ?", Long.class, versionId) > 0;
    }

    private long policyVersionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM policy_versions", Long.class);
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
