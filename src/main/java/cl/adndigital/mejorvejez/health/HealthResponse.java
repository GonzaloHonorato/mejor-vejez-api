package cl.adndigital.mejorvejez.health;

import java.time.Instant;

public record HealthResponse(
        String status,
        String service,
        String version,
        Instant timestamp,
        Component database) {

    public record Component(String status, Long latencyMs) {
    }
}
