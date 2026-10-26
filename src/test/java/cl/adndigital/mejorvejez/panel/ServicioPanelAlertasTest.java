package cl.adndigital.mejorvejez.panel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.FixturaAlertas;
import cl.adndigital.mejorvejez.alertas.RespuestaAlerta;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;

/**
 * Prueba las secciones "alertas de atraso" y "alertas preventivas" de
 * {@link ServicioPanel} (HU-10, Fase 6, Tarea 2) contra Oracle real
 * (Testcontainers), con el reloj de negocio fijo en el lunes 2026-11-02
 * 10:00 (America/Santiago), igual que {@link ServicioPanelTareasTest}.
 * Datos propios por prueba; nunca se cuentan tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioPanelAlertasTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);

    private static final OffsetDateTime AHORA = HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioPanel servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaTareas fixturaTareas;
    private FixturaPlanes fixturaPlanes;
    private FixturaAlertas fixturaAlertas;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Alertas de atraso (regla 3 de la Fase 6)
    // ------------------------------------------------------------------

    @Test
    void alertasCuentaAbiertasYCerradasUltimos7Dias() {
        Escenario e = escenario();

        // 2 ATRASO_TAREA abiertas: cuentan en "abiertas".
        crearAlertaDeTarea(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        crearAlertaDeTarea(e, CodigosAlertas.TIPO_ATRASO_TAREA);

        // 1 ATRASO_TAREA cerrada hace 2 días: cuenta en "cerradasUltimos7Dias".
        long idCerradaReciente = crearAlertaDeTarea(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        fixturaAlertas.cerrarAlertaConFecha(idCerradaReciente, AHORA.minusDays(2));

        // 1 ATRASO_TAREA cerrada hace 9 días: fuera de la ventana de 7 días, no cuenta.
        long idCerradaVieja = crearAlertaDeTarea(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        fixturaAlertas.cerrarAlertaConFecha(idCerradaVieja, AHORA.minusDays(9));

        // 1 CITA_PROXIMA abierta: no es ATRASO_TAREA, no debe contarse en esta sección.
        crearAlertaDeTarea(e, CodigosAlertas.TIPO_CITA_PROXIMA);

        RespuestaPanelAlertas respuesta = servicio.alertas(e.idGrupo, null);

        assertThat(respuesta.abiertas()).isEqualTo(2);
        assertThat(respuesta.cerradasUltimos7Dias()).isEqualTo(1);
    }

    @Test
    void recientesSonLas5AbiertasMasNuevas() {
        Escenario e = escenario();
        long[] ids = new long[7];
        for (int i = 0; i < ids.length; i++) {
            long idAlerta = crearAlertaDeTarea(e, CodigosAlertas.TIPO_ATRASO_TAREA);
            // Las más viejas primero: ids[0] queda a -7 min, ids[6] a -1 min de AHORA.
            jdbcTemplate.update("UPDATE ALERTA_CUIDADO SET FECHA_DETECCION = ? WHERE ID_ALERTA = ?",
                    AHORA.minusMinutes(ids.length - i), idAlerta);
            ids[i] = idAlerta;
        }

        RespuestaPanelAlertas respuesta = servicio.alertas(e.idGrupo, null);

        assertThat(respuesta.recientes()).extracting(RespuestaAlerta::idAlerta)
                .containsExactly(ids[6], ids[5], ids[4], ids[3], ids[2]);
    }

    // ------------------------------------------------------------------
    // Alertas preventivas (regla 6 de la Fase 6)
    // ------------------------------------------------------------------

    @Test
    void preventivasSoloAbiertasDeReposicionYCitaOrdenadasPorFechaProgramada() {
        Escenario e = escenario();
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Medicamento fixtura panel alertas");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        long idCitaLejana = crearAlertaDeTareaConProgramada(e, CodigosAlertas.TIPO_CITA_PROXIMA, AHORA.plusDays(10));
        long idReposicionCercana = crearAlertaDeDetalleConProgramada(e, idDetalle, AHORA.plusDays(1));
        long idCitaIntermedia = crearAlertaDeTareaConProgramada(e, CodigosAlertas.TIPO_CITA_PROXIMA, AHORA.plusDays(5));

        // No debe aparecer: REPOSICION_MEDICAMENTO cerrada.
        long idReposicionCerrada = fixturaAlertas.crearAlertaDeDetalle(e.idGrupo, e.idAdulto, idDetalle,
                FixturaAlertas.claveUnica());
        jdbcTemplate.update("UPDATE ALERTA_CUIDADO SET FECHA_PROGRAMADA = ? WHERE ID_ALERTA = ?", AHORA.plusDays(2),
                idReposicionCerrada);
        fixturaAlertas.cerrarAlertaConFecha(idReposicionCerrada, AHORA);

        // No debe aparecer: ATRASO_TAREA abierta (tipo incorrecto para esta sección).
        crearAlertaDeTarea(e, CodigosAlertas.TIPO_ATRASO_TAREA);

        RespuestaPanelPreventivas respuesta = servicio.alertasPreventivas(e.idGrupo, null);

        assertThat(respuesta.alertas()).extracting(RespuestaAlerta::idAlerta)
                .containsExactly(idReposicionCercana, idCitaIntermedia, idCitaLejana);
    }

    // ------------------------------------------------------------------
    // Filtro por adulto, aislamiento entre grupos y 404
    // ------------------------------------------------------------------

    @Test
    void filtroPorAdultoYAislamientoEntreGrupos() {
        Escenario a = escenario();
        long idAdultoDos = fixturaModelo.crearAdulto(a.idGrupo, a.idCoordinador);
        Escenario b = escenario();

        crearAlertaDeTarea(a, a.idAdulto, CodigosAlertas.TIPO_ATRASO_TAREA);
        crearAlertaDeTarea(a, idAdultoDos, CodigosAlertas.TIPO_ATRASO_TAREA);
        crearAlertaDeTarea(a, idAdultoDos, CodigosAlertas.TIPO_ATRASO_TAREA);
        crearAlertaDeTarea(b, b.idAdulto, CodigosAlertas.TIPO_ATRASO_TAREA);
        autenticar(a.uid);

        RespuestaPanelAlertas alertasAdultoUno = servicio.alertas(a.idGrupo, a.idAdulto);
        RespuestaPanelAlertas alertasAdultoDos = servicio.alertas(a.idGrupo, idAdultoDos);
        RespuestaPanelAlertas alertasGrupoCompleto = servicio.alertas(a.idGrupo, null);

        assertThat(alertasAdultoUno.abiertas()).isEqualTo(1);
        assertThat(alertasAdultoDos.abiertas()).isEqualTo(2);
        // Aislado del grupo B: 1 + 2, nunca la del otro grupo.
        assertThat(alertasGrupoCompleto.abiertas()).isEqualTo(3);
    }

    @Test
    void preventivasFiltranPorAdultoYAislanGrupos() {
        Escenario a = escenario();
        long idAdultoDos = fixturaModelo.crearAdulto(a.idGrupo, a.idCoordinador);
        Escenario b = escenario();

        long idAlertaUno = crearAlertaDeTarea(a, a.idAdulto, CodigosAlertas.TIPO_CITA_PROXIMA);
        long idAlertaDos = crearAlertaDeTarea(a, idAdultoDos, CodigosAlertas.TIPO_CITA_PROXIMA);
        crearAlertaDeTarea(b, b.idAdulto, CodigosAlertas.TIPO_CITA_PROXIMA);
        autenticar(a.uid);

        RespuestaPanelPreventivas preventivasAdultoUno = servicio.alertasPreventivas(a.idGrupo, a.idAdulto);
        RespuestaPanelPreventivas preventivasGrupoCompleto = servicio.alertasPreventivas(a.idGrupo, null);

        assertThat(preventivasAdultoUno.alertas()).extracting(RespuestaAlerta::idAlerta).containsExactly(idAlertaUno);
        // Aislado del grupo B: 1 + 1, nunca la del otro grupo.
        assertThat(preventivasGrupoCompleto.alertas()).extracting(RespuestaAlerta::idAlerta)
                .containsExactlyInAnyOrder(idAlertaUno, idAlertaDos);
    }

    @Test
    void adultoDeOtroGrupoDa404() {
        Escenario propio = escenario();
        Escenario ajeno = escenario();
        autenticar(propio.uid);

        assertThatThrownBy(() -> servicio.alertas(propio.idGrupo, ajeno.idAdulto))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
        assertThatThrownBy(() -> servicio.alertasPreventivas(propio.idGrupo, ajeno.idAdulto))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
    }

    // ------------------------------------------------------------------
    // Solo lectura y sin auditoría (reglas 7 y 8 de la Fase 6)
    // ------------------------------------------------------------------

    @Test
    void alertasNoAuditanParaAdministrador() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        autenticar(uidAdmin);

        servicio.alertas(idGrupo, idAdulto);
        servicio.alertasPreventivas(idGrupo, idAdulto);

        assertThat(contarEventosAuditoria(idGrupo)).isZero();
    }

    @Test
    void consultaNoModificaAlertasNiIntentos() {
        Escenario e = escenario();
        long idAlerta = crearAlertaDeTarea(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        fixturaAlertas.crearIntento(idAlerta, e.idCoordinador, "CORREO", 1, "ENVIADO");

        List<Map<String, Object>> alertasAntes = filasAlertasDelGrupo(e.idGrupo);
        List<Map<String, Object>> intentosAntes = fixturaAlertas.intentos(idAlerta);
        long eventosAntes = contarEventosAuditoria(e.idGrupo);

        servicio.alertas(e.idGrupo, null);
        servicio.alertasPreventivas(e.idGrupo, null);

        assertThat(filasAlertasDelGrupo(e.idGrupo)).isEqualTo(alertasAntes);
        assertThat(fixturaAlertas.intentos(idAlerta)).isEqualTo(intentosAntes);
        assertThat(contarEventosAuditoria(e.idGrupo)).isEqualTo(eventosAntes);
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private Escenario escenario() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        autenticar(uid);
        return new Escenario(uid, idCoordinador, idGrupo, idAdulto);
    }

    private record Escenario(String uid, long idCoordinador, long idGrupo, long idAdulto) {
    }

    private long crearAlertaDeTarea(Escenario e, String tipo) {
        return crearAlertaDeTarea(e, e.idAdulto, tipo);
    }

    private long crearAlertaDeTarea(Escenario e, long idAdulto, String tipo) {
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 8, 0));
        return fixturaAlertas.crearAlertaDeTarea(e.idGrupo, idAdulto, idTarea, tipo, FixturaAlertas.claveUnica());
    }

    private long crearAlertaDeTareaConProgramada(Escenario e, String tipo, OffsetDateTime fechaProgramada) {
        long idAlerta = crearAlertaDeTarea(e, tipo);
        jdbcTemplate.update("UPDATE ALERTA_CUIDADO SET FECHA_PROGRAMADA = ? WHERE ID_ALERTA = ?", fechaProgramada,
                idAlerta);
        return idAlerta;
    }

    private long crearAlertaDeDetalleConProgramada(Escenario e, long idDetalle, OffsetDateTime fechaProgramada) {
        long idAlerta = fixturaAlertas.crearAlertaDeDetalle(e.idGrupo, e.idAdulto, idDetalle,
                FixturaAlertas.claveUnica());
        jdbcTemplate.update("UPDATE ALERTA_CUIDADO SET FECHA_PROGRAMADA = ? WHERE ID_ALERTA = ?", fechaProgramada,
                idAlerta);
        return idAlerta;
    }

    private OffsetDateTime horario(LocalDate dia, int hora, int minuto) {
        return dia.atTime(hora, minuto).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private List<Map<String, Object>> filasAlertasDelGrupo(long idGrupo) {
        return jdbcTemplate.queryForList(
                "SELECT ID_ALERTA, ID_ESTADO, FECHA_REVISION FROM ALERTA_CUIDADO WHERE ID_GRUPO = ? "
                        + "ORDER BY ID_ALERTA",
                idGrupo);
    }

    private long contarEventosAuditoria(long idGrupo) {
        Long cantidad = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ?",
                Long.class, idGrupo);
        return cantidad == null ? 0 : cantidad;
    }

    private void autenticar(String uid) {
        Jwt jwt = Jwt.withTokenValue("token-de-prueba")
                .header("alg", "RS256")
                .subject(uid)
                .claim("email", uid + "@ejemplo.cl")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
