package com.decisionrail.api;

import com.decisionrail.replay.ReplayJobView;
import com.decisionrail.replay.ReplayReport;
import com.decisionrail.replay.ReplayResultView;
import com.decisionrail.replay.ReplayService;
import com.decisionrail.shadow.ShadowComparisonView;
import com.decisionrail.shadow.ShadowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant-facing replay jobs and shadow comparison reads.
 *
 * <p>Every route resolves the caller's merchant from authentication and passes it into the query.
 * Another merchant's job, result, or comparison is reported as not found rather than forbidden, so
 * these endpoints cannot be used to probe which identifiers exist.
 */
@RestController
@RequestMapping("/v1")
public class ReplayController {
    private final ReplayService replay;
    private final ShadowService shadow;

    public ReplayController(ReplayService replay, ShadowService shadow) {
        this.replay = replay;
        this.shadow = shadow;
    }

    /**
     * Creates a replay job against an existing immutable candidate version.
     *
     * <p>Uses an Idempotency-Key with its own request record, deliberately not the payment
     * idempotency model: that model stores a payment document as its replayed response, and a
     * replay job is not a payment. A repeated request returns the same job; a key reused for a
     * different request is a conflict.
     */
    @PostMapping("/replay-jobs")
    public ResponseEntity<ReplayJobView> create(Principal principal,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateReplayJobRequest request) {
        ReplayService.CreatedJob created = replay.create(principal.getName(), key,
                new ReplayService.CreateCommand(request.candidateVersion(), request.limit(), request.from()));
        return ResponseEntity.status(created.replayed() ? 200 : 201)
                .location(URI.create("/v1/replay-jobs/" + created.job().id()))
                .header("Idempotency-Replayed", Boolean.toString(created.replayed()))
                .body(created.job());
    }

    @GetMapping("/replay-jobs")
    public List<ReplayJobView> jobs(Principal principal, @RequestParam(defaultValue = "20") int limit) {
        return replay.jobs(principal.getName(), limit);
    }

    @GetMapping("/replay-jobs/{id}")
    public ReplayJobView job(Principal principal, @PathVariable UUID id) {
        return replay.job(principal.getName(), id);
    }

    /** Comparison report including the divergence rate and its stated denominator. */
    @GetMapping("/replay-jobs/{id}/report")
    public ReplayReport report(Principal principal, @PathVariable UUID id) {
        return replay.report(principal.getName(), id);
    }

    /** Per-payment baseline and candidate explanations. */
    @GetMapping("/replay-jobs/{id}/results")
    public List<ReplayResultView> results(Principal principal, @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean divergedOnly,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return replay.results(principal.getName(), id, divergedOnly, limit, offset);
    }

    @GetMapping("/payments/{id}/shadow")
    public List<ShadowComparisonView> paymentShadow(Principal principal, @PathVariable UUID id) {
        return shadow.comparisons(principal.getName(), id);
    }

    @GetMapping("/shadow-comparisons")
    public List<ShadowComparisonView> shadowComparisons(Principal principal,
            @RequestParam(defaultValue = "false") boolean divergedOnly,
            @RequestParam(defaultValue = "20") int limit) {
        return shadow.recent(principal.getName(), divergedOnly, limit);
    }

    /**
     * @param candidateVersion an existing immutable policy version
     * @param limit            bounded membership size, 1..5000, default 500
     * @param from             optional inclusive lower bound on payment creation time. The upper
     *                         bound is always the moment the job is created.
     */
    public record CreateReplayJobRequest(
            @NotNull @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}") String candidateVersion,
            @Min(1) @Max(5000) Integer limit,
            Instant from) {}
}
