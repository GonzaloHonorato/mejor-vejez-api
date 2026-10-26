package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;

/**
 * Prueba {@link ServicioTareasManuales} contra Oracle real (Testcontainers),
 * con el reloj de negocio fijo en el lunes 2026-11-02 10:00
 * (America/Santiago), igual que {@link ServicioAsignacionTareasTest}. Datos
 * propios por prueba; nunca se cuentan tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioTareasManualesTest {

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
    private ServicioTareasManuales servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaPlanes fixturaPlanes;
    private FixturaGeneracion fixturaGeneracion;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        fixturaGeneracion = new FixturaGeneracion(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Compra con varias líneas
    // ------------------------------------------------------------------

    @Test
    void compraConVariosMedicamentos() {
        Escenario e = escenario();
        long idMedicamentoUno = fixturaPlanes.crearMedicamento("Paracetamol");
        long idMedicamentoDos = fixturaPlanes.crearMedicamento("Ibuprofeno");
        long idMedicamentoTres = fixturaPlanes.crearMedicamento("Omeprazol");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidad = idOpcion("UNIDAD_DOSIS", "COMPRIMIDO");

        List<SolicitudLineaCompra> lineas = List.of(
                new SolicitudLineaCompra(idMedicamentoUno, idPresentacion, "1 comprimido", BigDecimal.TEN, idUnidad,
                        null),
                new SolicitudLineaCompra(idMedicamentoDos, idPresentacion, null, BigDecimal.ONE, idUnidad,
                        "Con receta"),
                new SolicitudLineaCompra(idMedicamentoTres, idPresentacion, null, BigDecimal.valueOf(2), idUnidad,
                        null));

        RespuestaTarea respuesta = servicio.crear(e.idGrupo, solicitudCompra(e, lineas));

        assertThat(respuesta.tipoTarea()).isEqualTo(CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS);
        assertThat(respuesta.idDetallePlan()).isNull();
        assertThat(respuesta.cita()).isNull();
        assertThat(respuesta.compra()).hasSize(3);
        assertThat(respuesta.compra().get(0).idMedicamento()).isEqualTo(idMedicamentoUno);
        assertThat(respuesta.compra().get(1).idMedicamento()).isEqualTo(idMedicamentoDos);
        assertThat(respuesta.compra().get(1).observaciones()).isEqualTo("Con receta");
        assertThat(respuesta.compra().get(2).idMedicamento()).isEqualTo(idMedicamentoTres);
    }

    // ------------------------------------------------------------------
    // Cita con médico y lugar
    // ------------------------------------------------------------------

    @Test
    void citaConMedicoYLugar() {
        Escenario e = escenario();

        RespuestaTarea respuesta = servicio.crear(e.idGrupo,
                solicitudCita(e, new SolicitudCita("Dra. Pérez", "Clínica Central", "Control trimestral")));

        assertThat(respuesta.tipoTarea()).isEqualTo(CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS);
        assertThat(respuesta.idDetallePlan()).isNull();
        assertThat(respuesta.compra()).isEmpty();
        assertThat(respuesta.cita()).isNotNull();
        assertThat(respuesta.cita().nombreMedico()).isEqualTo("Dra. Pérez");
        assertThat(respuesta.cita().lugarAtencion()).isEqualTo("Clínica Central");
        assertThat(respuesta.cita().observaciones()).isEqualTo("Control trimestral");
    }

    // ------------------------------------------------------------------
    // Offset de las respuestas (ruling final de F4)
    // ------------------------------------------------------------------

    /**
     * Toda respuesta de tareas devuelve sus instantes con el offset de
     * America/Santiago en ese instante, aunque el cliente los envíe en UTC
     * (Jackson los deja en Z al deserializar).
     */
    @Test
    void respuestaDevuelveFechaHorarioConOffsetDeSantiago() {
        Escenario e = escenario();
        SolicitudCrearTareaManual solicitud = new SolicitudCrearTareaManual(
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.idAdulto, "Control médico", null,
                OffsetDateTime.parse("2026-11-03T01:00:00Z"), null, null,
                new SolicitudCita("Dra. Pérez", "Clínica Central", null));

        RespuestaTarea respuesta = servicio.crear(e.idGrupo, solicitud);

        // AssertJ compara OffsetDateTime por instante: se compara el texto ISO para exigir el offset.
        assertThat(respuesta.fechaHorario()).hasToString("2026-11-02T22:00-03:00");
        assertThat(respuesta.fechaHorario().getOffset()).isEqualTo(ZoneOffset.ofHours(-3));
    }

    // ------------------------------------------------------------------
    // Tipo de tarea
    // ------------------------------------------------------------------

    @Test
    void tipoMedicamentos422() {
        Escenario e = escenario();

        assertThatThrownBy(() -> servicio.crear(e.idGrupo,
                new SolicitudCrearTareaManual(CodigosTareas.TIPO_TAREA_MEDICAMENTOS, e.idAdulto, "Tarea inválida",
                        null, futuro(), null, null, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioTareasManuales.MENSAJE_TIPO_INVALIDO);
    }

    // ------------------------------------------------------------------
    // Compra / cita mutuamente excluyentes
    // ------------------------------------------------------------------

    @Test
    void compraSinLineas422() {
        Escenario e = escenario();

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitudCompra(e, List.of())))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioTareasManuales.MENSAJE_COMPRA_SIN_LINEAS);

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitudCompra(e, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioTareasManuales.MENSAJE_COMPRA_SIN_LINEAS);
    }

    @Test
    void citaSinDatos422() {
        Escenario e = escenario();

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitudCita(e, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioTareasManuales.MENSAJE_CITA_SIN_DATOS);
    }

    @Test
    void compraConCita422() {
        Escenario e = escenario();
        long idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidad = idOpcion("UNIDAD_DOSIS", "COMPRIMIDO");
        SolicitudLineaCompra linea = new SolicitudLineaCompra(idMedicamento, idPresentacion, null, BigDecimal.ONE,
                idUnidad, null);

        SolicitudCrearTareaManual solicitud = new SolicitudCrearTareaManual(
                CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS, e.idAdulto, "Compra con cita", null, futuro(),
                null, List.of(linea), new SolicitudCita("Dr. Soto", "Hospital", null));

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitud)).isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioTareasManuales.MENSAJE_COMPRA_CON_CITA);
    }

    @Test
    void citaConCompra422() {
        Escenario e = escenario();
        long idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol");
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidad = idOpcion("UNIDAD_DOSIS", "COMPRIMIDO");
        SolicitudLineaCompra linea = new SolicitudLineaCompra(idMedicamento, idPresentacion, null, BigDecimal.ONE,
                idUnidad, null);

        SolicitudCrearTareaManual solicitud = new SolicitudCrearTareaManual(
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.idAdulto, "Cita con compra", null, futuro(),
                null, List.of(linea), new SolicitudCita("Dr. Soto", "Hospital", null));

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitud)).isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioTareasManuales.MENSAJE_CITA_CON_COMPRA);
    }

    // ------------------------------------------------------------------
    // Validación de las líneas de compra
    // ------------------------------------------------------------------

    @Test
    void medicamentoInactivo422() {
        Escenario e = escenario();
        long idMedicamento = fixturaPlanes.crearMedicamento("Descontinuado");
        jdbcTemplate.update("UPDATE MEDICAMENTO SET ACTIVO = 'N' WHERE ID_MEDICAMENTO = ?", idMedicamento);
        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        long idUnidad = idOpcion("UNIDAD_DOSIS", "COMPRIMIDO");
        SolicitudLineaCompra linea = new SolicitudLineaCompra(idMedicamento, idPresentacion, null, BigDecimal.ONE,
                idUnidad, null);

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitudCompra(e, List.of(linea))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El medicamento indicado no existe o no está activo");
    }

    @Test
    void presentacionDeOtroCatalogo422() {
        Escenario e = escenario();
        long idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol");
        long idUnidad = idOpcion("UNIDAD_DOSIS", "COMPRIMIDO");
        // idPresentacion es en realidad una opción de UNIDAD_DOSIS: catálogo cruzado.
        SolicitudLineaCompra lineaConPresentacionCruzada = new SolicitudLineaCompra(idMedicamento, idUnidad, null,
                BigDecimal.ONE, idUnidad, null);

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitudCompra(e, List.of(lineaConPresentacionCruzada))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La opción indicada no pertenece al catálogo PRESENTACION_MEDICAMENTO");

        long idPresentacion = idOpcion("PRESENTACION_MEDICAMENTO", "COMPRIMIDO");
        // idUnidadCantidad es en realidad una opción de PRESENTACION_MEDICAMENTO: catálogo cruzado.
        SolicitudLineaCompra lineaConUnidadCruzada = new SolicitudLineaCompra(idMedicamento, idPresentacion, null,
                BigDecimal.ONE, idPresentacion, null);

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitudCompra(e, List.of(lineaConUnidadCruzada))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La opción indicada no pertenece al catálogo UNIDAD_DOSIS");
    }

    // ------------------------------------------------------------------
    // Adulto mayor
    // ------------------------------------------------------------------

    @Test
    void adultoDeOtroGrupo404() {
        Escenario grupoA = escenario();
        Escenario grupoB = escenario();
        autenticar(grupoB.uid);

        assertThatThrownBy(() -> servicio.crear(grupoB.idGrupo,
                solicitudCita(grupoA, new SolicitudCita("Dr. Soto", "Hospital", null))))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
    }

    @Test
    void adultoInactivo422() {
        Escenario e = escenario();
        fixturaGeneracion.cambiarEstado("ADULTO_MAYOR", "ID_ADULTO_MAYOR", e.idAdulto, "INACTIVO");

        assertThatThrownBy(() -> servicio.crear(e.idGrupo,
                solicitudCita(e, new SolicitudCita("Dr. Soto", "Hospital", null))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioTareasManuales.MENSAJE_ADULTO_INACTIVO);
    }

    // ------------------------------------------------------------------
    // Fecha y hora
    // ------------------------------------------------------------------

    @Test
    void fechaPasada422() {
        Escenario e = escenario();
        OffsetDateTime pasada = HOY.atTime(9, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();

        SolicitudCrearTareaManual solicitud = new SolicitudCrearTareaManual(
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.idAdulto, "Cita pasada", null, pasada, null, null,
                new SolicitudCita("Dr. Soto", "Hospital", null));

        assertThatThrownBy(() -> servicio.crear(e.idGrupo, solicitud)).isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioTareasManuales.MENSAJE_FECHA_FUTURA);
    }

    // ------------------------------------------------------------------
    // Permisos
    // ------------------------------------------------------------------

    @Test
    void colaborador403() {
        Escenario e = escenario();
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        autenticar(colaborador.uid());

        assertThatThrownBy(() -> servicio.crear(e.idGrupo,
                solicitudCita(e, new SolicitudCita("Dr. Soto", "Hospital", null))))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo un coordinador puede realizar esta acción");
    }

    // ------------------------------------------------------------------
    // Responsable
    // ------------------------------------------------------------------

    @Test
    void responsableDeOtroGrupo422() {
        Escenario grupoA = escenario();
        Escenario grupoB = escenario();
        FixturaGeneracion.UsuarioAlto colaboradorDeA = fixturaGeneracion.crearIntegranteConIdAlto(grupoA.idGrupo,
                "COLABORADOR", "ACTIVO");
        autenticar(grupoB.uid);

        SolicitudCrearTareaManual solicitud = new SolicitudCrearTareaManual(
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, grupoB.idAdulto, "Cita", null, futuro(),
                colaboradorDeA.idUsuario(), null, new SolicitudCita("Dr. Soto", "Hospital", null));

        assertThatThrownBy(() -> servicio.crear(grupoB.idGrupo, solicitud)).isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El responsable debe ser integrante activo del grupo");
    }

    @Test
    void responsableActivoCreaAsignacion() {
        Escenario e = escenario();
        FixturaGeneracion.UsuarioAlto responsable = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        assertThat(responsable.idUsuario()).isGreaterThan(127);

        SolicitudCrearTareaManual solicitud = new SolicitudCrearTareaManual(
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.idAdulto, "Control médico", null, futuro(),
                responsable.idUsuario(), null, new SolicitudCita("Dra. Pérez", "Clínica Central", null));

        RespuestaTarea respuesta = servicio.crear(e.idGrupo, solicitud);

        assertThat(respuesta.responsable()).isNotNull();
        assertThat(respuesta.responsable().idUsuario()).isEqualTo(responsable.idUsuario());
        assertThat(respuesta.responsable().nombre()).isEqualTo("Responsable fixtura");

        List<Map<String, Object>> asignaciones = jdbcTemplate.queryForList(
                "SELECT a.ID_USUARIO_ASIGNADO, a.ID_USUARIO_ASIGNADOR, ce.CODIGO_ESTADO FROM ASIGNACION_TAREA a "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO WHERE a.ID_TAREA = ?",
                respuesta.idTarea());
        assertThat(asignaciones).hasSize(1);
        assertThat(asignaciones.get(0).get("CODIGO_ESTADO")).isEqualTo("ACTIVO");
        assertThat(((Number) asignaciones.get(0).get("ID_USUARIO_ASIGNADO")).longValue())
                .isEqualTo(responsable.idUsuario());
        assertThat(((Number) asignaciones.get(0).get("ID_USUARIO_ASIGNADOR")).longValue())
                .isEqualTo(e.idCoordinador);

        assertThat(fixturaGeneracion.contarHistorial(respuesta.idTarea())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Historial inicial (regla 9 de la Fase 4)
    // ------------------------------------------------------------------

    @Test
    void creaHistorialInicial() {
        Escenario e = escenario();

        RespuestaTarea respuesta = servicio.crear(e.idGrupo,
                solicitudCita(e, new SolicitudCita("Dra. Pérez", "Clínica Central", null)));

        assertThat(fixturaGeneracion.contarHistorial(respuesta.idTarea())).isEqualTo(1);
        List<Map<String, Object>> historial = jdbcTemplate.queryForList(
                "SELECT ID_ESTADO_ANTERIOR, ID_ESTADO_NUEVO, ID_USUARIO_CAMBIO FROM HISTORIAL_ESTADO_TAREA "
                        + "WHERE ID_TAREA = ?",
                respuesta.idTarea());
        assertThat(historial).hasSize(1);
        assertThat(historial.get(0).get("ID_ESTADO_ANTERIOR")).isNull();
        long idPendiente = fixturaGeneracion.idEstado(CodigosTareas.TABLA_TAREA_CUIDADO,
                CodigosTareas.ESTADO_TAREA_PENDIENTE);
        assertThat(((Number) historial.get(0).get("ID_ESTADO_NUEVO")).longValue()).isEqualTo(idPendiente);
        assertThat(((Number) historial.get(0).get("ID_USUARIO_CAMBIO")).longValue()).isEqualTo(e.idCoordinador);
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

    private SolicitudCrearTareaManual solicitudCompra(Escenario e, List<SolicitudLineaCompra> compra) {
        return new SolicitudCrearTareaManual(CodigosTareas.TIPO_TAREA_COMPRA_RETIRO_MEDICAMENTOS, e.idAdulto,
                "Comprar medicamentos", null, futuro(), null, compra, null);
    }

    private SolicitudCrearTareaManual solicitudCita(Escenario e, SolicitudCita cita) {
        return new SolicitudCrearTareaManual(CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, e.idAdulto,
                "Control médico", null, futuro(), null, null, cita);
    }

    private OffsetDateTime futuro() {
        return HOY.plusDays(1).atTime(8, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private long idOpcion(String tipo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = ? AND CODIGO = ?", Long.class, tipo,
                codigo);
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
