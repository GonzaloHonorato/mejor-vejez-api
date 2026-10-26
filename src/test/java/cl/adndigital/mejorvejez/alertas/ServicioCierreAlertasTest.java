package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.planificacion.ServicioDetallesPlan;
import cl.adndigital.mejorvejez.planificacion.SolicitudGuardarDetalle;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;
import cl.adndigital.mejorvejez.tareas.ServicioEstadoTareas;
import cl.adndigital.mejorvejez.tareas.SolicitudCambiarEstadoTarea;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioCierreAlertas} (TSA-47, TSA-9) contra Oracle real
 * (Testcontainers), con el reloj de negocio fijo en el lunes 2026-11-02
 * 10:00 (America/Santiago), igual que {@link ServicioConsultaAlertasTest}.
 * Datos propios por prueba; nunca se cuentan tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioCierreAlertasTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);
    private static final String HORARIO_08_20 =
            "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"08:00\",\"20:00\"]}";
    private static final String HORARIO_09 = "{\"version\":1,\"tipo\":\"HORAS_FIJAS\",\"horas\":[\"09:00\"]}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioCierreAlertas servicio;

    @Autowired
    private ServicioDetallesPlan servicioDetalles;

    @Autowired
    private ServicioEstadoTareas servicioEstadoTareas;

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
    // Validaciones (regla 13 de la Fase 5)
    // ------------------------------------------------------------------

    @Test
    void rechazaCierreSinObservacion() {
        Escenario e = escenario();
        long idTarea = tareaConAlerta(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        long idAlerta = ultimaAlertaDeTarea(idTarea);

        assertThatThrownBy(() -> servicio.cerrar(e.idGrupo, idAlerta, new SolicitudCerrarAlerta(null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La observación es obligatoria para cerrar la alerta.");
        assertThatThrownBy(() -> servicio.cerrar(e.idGrupo, idAlerta, new SolicitudCerrarAlerta("   ")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La observación es obligatoria para cerrar la alerta.");

        assertThat(fixturaAlertas.estadoAlerta(idAlerta)).isEqualTo("ABIERTA");
    }

    @Test
    void rechazaCierreNoAutorizado() {
        Escenario e = escenario();
        long idTarea = tareaConAlerta(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        long idAlerta = ultimaAlertaDeTarea(idTarea);
        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(e.idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        // Observación vacía (que también sería 422): el colaborador debe recibir 403, no 422.
        assertThatThrownBy(() -> servicio.cerrar(e.idGrupo, idAlerta, new SolicitudCerrarAlerta("")))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo un coordinador puede realizar esta acción");

        assertThat(contarRechazos("ACCESO_GRUPO", e.idGrupo, idColaborador)).isEqualTo(1);
        assertThat(fixturaAlertas.estadoAlerta(idAlerta)).isEqualTo("ABIERTA");
    }

    // ------------------------------------------------------------------
    // Cierre exitoso: no toca la tarea, guarda revisor/fecha/observación
    // ------------------------------------------------------------------

    @Test
    void cierraSinModificarLaTarea() {
        Escenario e = escenario();
        long idTarea = tareaConAlerta(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        long idAlerta = ultimaAlertaDeTarea(idTarea);

        RespuestaAlertaDetallada detalle = servicio.cerrar(e.idGrupo, idAlerta,
                new SolicitudCerrarAlerta("Se habló con la familia"));

        assertThat(detalle.alerta().idEstado()).isEqualTo(idEstado("ALERTA_CUIDADO", "CERRADA"));
        assertThat(detalle.alerta().revisor()).isNotNull();
        assertThat(detalle.alerta().revisor().idUsuario()).isEqualTo(e.idCoordinador);
        assertThat(detalle.alerta().fechaRevision()).isEqualTo(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA)
                .toOffsetDateTime());
        assertThat(detalle.alerta().observacionCierre()).isEqualTo("Se habló con la familia");

        // La tarea de origen (PENDIENTE) queda intacta: el cierre nunca la toca.
        assertThat(fixturaTareas.estadoTarea(idTarea)).isEqualTo("PENDIENTE");

        List<Map<String, Object>> eventos = eventosCierre(idAlerta);
        assertThat(eventos).hasSize(1);
        Map<String, Object> detallesJson = leerJson((String) eventos.get(0).get("DETALLES_JSON"));
        assertThat(detallesJson).doesNotContainKey("observacion").doesNotContainKey("accesoAdministrador")
                .containsEntry("tipoAlerta", CodigosAlertas.TIPO_ATRASO_TAREA)
                .containsEntry("idTarea", (int) idTarea);
    }

    @Test
    void dobleCierreDa409() {
        Escenario e = escenario();
        long idTarea = tareaConAlerta(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        long idAlerta = ultimaAlertaDeTarea(idTarea);
        servicio.cerrar(e.idGrupo, idAlerta, new SolicitudCerrarAlerta("Primer cierre"));

        assertThatThrownBy(() -> servicio.cerrar(e.idGrupo, idAlerta, new SolicitudCerrarAlerta("Segundo intento")))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("La alerta ya está cerrada.");
    }

    @Test
    void observacionDeMasDe500Da422() {
        Escenario e = escenario();

        long idTareaCaracteres = tareaConAlerta(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        long idAlertaCaracteres = ultimaAlertaDeTarea(idTareaCaracteres);
        assertThatThrownBy(() -> servicio.cerrar(e.idGrupo, idAlertaCaracteres,
                new SolicitudCerrarAlerta("x".repeat(501))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La observación no puede superar los 500 caracteres.");
        assertThat(fixturaAlertas.estadoAlerta(idAlertaCaracteres)).isEqualTo("ABIERTA");

        // 400 caracteres de "€" (3 bytes UTF-8 cada uno): 1200 bytes, supera el límite de bytes
        // aunque no supere los 500 caracteres.
        long idTareaBytes = tareaConAlerta(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        long idAlertaBytes = ultimaAlertaDeTarea(idTareaBytes);
        assertThatThrownBy(() -> servicio.cerrar(e.idGrupo, idAlertaBytes,
                new SolicitudCerrarAlerta("€".repeat(400))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La observación no puede superar los 500 caracteres.");
        assertThat(fixturaAlertas.estadoAlerta(idAlertaBytes)).isEqualTo("ABIERTA");

        // Borde exacto: 500 caracteres se acepta (el límite es "hasta 500", no "menos de 500").
        long idTareaBorde = tareaConAlerta(e, CodigosAlertas.TIPO_ATRASO_TAREA);
        long idAlertaBorde = ultimaAlertaDeTarea(idTareaBorde);
        RespuestaAlertaDetallada detalle = servicio.cerrar(e.idGrupo, idAlertaBorde,
                new SolicitudCerrarAlerta("x".repeat(500)));
        assertThat(detalle.alerta().observacionCierre()).hasSize(500);
    }

    @Test
    void administradorSinMembresiaCierraConTrazabilidad() {
        Escenario e = escenario();
        long idTarea = tareaConAlerta(e, CodigosAlertas.TIPO_CITA_PROXIMA);
        long idAlerta = ultimaAlertaDeTarea(idTarea);
        String uidAdministrador = FixturaGrupos.uidUnico();
        long idAdministrador = fixturaGrupos.crearUsuario(uidAdministrador, true);
        autenticar(uidAdministrador);

        RespuestaAlertaDetallada detalle = servicio.cerrar(e.idGrupo, idAlerta,
                new SolicitudCerrarAlerta("Cierre administrativo"));

        assertThat(detalle.alerta().revisor().idUsuario()).isEqualTo(idAdministrador);

        List<Map<String, Object>> eventos = eventosCierre(idAlerta);
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idAdministrador);
        assertThat(leerJson((String) eventos.get(0).get("DETALLES_JSON")))
                .containsEntry("accesoAdministrador", true);
    }

    // ------------------------------------------------------------------
    // Nada más cierra una alerta (regla 13): ni la cancelación de una tarea
    // manual ni el recálculo de F4 por un cambio de plan.
    // ------------------------------------------------------------------

    @Test
    void cancelarLaTareaOCambiarElPlanNoCierraLaAlerta() {
        Escenario e = escenario();

        // Sub-caso 1: cancelación manual de una cita por el coordinador (HU-07, regla 11 de la Fase 4).
        long idTareaManual = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 0));
        long idAlertaManual = fixturaAlertas.crearAlertaDeTarea(e.idGrupo, e.idAdulto, idTareaManual,
                CodigosAlertas.TIPO_CITA_PROXIMA, FixturaAlertas.claveUnica());
        long idEstadoCancelada = idEstado("TAREA_CUIDADO", "CANCELADA");

        servicioEstadoTareas.cambiarEstado(e.idGrupo, idTareaManual,
                new SolicitudCambiarEstadoTarea(idEstadoCancelada, "El paciente ya no requiere la cita"));

        assertThat(fixturaTareas.estadoTarea(idTareaManual)).isEqualTo("CANCELADA");
        assertThat(fixturaAlertas.estadoAlerta(idAlertaManual)).isEqualTo("ABIERTA");

        // Sub-caso 2: recálculo síncrono de F4 por un cambio de horario del plan (regla 8 de la Fase 4).
        long idPlan = fixturaPlanes.crearPlanConVigencia(e.idGrupo, e.idAdulto, e.idCoordinador, HOY, null);
        long idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol fixtura alertas");
        long idDetalle = servicioDetalles.crear(e.idGrupo, idPlan, solicitudDetalle(idMedicamento, HORARIO_08_20))
                .idDetallePlan();
        long idTareaDeMedicamento = primeraTareaFutura(idDetalle);
        long idAlertaDeMedicamento = fixturaAlertas.crearAlertaDeTarea(e.idGrupo, e.idAdulto, idTareaDeMedicamento,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());

        servicioDetalles.actualizar(e.idGrupo, idPlan, idDetalle, solicitudDetalle(idMedicamento, HORARIO_09));

        assertThat(fixturaTareas.estadoTarea(idTareaDeMedicamento)).isEqualTo("CANCELADA");
        assertThat(fixturaAlertas.estadoAlerta(idAlertaDeMedicamento)).isEqualTo("ABIERTA");
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

    /** Tarea manual PENDIENTE del escenario, con una alerta ABIERTA del tipo indicado. */
    private long tareaConAlerta(Escenario e, String tipoAlerta) {
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        fixturaAlertas.crearAlertaDeTarea(e.idGrupo, e.idAdulto, idTarea, tipoAlerta, FixturaAlertas.claveUnica());
        return idTarea;
    }

    private long ultimaAlertaDeTarea(long idTarea) {
        return jdbcTemplate.queryForObject(
                "SELECT MAX(ID_ALERTA) FROM ALERTA_CUIDADO WHERE ID_TAREA = ?", Long.class, idTarea);
    }

    /** La tarea futura más próxima del detalle (la primera que el recálculo cancelará). */
    private long primeraTareaFutura(long idDetalle) {
        return jdbcTemplate.queryForList(
                "SELECT ID_TAREA FROM TAREA_CUIDADO WHERE ID_DETALLE_PLAN = ? ORDER BY FECHA_HORARIO, ID_TAREA",
                Long.class, idDetalle).get(0);
    }

    /** Solicitud de detalle válida: diario, dosis 1, desde {@code HOY}, sin término, con el horario indicado. */
    private SolicitudGuardarDetalle solicitudDetalle(long idMedicamento, String horarioJson) {
        List<Long> dias = List.of("LUNES", "MARTES", "MIERCOLES", "JUEVES", "VIERNES", "SABADO", "DOMINGO").stream()
                .map(dia -> opcion("DIA_SEMANA", dia)).toList();
        return new SolicitudGuardarDetalle(idMedicamento, opcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO"),
                "500 mg", "Con comida", BigDecimal.ONE, opcion("UNIDAD_DOSIS", "COMPRIMIDO"), dias,
                JSON.readTree(horarioJson), HOY, null, null, null, "AUTOMATICO", null, null);
    }

    private OffsetDateTime horario(LocalDate dia, int hora, int minuto) {
        return dia.atTime(hora, minuto).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private List<Map<String, Object>> eventosCierre(long idAlerta) {
        return jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, DETALLES_JSON FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'ALERTA_CUIDADO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = ?",
                String.valueOf(idAlerta), AccionesAlertas.CERRAR_ALERTA);
    }

    private Map<String, Object> leerJson(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    /** Eventos RECHAZADA con la acción, grupo y actor indicados (mismo patrón que ServicioAutorizacionTest). */
    private int contarRechazos(String codigoAccion, long idGrupo, long idActor) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA e "
                        + "JOIN CATALOGO_ESTADOS r ON r.ID_ESTADO = e.ID_RESULTADO "
                        + "WHERE e.CODIGO_ACCION = ? AND e.ID_USUARIO_ACTOR = ? AND e.ID_GRUPO = ? "
                        + "AND r.CODIGO_ESTADO = 'RECHAZADA'",
                Integer.class, codigoAccion, idActor, idGrupo);
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private long opcion(String tipo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?",
                Long.class, tipo, codigo);
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
