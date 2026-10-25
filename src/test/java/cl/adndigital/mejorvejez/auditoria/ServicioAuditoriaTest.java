package cl.adndigital.mejorvejez.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.FiltroCorrelacion;

import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioAuditoria} contra Oracle real (Testcontainers).
 *
 * <p>{@code EVENTO_AUDITORIA} es append-only (el trigger de V3 bloquea
 * UPDATE/DELETE), así que estas pruebas no limpian lo que confirman: cada
 * una usa un ID de correlación único ({@link UUID#randomUUID()}) para
 * identificar su propia fila sin depender de borrarla ni de contar el total
 * de filas de la tabla.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioAuditoriaTest {

    @Autowired
    private ServicioAuditoria servicioAuditoria;

    @Autowired
    private RepositorioEventoAuditoria repositorio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    @AfterEach
    void limpiarMdc() {
        MDC.remove(FiltroCorrelacion.CLAVE_MDC_ID_CORRELACION);
    }

    @Test
    void registraEventoConCorrelacionDelMdc() {
        String idCorrelacion = "abc-" + UUID.randomUUID();
        MDC.put(FiltroCorrelacion.CLAVE_MDC_ID_CORRELACION, idCorrelacion);

        servicioAuditoria.registrar(new EventoARegistrar("servicio-prueba", "USUARIO", "1", null, null,
                "ACCION_PRUEBA", ResultadoAuditoria.EXITO, null));

        List<EventoAuditoria> eventos = repositorio.findByIdCorrelacion(idCorrelacion);
        assertThat(eventos).hasSize(1);

        long idResultadoExito = jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'EVENTO_AUDITORIA' AND NOMBRE_CAMPO = 'RESULTADO' "
                        + "AND CODIGO_ESTADO = 'EXITO'",
                Long.class);

        EventoAuditoria evento = eventos.get(0);
        assertThat(evento.getIdCorrelacion()).isEqualTo(idCorrelacion);
        assertThat(evento.getIdResultado()).isEqualTo(idResultadoExito);
        assertThat(evento.getNombreServicio()).isEqualTo("servicio-prueba");
        assertThat(evento.getCodigoAccion()).isEqualTo("ACCION_PRUEBA");
    }

    @Test
    void omiteClavesSensiblesEnDetalles() {
        String idCorrelacion = "detalles-" + UUID.randomUUID();
        MDC.put(FiltroCorrelacion.CLAVE_MDC_ID_CORRELACION, idCorrelacion);

        Map<String, Object> detalles = Map.of("nombre", "x", "idToken", "y", "clave", "z");
        servicioAuditoria.registrar(new EventoARegistrar("servicio-prueba", "USUARIO", "1", null, null,
                "ACCION_PRUEBA", ResultadoAuditoria.EXITO, detalles));

        EventoAuditoria evento = repositorio.findByIdCorrelacion(idCorrelacion).get(0);

        JsonMapper jsonMapper = JsonMapper.builder().build();
        Map<String, Object> detallesGuardados =
                jsonMapper.readValue(evento.getDetallesJson(), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                });

        assertThat(detallesGuardados).containsExactlyEntriesOf(Map.of("nombre", "x"));
    }

    @Test
    void registroEnTransaccionPropiaSobreviveRollback() {
        String idCorrelacion = "rollback-" + UUID.randomUUID();
        MDC.put(FiltroCorrelacion.CLAVE_MDC_ID_CORRELACION, idCorrelacion);

        EventoARegistrar evento = new EventoARegistrar("servicio-prueba", "USUARIO", "1", null, null,
                "ACCION_FALLIDA", ResultadoAuditoria.FALLO, null);

        TransactionTemplate plantillaTransaccion = new TransactionTemplate(gestorTransacciones);

        assertThrows(ExcepcionDePrueba.class, () -> plantillaTransaccion.executeWithoutResult(estado -> {
            servicioAuditoria.registrarEnTransaccionPropia(evento);
            throw new ExcepcionDePrueba();
        }));

        List<EventoAuditoria> eventos = repositorio.findByIdCorrelacion(idCorrelacion);
        assertThat(eventos).hasSize(1);
    }

    /** Excepción de prueba, solo para forzar el rollback de la transacción externa. */
    private static class ExcepcionDePrueba extends RuntimeException {
    }
}
