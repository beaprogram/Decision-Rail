package com.decisionrail.config;

import com.decisionrail.decision.DecisionEngine;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfig {
    @Bean DecisionEngine decisionEngine() { return new DecisionEngine(); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
