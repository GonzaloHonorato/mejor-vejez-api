package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;

/**
 * Prueba {@link CreadorAlertas#crearReposicion} contra Oracle real
 * (Testcontainers), con el reloj fijo en el lunes 2026-11-02 10:00 de
 * Santiago. Vive en este paquete (y no en {@code interno}) porque la regla
 * 5 de abajo necesita interceptar {@link
 * RepositorioConsultaAlertas#planDeDetalle}, de paquete.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class CreadorAlertasTest {

    /** Lunes 2026-11-02 10:00 en Santiago (−03:00). */
    private static final OffsetDateTime AHORA = ZonedDateTime
            .of(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), ZonaNegocio.ZONA).toOffsetDateTime();

    /** "Hoy" en Santiago al momento de {@link #AHORA}. */
    private static final LocalDate HOY = AHORA.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();

    /** Ids de usuario explícitos > 127, en un rango propio. */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(983_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private CreadorAlertas creadorAlertas;

    @MockitoSpyBean
    private RepositorioConsultaAlertas consultaAlertas;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaPlanes fixturaPlanes;
    private FixturaAlertas fixturaAlertas;

    @BeforeEach
    void preparar() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
    }

    /**
     * Regla 5 de la Fase 5: el detalle se desactiva DESPUÉS de la
     * proyección sin bloqueo del plan ({@code planDeDetalle}) pero ANTES de
     * bloquearlo; la carga del detalle, bajo el bloqueo del plan, debe ver
     * su estado vigente (INACTIVO), no una foto de antes del bloqueo.
     */
    @Test
    void detalleDesactivadoDespuesDeLaConsultaNoGeneraReposicion() {
        long idCoordinador = insertarUsuarioAlto();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");
        long idDetalle = fixturaPlanes.crearDetalleConTermino(idPlan, fixturaPlanes.crearMedicamento("Losartán"),
                HOY.plusDays(1));
        doAnswer(invocacion -> {
            Optional<Long> idPlanLeido = (Optional<Long>) invocacion.callRealMethod();
            fixturaPlanes.cambiarEstadoDetalle(idDetalle, "INACTIVO");
            return idPlanLeido;
        }).when(consultaAlertas).planDeDetalle(idDetalle);

        Optional<Long> idAlerta = creadorAlertas.crearReposicion(idDetalle, HOY, AHORA);

        verify(consultaAlertas).planDeDetalle(eq(idDetalle));
        assertThat(idAlerta).isEmpty();
        assertThat(fixturaAlertas.alertasDelDetalle(idDetalle)).isEmpty();
    }

    private long insertarUsuarioAlto() {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 'N', (SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'USUARIO' AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = 'ACTIVO'))",
                idUsuario, FixturaGrupos.uidUnico(), "fixtura-" + UUID.randomUUID() + "@creador.mejorvejez.test",
                "Usuario " + idUsuario);
        return idUsuario;
    }
}
