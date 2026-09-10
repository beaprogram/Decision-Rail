package com.decisionrail.policy;

import com.decisionrail.decision.DecisionEngine;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Records the built-in policy once migrations have run, so replay and shadow reports can name a
 * baseline version with a real content hash rather than a bare string.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PolicyStartup implements ApplicationRunner {
    private final PolicyService policies;
    private final DecisionEngine engine;

    public PolicyStartup(PolicyService policies, DecisionEngine engine) {
        this.policies = policies;
        this.engine = engine;
    }

    @Override
    public void run(ApplicationArguments args) {
        policies.registerBuiltinPolicy(engine);
    }
}
