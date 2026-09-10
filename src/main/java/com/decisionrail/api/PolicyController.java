package com.decisionrail.api;

import com.decisionrail.policy.PolicyService;
import com.decisionrail.policy.PolicyVersion;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate policy registration and lookup.
 *
 * <p>Creation requires the ADMIN identity. Reads are open to merchants as well, because a merchant
 * needs to name a candidate version when it creates a replay job. Policy versions contain no
 * tenant data: they are rule definitions, identical for every merchant.
 */
@RestController
@RequestMapping("/v1/policies")
public class PolicyController {
    private final PolicyService policies;

    public PolicyController(PolicyService policies) {
        this.policies = policies;
    }

    /**
     * Registers an immutable candidate policy version.
     *
     * <p>Safe to retry without an idempotency key, because content identity does the work: the same
     * version id with the same definition returns 200 and the existing version, while the same id
     * with a different definition is a 409 rather than a silent rebinding.
     */
    @PostMapping
    public ResponseEntity<PolicyVersion> create(Principal principal, @Valid @RequestBody CreatePolicyRequest request) {
        PolicyService.Created created = policies.createCandidate(request.versionId(), request.definition(), principal.getName());
        return ResponseEntity.status(created.alreadyExisted() ? 200 : 201)
                .location(URI.create("/v1/policies/" + created.version().versionId()))
                .body(created.version());
    }

    @GetMapping
    public List<PolicyVersion> list(@RequestParam(defaultValue = "50") int limit) {
        return policies.list(limit);
    }

    @GetMapping("/{versionId}")
    public PolicyVersion get(@PathVariable String versionId) {
        return policies.require(versionId);
    }

    /**
     * @param definition the policy document. Its schema, operators, and bounds are documented on
     *                   the policy schema translator and in the OpenAPI contract.
     */
    public record CreatePolicyRequest(
            @NotNull @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}") String versionId,
            @NotNull JsonNode definition) {}
}
