package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

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
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;

/**
 * Prueba {@link ServicioConsultaTareas} contra Oracle real (Testcontainers),
 * con el reloj de negocio fijo en el lunes 2026-11-02 10:00
 * (America/Santiago) vía un {@code Clock} {@code @Primary} de prueba, igual
 * que {@link cl.adndigital.mejorvejez.planificacion.ServicioDetallesPlanTest}.
 * Datos propios por prueba; nunca se cuentan tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioConsultaTareasTest {

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
    private ServicioConsultaTareas servicio;

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
    // Agenda del día, orden y responsable
    // ------------------------------------------------------------------

    @Test
    void listarDelDiaOrdenadoPorHoraConResponsable() {
        Escenario e = escenario();
        long idTardeMedia = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 14, 0));
        long idTemprano = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS, horario(HOY, 8, 0));
        long idTarde = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 20, 0));
        fixturaTareas.crearAsignacion(idTemprano, e.idGrupo, e.idCoordinador, e.idCoordinador);

        List<RespuestaTarea> agenda = servicio.listar(e.idGrupo, new FiltroTareas(null, null, null, null, false));

        assertThat(agenda).extracting(RespuestaTarea::idTarea).containsExactly(idTemprano, idTardeMedia, idTarde);
        RespuestaTarea conResponsable = agenda.get(0);
        assertThat(conResponsable.responsable()).isNotNull();
        assertThat(conResponsable.responsable().idUsuario()).isEqualTo(e.idCoordinador);
        assertThat(conResponsable.nombreAdulto()).isNotBlank();
        assertThat(agenda.get(1).responsable()).isNull();
        assertThat(agenda).allSatisfy(tarea -> assertThat(tarea.compra()).isEmpty());
    }

    // ------------------------------------------------------------------
    // Rango, filtros y límites
    // ------------------------------------------------------------------

    @Test
    void rangoYFiltros() {
        Escenario e = escenario();
        long idAdultoDos = fixturaModelo.crearAdulto(e.idGrupo, e.idCoordinador);

        LocalDate desde = HOY.plusDays(1);
        LocalDate hasta = HOY.plusDays(2);

        long idDentroAdultoUno = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS, desde.atTime(9, 0).atZone(ZonaNegocio.ZONA)
                        .toOffsetDateTime());
        long idDentroAdultoDos = fixturaTareas.crearTareaManual(e.idGrupo, idAdultoDos,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, desde.atTime(11, 0).atZone(ZonaNegocio.ZONA)
                        .toOffsetDateTime());
        long idCompletada = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, hasta.atTime(12, 0).atZone(ZonaNegocio.ZONA)
                        .toOffsetDateTime());
        fixturaTareas.cambiarEstadoTarea(idCompletada, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        long idBorde = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, hasta.atTime(23, 59).atZone(ZonaNegocio.ZONA)
                        .toOffsetDateTime());
        long idFueraDeRango = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, hasta.plusDays(1).atStartOfDay(ZonaNegocio.ZONA)
                        .toOffsetDateTime());
        long idAntesDelRango = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, desde.minusDays(1).atTime(23, 0)
                        .atZone(ZonaNegocio.ZONA).toOffsetDateTime());

        List<RespuestaTarea> sinFiltro = servicio.listar(e.idGrupo,
                new FiltroTareas(desde, hasta, null, null, false));
        assertThat(sinFiltro).extracting(RespuestaTarea::idTarea)
                .containsExactly(idDentroAdultoUno, idDentroAdultoDos, idCompletada, idBorde);
        assertThat(sinFiltro).extracting(RespuestaTarea::idTarea)
                .doesNotContain(idFueraDeRango, idAntesDelRango);

        List<RespuestaTarea> porAdulto = servicio.listar(e.idGrupo,
                new FiltroTareas(desde, hasta, e.idAdulto, null, false));
        assertThat(porAdulto).extracting(RespuestaTarea::idTarea)
                .containsExactly(idDentroAdultoUno, idCompletada, idBorde);

        long idEstadoCompletada = idEstado("TAREA_CUIDADO", "COMPLETADA");
        List<RespuestaTarea> porEstado = servicio.listar(e.idGrupo,
                new FiltroTareas(desde, hasta, null, idEstadoCompletada, false));
        assertThat(porEstado).extracting(RespuestaTarea::idTarea).containsExactly(idCompletada);

        fixturaTareas.crearAsignacion(idDentroAdultoUno, e.idGrupo, e.idCoordinador, e.idCoordinador);
        List<RespuestaTarea> soloMias = servicio.listar(e.idGrupo,
                new FiltroTareas(desde, hasta, null, null, true));
        assertThat(soloMias).extracting(RespuestaTarea::idTarea).containsExactly(idDentroAdultoUno);
    }

    @Test
    void rangoInvalidoYMayorA31Dias422() {
        Escenario e = escenario();

        assertThatThrownBy(() -> servicio.listar(e.idGrupo,
                new FiltroTareas(HOY.plusDays(5), HOY, null, null, false)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El rango de fechas no es válido");

        assertThatThrownBy(() -> servicio.listar(e.idGrupo,
                new FiltroTareas(HOY, HOY.plusDays(31), null, null, false)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El rango máximo es de 31 días");

        // 31 días exactos (el límite) no lanza.
        assertThat(servicio.listar(e.idGrupo, new FiltroTareas(HOY, HOY.plusDays(30), null, null, false)))
                .isEmpty();
    }

    @Test
    void estadoDeOtroDominio422() {
        Escenario e = escenario();
        long idEstadoDeOtroDominio = idEstado("PLAN_MEDICAMENTOS", "ACTIVO");

        assertThatThrownBy(() -> servicio.listar(e.idGrupo,
                new FiltroTareas(null, null, null, idEstadoDeOtroDominio, false)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El estado indicado no corresponde a TAREA_CUIDADO.ESTADO");
    }

    @Test
    void adultoDeOtroGrupoDevuelve404() {
        Escenario propio = escenario();
        Escenario ajeno = escenario();
        autenticar(propio.uid());

        assertThatThrownBy(() -> servicio.listar(propio.idGrupo,
                new FiltroTareas(null, null, ajeno.idAdulto, null, false)))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
    }

    // ------------------------------------------------------------------
    // Recursos ajenos y permisos
    // ------------------------------------------------------------------

    @Test
    void tareaDeOtroGrupo404() {
        Escenario propio = escenario();
        Escenario ajeno = escenario();
        long idTareaAjena = fixturaTareas.crearTareaManual(ajeno.idGrupo, ajeno.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        autenticar(propio.uid());

        assertThatThrownBy(() -> servicio.obtener(propio.idGrupo, idTareaAjena))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("La tarea no existe");
    }

    @Test
    void noMiembro403() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        String uidAjeno = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAjeno, false);
        autenticar(uidAjeno);

        assertThatThrownBy(() -> servicio.listar(e.idGrupo, new FiltroTareas(null, null, null, null, false)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.obtener(e.idGrupo, idTarea))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    // ------------------------------------------------------------------
    // Atrasada (HU-07, regla 12; reloj fijo en 2026-11-02 10:00)
    // ------------------------------------------------------------------

    @Test
    void atrasadaVsNoRealizada() {
        Escenario e = escenario();
        long idPendienteAtrasada = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 0));
        long idNoRealizada = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 0));
        fixturaTareas.cambiarEstadoTarea(idNoRealizada, CodigosTareas.ESTADO_TAREA_NO_REALIZADA);
        long idDentroDeTolerancia = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 9, 45));

        RespuestaTareaDetallada pendienteAtrasada = servicio.obtener(e.idGrupo, idPendienteAtrasada);
        RespuestaTareaDetallada noRealizada = servicio.obtener(e.idGrupo, idNoRealizada);
        RespuestaTareaDetallada dentroDeTolerancia = servicio.obtener(e.idGrupo, idDentroDeTolerancia);

        assertThat(pendienteAtrasada.tarea().atrasada()).isTrue();
        assertThat(noRealizada.tarea().atrasada()).isFalse();
        assertThat(dentroDeTolerancia.tarea().atrasada()).isFalse();
    }

    // ------------------------------------------------------------------
    // Detalle: historial y asignaciones en orden
    // ------------------------------------------------------------------

    @Test
    void detalleIncluyeHistorialYAsignacionesEnOrden() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        long idPendiente = idEstado("TAREA_CUIDADO", "PENDIENTE");
        long idEnCurso = idEstado("TAREA_CUIDADO", "EN_CURSO");

        OffsetDateTime t1 = HOY.minusDays(1).atTime(8, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime t2 = HOY.minusDays(1).atTime(9, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
        fixturaTareas.crearHistorial(idTarea, null, idPendiente, e.idCoordinador, null, t1);
        fixturaTareas.crearHistorial(idTarea, idPendiente, idEnCurso, e.idCoordinador, "Inicio de la tarea", t2);

        long idColaborador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(e.idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        OffsetDateTime a1 = HOY.minusDays(2).atTime(8, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime a2 = HOY.minusDays(1).atTime(8, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
        fixturaTareas.crearAsignacionConEstado(idTarea, e.idGrupo, idColaborador, e.idCoordinador, "REEMPLAZADA", a1,
                a2, "Responsable predeterminado del plan");
        fixturaTareas.crearAsignacionConEstado(idTarea, e.idGrupo, e.idCoordinador, e.idCoordinador, "ACTIVO", a2,
                null, "Reasignación");

        RespuestaTareaDetallada detalle = servicio.obtener(e.idGrupo, idTarea);

        assertThat(detalle.historial()).hasSize(2);
        assertThat(detalle.historial().get(0).idEstadoAnterior()).isNull();
        assertThat(detalle.historial().get(0).idEstadoNuevo()).isEqualTo(idPendiente);
        assertThat(detalle.historial().get(1).idEstadoAnterior()).isEqualTo(idPendiente);
        assertThat(detalle.historial().get(1).observacion()).isEqualTo("Inicio de la tarea");
        assertThat(detalle.historial()).extracting(RespuestaHistorialEstado::nombreUsuario).doesNotContainNull();

        assertThat(detalle.asignaciones()).hasSize(2);
        assertThat(detalle.asignaciones().get(0).idUsuarioAsignado()).isEqualTo(idColaborador);
        assertThat(detalle.asignaciones().get(0).motivo()).isEqualTo("Responsable predeterminado del plan");
        assertThat(detalle.asignaciones().get(1).idUsuarioAsignado()).isEqualTo(e.idCoordinador);
        assertThat(detalle.asignaciones()).extracting(RespuestaAsignacion::nombreAsignado).doesNotContainNull();
        assertThat(detalle.asignaciones()).extracting(RespuestaAsignacion::nombreAsignador).doesNotContainNull();

        // La tarea del detalle también está ensamblada (con el responsable ACTIVO vigente).
        assertThat(detalle.tarea().idTarea()).isEqualTo(idTarea);
        assertThat(detalle.tarea().responsable().idUsuario()).isEqualTo(e.idCoordinador);
    }

    // ------------------------------------------------------------------
    // Compra y cita (fixtura JDBC)
    // ------------------------------------------------------------------

    @Test
    void compraYCitaSeIncluyen() {
        Escenario e = escenario();
        long idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol fixtura tareas");
        long idPresentacion = opcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidad = opcion("UNIDAD_DOSIS", "COMPRIMIDO");

        long idTareaCompra = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS, horario(HOY, 9, 0));
        fixturaTareas.crearLineaCompra(idTareaCompra, idMedicamento, idPresentacion, "1 comprimido cada 8 horas",
                new BigDecimal("30"), idUnidad, "Retirar en farmacia habitual");

        long idTareaCita = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 15, 0));
        fixturaTareas.crearCita(idTareaCita, "Dra. Pérez", "Clínica Mejor Vejez", "Traer exámenes previos");

        RespuestaTareaDetallada detalleCompra = servicio.obtener(e.idGrupo, idTareaCompra);
        assertThat(detalleCompra.tarea().cita()).isNull();
        assertThat(detalleCompra.tarea().compra()).hasSize(1);
        RespuestaLineaCompra linea = detalleCompra.tarea().compra().get(0);
        assertThat(linea.idMedicamento()).isEqualTo(idMedicamento);
        assertThat(linea.nombreMedicamento()).startsWith("Paracetamol fixtura tareas");
        assertThat(linea.cantidadSolicitada()).isEqualByComparingTo("30");
        assertThat(linea.observaciones()).isEqualTo("Retirar en farmacia habitual");

        RespuestaTareaDetallada detalleCita = servicio.obtener(e.idGrupo, idTareaCita);
        assertThat(detalleCita.tarea().compra()).isEmpty();
        assertThat(detalleCita.tarea().cita()).isNotNull();
        assertThat(detalleCita.tarea().cita().nombreMedico()).isEqualTo("Dra. Pérez");
        assertThat(detalleCita.tarea().cita().lugarAtencion()).isEqualTo("Clínica Mejor Vejez");
        assertThat(detalleCita.tarea().cita().observaciones()).isEqualTo("Traer exámenes previos");
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
