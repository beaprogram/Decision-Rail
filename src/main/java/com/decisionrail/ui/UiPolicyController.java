package com.decisionrail.ui;

import com.decisionrail.policy.PolicyService;
import com.decisionrail.policy.PolicyVersion;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * Policy browsing for merchants and administrators, and candidate registration for administrators.
 *
 * <p>Registration keeps the existing semantics exactly: an identical resubmission returns the stored
 * version with 200, and reusing a version identifier for different content is a 409 rather than a
 * silent rebinding. Nothing here edits or promotes an existing version, because neither operation exists.
 */
@RestController
@RequestMapping("/ui")
public class UiPolicyController {
    private final PolicyService policies;

    public UiPolicyController(PolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/policies")
    public List<PolicyVersion> list(@RequestParam(defaultValue = "50") int limit) {
        return policies.list(limit);
    }

    @GetMapping("/policies/{versionId}")
    public PolicyVersion get(@PathVariable String versionId) {
        return policies.require(versionId);
    }

    @PostMapping("/policies")
    public ResponseEntity<PolicyVersion> create(Principal principal, @Valid @RequestBody CreatePolicyRequest request) {
        PolicyService.Created created = policies.createCandidate(request.versionId(), request.definition(), principal.getName());
        return ResponseEntity.status(created.alreadyExisted() ? 200 : 201).body(created.version());
    }

    public record CreatePolicyRequest(
            @NotNull @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}") String versionId,
            @NotNull JsonNode definition) {}
}
