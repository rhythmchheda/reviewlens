package com.reviewlens.llm;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Shows the circuit state under /actuator/health. Reported as UP even when OPEN, on purpose:
 * the app still serves cached summaries, so a load balancer should not take the instance out.
 */
@Component("llmCircuit")
public class LlmCircuitHealthIndicator implements HealthIndicator {

    private final ResilientLlm llm;

    public LlmCircuitHealthIndicator(ResilientLlm llm) {
        this.llm = llm;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("provider", llm.name())
                .withDetail("circuit", llm.circuitState().name())
                .build();
    }
}
