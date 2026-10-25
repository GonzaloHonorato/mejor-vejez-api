package cl.adndigital.mejorvejez.salud;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ServicioSalud {

    static final String DISPONIBLE = "DISPONIBLE";
    static final String NO_DISPONIBLE = "NO_DISPONIBLE";
    static final String DEGRADADO = "DEGRADADO";

    private final DataSource dataSource;
    private final String servicio;
    private final String version;
    private final Clock reloj;

    public ServicioSalud(DataSource dataSource,
                         @Value("${spring.application.name}") String servicio,
                         @Value("${app.version:dev}") String version,
                         Clock reloj) {
        this.dataSource = dataSource;
        this.servicio = servicio;
        this.version = version;
        this.reloj = reloj;
    }

    public RespuestaSalud verificar() {
        RespuestaSalud.Componente baseDatos = verificarBaseDatos();
        String estado = DISPONIBLE.equals(baseDatos.estado()) ? DISPONIBLE : DEGRADADO;
        return new RespuestaSalud(estado, servicio, version, Instant.now(reloj), baseDatos);
    }

    private RespuestaSalud.Componente verificarBaseDatos() {
        long inicio = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean valida = connection.isValid(2);
            long latenciaMs = (System.nanoTime() - inicio) / 1_000_000;
            return new RespuestaSalud.Componente(valida ? DISPONIBLE : NO_DISPONIBLE, latenciaMs);
        } catch (Exception e) {
            // El detalle va al log, no a la respuesta: el endpoint es público.
            log.warn("Salud: la base de datos no responde: {}", e.getMessage());
            return new RespuestaSalud.Componente(NO_DISPONIBLE, null);
        }
    }
}
