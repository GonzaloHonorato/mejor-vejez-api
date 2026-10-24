package cl.adndigital.mejorvejez.health;

import java.sql.Connection;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class HealthService {

    static final String UP = "UP";
    static final String DOWN = "DOWN";
    static final String DEGRADED = "DEGRADED";

    private final DataSource dataSource;
    private final String service;
    private final String version;

    public HealthService(DataSource dataSource,
                         @Value("${spring.application.name}") String service,
                         @Value("${app.version:dev}") String version) {
        this.dataSource = dataSource;
        this.service = service;
        this.version = version;
    }

    public HealthResponse check() {
        HealthResponse.Component database = checkDatabase();
        String status = UP.equals(database.status()) ? UP : DEGRADED;
        return new HealthResponse(status, service, version, Instant.now(), database);
    }

    private HealthResponse.Component checkDatabase() {
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new HealthResponse.Component(valid ? UP : DOWN, latencyMs);
        } catch (Exception e) {
            // El detalle va al log, no a la respuesta: el endpoint es público.
            log.warn("Health: la base de datos no responde: {}", e.getMessage());
            return new HealthResponse.Component(DOWN, null);
        }
    }
}
