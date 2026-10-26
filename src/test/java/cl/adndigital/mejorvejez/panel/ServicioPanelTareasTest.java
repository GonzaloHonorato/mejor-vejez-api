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
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;
import cl.adndigital.mejorvejez.tareas.RespuestaTarea;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioPanel} contra Oracle real (Testcontainers), con el
 * reloj de negocio fijo en el lunes 2026-11-02 10:00 (America/Santiago), vía
 * un {@code Clock} {@code @Primary} de prueba, igual que {@link
 * cl.adndigital.mejorvejez.tareas.ServicioConsultaTareasTest}. Datos propios
 * por prueba; nunca se cuentan tablas completas (la BD es compartida).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioPanelTareasTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);

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

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Tareas de hoy: conteos y atrasadas
    // ------------------------------------------------------------------

    @Test
    void conteosDeHoyEnSantiago() {
        Escenario e = escenario();

        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 9, 0));
        long idEnCurso = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 0));
        fixturaTareas.cambiarEstadoTarea(idEnCurso, CodigosTareas.ESTADO_TAREA_EN_CURSO);
        long idCompletadaHoy = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 0));
        fixturaTareas.cambiarEstadoTarea(idCompletadaHoy, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        long idNoRealizada = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 0));
        fixturaTareas.cambiarEstadoTarea(idNoRealizada, CodigosTareas.ESTADO_TAREA_NO_REALIZADA);
        long idCancelada = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 0));
        fixturaTareas.cambiarEstadoTarea(idCancelada, CodigosTareas.ESTADO_TAREA_CANCELADA);

        // Otra PENDIENTE a las 23:30 de hoy: también cuenta en "pendientes".
        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 23, 30));
        // Fuera del rango de hoy: no debe contarse.
        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY.plusDays(1), 0, 10));
        long idCompletadaAyer = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY.minusDays(1), 12, 0));
        fixturaTareas.cambiarEstadoTarea(idCompletadaAyer, CodigosTareas.ESTADO_TAREA_COMPLETADA);

        RespuestaPanelTareas respuesta = servicio.tareas(e.idGrupo, null);

        assertThat(respuesta.fecha()).isEqualTo(HOY);
        assertThat(respuesta.pendientes()).isEqualTo(2);
        assertThat(respuesta.enCurso()).isEqualTo(1);
        assertThat(respuesta.completadas()).isEqualTo(1);
        assertThat(respuesta.noRealizadas()).isEqualTo(1);
        assertThat(respuesta.canceladas()).isEqualTo(1);
    }

    @Test
    void atrasadasRespetanToleranciaYSieteDias() {
        Escenario e = escenario();

        // Entran: PENDIENTE de las 09:00 (límite 09:30 < 10:00) y EN_CURSO de hace 3 días.
        long idPendienteAtrasada = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 0));
        long idEnCursoHace3Dias = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY.minusDays(3), 9, 0));
        fixturaTareas.cambiarEstadoTarea(idEnCursoHace3Dias, CodigosTareas.ESTADO_TAREA_EN_CURSO);

        // No entran: límite exacto 10:00, hace 8 días (fuera de la retrospectiva) y una COMPLETADA.
        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 9, 30));
        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY.minusDays(8), 9, 0));
        long idCompletada0800 = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        fixturaTareas.cambiarEstadoTarea(idCompletada0800, CodigosTareas.ESTADO_TAREA_COMPLETADA);

        RespuestaPanelTareas respuesta = servicio.tareas(e.idGrupo, null);

        assertThat(respuesta.atrasadas()).isEqualTo(2);
        assertThat(respuesta.tareasAtrasadas()).extracting(RespuestaTarea::idTarea)
                .containsExactly(idEnCursoHace3Dias, idPendienteAtrasada);
    }

    @Test
    void filtroPorAdultoSoloCuentaEseAdulto() {
        Escenario e = escenario();
        long idAdultoDos = fixturaModelo.crearAdulto(e.idGrupo, e.idCoordinador);

        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 9, 0));
        fixturaTareas.crearTareaManual(e.idGrupo, idAdultoDos, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 9, 0));
        fixturaTareas.crearTareaManual(e.idGrupo, idAdultoDos, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 9, 30));

        RespuestaPanelTareas respuestaUno = servicio.tareas(e.idGrupo, e.idAdulto);
        RespuestaPanelTareas respuestaDos = servicio.tareas(e.idGrupo, idAdultoDos);

        assertThat(respuestaUno.pendientes()).isEqualTo(1);
        assertThat(respuestaDos.pendientes()).isEqualTo(2);
    }

    @Test
    void adultoDeOtroGrupoDa404() {
        Escenario propio = escenario();
        Escenario ajeno = escenario();
        autenticar(propio.uid());

        assertThatThrownBy(() -> servicio.tareas(propio.idGrupo, ajeno.idAdulto))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
        assertThatThrownBy(() -> servicio.proximosHorarios(propio.idGrupo, ajeno.idAdulto))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
        assertThatThrownBy(() -> servicio.citas(propio.idGrupo, ajeno.idAdulto))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
        assertThatThrownBy(() -> servicio.tareas(propio.idGrupo, 999_999_999L))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
    }

    @Test
    void aislamientoEntreGrupos() {
        Escenario a = escenario();
        Escenario b = escenario();

        fixturaTareas.crearTareaManual(b.idGrupo, b.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 9, 0));
        fixturaTareas.crearTareaManual(b.idGrupo, b.idAdulto, CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS,
                horario(HOY, 12, 0));
        fixturaTareas.crearTareaManual(b.idGrupo, b.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY.plusDays(5), 10, 0));
        autenticar(a.uid());

        RespuestaPanelTareas tareasA = servicio.tareas(a.idGrupo, null);
        RespuestaPanelHorarios horariosA = servicio.proximosHorarios(a.idGrupo, null);
        RespuestaPanelCitas citasA = servicio.citas(a.idGrupo, null);

        assertThat(tareasA.pendientes()).isZero();
        assertThat(tareasA.enCurso()).isZero();
        assertThat(tareasA.completadas()).isZero();
        assertThat(tareasA.noRealizadas()).isZero();
        assertThat(tareasA.canceladas()).isZero();
        assertThat(tareasA.atrasadas()).isZero();
        assertThat(tareasA.tareasAtrasadas()).isEmpty();
        assertThat(horariosA.tareas()).isEmpty();
        assertThat(citasA.citas()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Próximos horarios (24 h) y citas (14 días)
    // ------------------------------------------------------------------

    @Test
    void proximosHorariosSolo24hYTiposCorrectos() {
        Escenario e = escenario();
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Medicamento fixtura panel");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");

        // Entran: MEDICAMENTOS en 2 h y COMPRA_RETIRO_MEDICAMENTOS en 20 h.
        long idMedEn2h = fixturaPlanes.crearTareaDeMedicamento(e.idGrupo, e.idAdulto, idDetalle, horario(HOY, 12, 0));
        long idCompraEn20h = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS, horario(HOY.plusDays(1), 6, 0));

        // No entran: una cita en 3 h, un MEDICAMENTOS en 25 h y una COMPLETADA en 1 h.
        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 13, 0));
        fixturaPlanes.crearTareaDeMedicamento(e.idGrupo, e.idAdulto, idDetalle, horario(HOY.plusDays(1), 11, 0));
        long idCompletadaEn1h = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS, horario(HOY, 11, 0));
        fixturaTareas.cambiarEstadoTarea(idCompletadaEn1h, CodigosTareas.ESTADO_TAREA_COMPLETADA);

        RespuestaPanelHorarios respuesta = servicio.proximosHorarios(e.idGrupo, null);

        assertThat(respuesta.tareas()).extracting(RespuestaTarea::idTarea).containsExactly(idMedEn2h, idCompraEn20h);
    }

    @Test
    void citasProximos14DiasConMedicoYLugar() {
        Escenario e = escenario();
        long idCitaProxima = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY.plusDays(5), 10, 0));
        fixturaTareas.crearCita(idCitaProxima, "Dra. Pérez", "Clínica Mejor Vejez", "Traer exámenes previos");
        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY.plusDays(15), 10, 0));

        RespuestaPanelCitas respuesta = servicio.citas(e.idGrupo, null);

        assertThat(respuesta.citas()).extracting(RespuestaTarea::idTarea).containsExactly(idCitaProxima);
        RespuestaTarea tarea = respuesta.citas().get(0);
        assertThat(tarea.cita()).isNotNull();
        assertThat(tarea.cita().nombreMedico()).isEqualTo("Dra. Pérez");
        assertThat(tarea.cita().lugarAtencion()).isEqualTo("Clínica Mejor Vejez");
    }

    @Test
    void tomaDentroDeLaToleranciaApareceEnProximos() {
        Escenario e = escenario();
        // 09:45 + 30 min (tolerancia por defecto) = 10:15 >= 10:00: todavía no está atrasada,
        // pero 09:45 < 10:00 (ahora), así que con el borde inferior viejo quedaba en ningún lado.
        long idDentroDeTolerancia = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS, horario(HOY, 9, 45));

        RespuestaPanelHorarios respuesta = servicio.proximosHorarios(e.idGrupo, null);
        RespuestaPanelTareas resumen = servicio.tareas(e.idGrupo, null);

        assertThat(respuesta.tareas()).extracting(RespuestaTarea::idTarea).contains(idDentroDeTolerancia);
        assertThat(resumen.tareasAtrasadas()).extracting(RespuestaTarea::idTarea)
                .doesNotContain(idDentroDeTolerancia);
    }

    @Test
    void citaDentroDeLaToleranciaApareceEnProximos() {
        Escenario e = escenario();
        // Misma ventana de tolerancia que las tareas de horarios, pero para citas.
        long idCitaDentroDeTolerancia = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 45));

        RespuestaPanelCitas respuesta = servicio.citas(e.idGrupo, null);
        RespuestaPanelTareas resumen = servicio.tareas(e.idGrupo, null);

        assertThat(respuesta.citas()).extracting(RespuestaTarea::idTarea).contains(idCitaDentroDeTolerancia);
        assertThat(resumen.tareasAtrasadas()).extracting(RespuestaTarea::idTarea)
                .doesNotContain(idCitaDentroDeTolerancia);
    }

    @Test
    void listasLimitadasA10() {
        Escenario e = escenario();
        for (int i = 0; i < 12; i++) {
            fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                    horario(HOY.plusDays(1 + i), 9, 0));
        }

        RespuestaPanelCitas respuesta = servicio.citas(e.idGrupo, null);

        assertThat(respuesta.citas()).hasSize(10);
    }

    // ------------------------------------------------------------------
    // Solo lectura y auditoría (regla 7 y regla 8 de la Fase 6)
    // ------------------------------------------------------------------

    @Test
    void consultaNoModificaNada() {
        Escenario e = escenario();
        fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario(HOY, 9, 0));

        List<Map<String, Object>> antes = filasTareasDelGrupo(e.idGrupo);
        long eventosAntes = contarEventosAuditoria(e.idGrupo);

        servicio.tareas(e.idGrupo, null);
        servicio.proximosHorarios(e.idGrupo, null);
        servicio.citas(e.idGrupo, null);

        List<Map<String, Object>> despues = filasTareasDelGrupo(e.idGrupo);
        long eventosDespues = contarEventosAuditoria(e.idGrupo);

        assertThat(despues).isEqualTo(antes);
        assertThat(eventosDespues).isEqualTo(eventosAntes);
    }

    @Test
    void administradorSinMembresiaAuditaSoloTareas() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        autenticar(uidAdmin);

        servicio.tareas(idGrupo, idAdulto);
        servicio.proximosHorarios(idGrupo, idAdulto);
        servicio.citas(idGrupo, idAdulto);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'CONSULTAR_PANEL'",
                idGrupo);
        assertThat(eventos).hasSize(1);

        String detallesJson = (String) eventos.get(0).get("DETALLES_JSON");
        Map<String, Object> detalles = JsonMapper.builder().build()
                .readValue(detallesJson, new TypeReference<Map<String, Object>>() {
                });
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
        assertThat(((Number) detalles.get("idAdulto")).longValue()).isEqualTo(idAdulto);
    }

    @Test
    void noMiembroDa403() {
        Escenario e = escenario();
        String uidAjeno = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAjeno, false);
        autenticar(uidAjeno);

        assertThatThrownBy(() -> servicio.tareas(e.idGrupo, null)).isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.proximosHorarios(e.idGrupo, null))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.citas(e.idGrupo, null)).isInstanceOf(ExcepcionAccesoDenegado.class);
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

    private OffsetDateTime horario(LocalDate dia, int hora, int minuto) {
        return dia.atTime(hora, minuto).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private List<Map<String, Object>> filasTareasDelGrupo(long idGrupo) {
        return jdbcTemplate.queryForList(
                "SELECT ID_TAREA, ID_ESTADO, FECHA_HORARIO FROM TAREA_CUIDADO WHERE ID_GRUPO = ? ORDER BY ID_TAREA",
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
