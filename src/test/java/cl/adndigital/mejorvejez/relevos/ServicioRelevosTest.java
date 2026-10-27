package cl.adndigital.mejorvejez.relevos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.FixturaAlertas;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.comun.TextoAcotado;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioRelevos} contra Oracle real (Testcontainers), con el
 * reloj de negocio fijo el lunes 2026-11-02 10:00 (America/Santiago), igual
 * que {@code panel.ServicioPanelTareasTest} / {@code
 * auditoria.ServicioConsultaAuditoriaTest}. Datos propios por prueba
 * (grupos/usuarios con UUID); nunca se cuentan tablas completas (la BD es
 * compartida).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConfiguracionTestcontainers.class)
class ServicioRelevosTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);
    private static final OffsetDateTime AHORA = HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioRelevos servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaTareas fixturaTareas;
    private FixturaAlertas fixturaAlertas;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Crear y consultar (HTTP de punta a punta)
    // ------------------------------------------------------------------

    @Test
    void crearYConsultarRelevo() throws Exception {
        Escenario e = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());
        String cuerpo = "{\"idAdulto\":" + e.idAdulto() + ",\"idUsuarioSaliente\":" + e.idCoordinador()
                + ",\"idUsuarioEntrante\":" + idColaborador + ",\"notas\":\"Notas del relevo\"}";

        MvcResult creacion = mockMvc.perform(post("/api/grupos/{idGrupo}/relevos", e.idGrupo())
                        .with(jwt().jwt(constructor -> constructor.subject(e.uid())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.codigoEstado").value("COMPLETADA"))
                .andReturn();

        String location = creacion.getResponse().getHeader("Location");
        assertThat(location).startsWith("/api/grupos/" + e.idGrupo() + "/relevos/");

        mockMvc.perform(get(location).with(jwt().jwt(constructor -> constructor.subject(e.uid()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idGrupo").value(e.idGrupo()))
                .andExpect(jsonPath("$.nombreGrupo").isNotEmpty())
                .andExpect(jsonPath("$.idAdulto").value(e.idAdulto()))
                .andExpect(jsonPath("$.nombreAdulto").isNotEmpty())
                .andExpect(jsonPath("$.saliente.idUsuario").value(e.idCoordinador()))
                .andExpect(jsonPath("$.entrante.idUsuario").value(idColaborador))
                .andExpect(jsonPath("$.creador.idUsuario").value(e.idCoordinador()))
                .andExpect(jsonPath("$.notas").value("Notas del relevo"))
                .andExpect(jsonPath("$.codigoEstado").value("COMPLETADA"))
                .andExpect(jsonPath("$.fechaTraspaso", org.hamcrest.Matchers.containsString("-03:00")));
    }

    // ------------------------------------------------------------------
    // Resumen de tareas y alertas (regla 4 de la Fase 7)
    // ------------------------------------------------------------------

    @Test
    void resumenCorrectoDeTareasYAlertasAbiertas() {
        Escenario e = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());
        long idOtroAdulto = fixturaAdultos.crearAdulto(e.idGrupo(), e.idCoordinador(), "ACTIVO");

        // Entran: PENDIENTE atrasada de las 08:00 (límite 08:30 < 10:00), con responsable asignado.
        long idPendienteAtrasada = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        fixturaTareas.crearAsignacion(idPendienteAtrasada, e.idGrupo(), idColaborador, e.idCoordinador());
        // Entran: EN_CURSO de hace 3 días (dentro de la retrospectiva de 7 días).
        long idEnCursoHace3Dias = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.minusDays(3));
        fixturaTareas.cambiarEstadoTarea(idEnCursoHace3Dias, CodigosTareas.ESTADO_TAREA_EN_CURSO);
        // Entra: PENDIENTE en 20 h (dentro de las 24 h hacia adelante).
        long idPendienteEn20h = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.plusHours(20));

        // No entra: COMPLETADA.
        long idCompletada = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.plusHours(1));
        fixturaTareas.cambiarEstadoTarea(idCompletada, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        // No entra: PENDIENTE en 25 h (fuera de la ventana de 24 h).
        fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(), CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                AHORA.plusHours(25));
        // No entra: hace 8 días (fuera de la retrospectiva de 7 días).
        fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(), CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                AHORA.minusDays(8));
        // No entra: de otro adulto.
        fixturaTareas.crearTareaManual(e.idGrupo(), idOtroAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                AHORA.minusHours(1));

        // Entran: 2 alertas ABIERTA (ATRASO y REPOSICION).
        fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idPendienteAtrasada,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idPendienteEn20h,
                CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO, FixturaAlertas.claveUnica());
        // No entra: CERRADA.
        long idAlertaCerrada = fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idCompletada,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        fixturaAlertas.cambiarEstadoAlerta(idAlertaCerrada, CodigosAlertas.ESTADO_ALERTA_CERRADA);

        autenticar(e.uid());
        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null));

        assertThat(relevo.totalTareas()).isEqualTo(3);
        assertThat(relevo.totalAlertas()).isEqualTo(2);
        assertThat(relevo.detalles()).hasSize(5);
        assertThat(relevo.detalles().subList(0, 3)).extracting(RespuestaDetalleRelevo::tipoElemento)
                .containsOnly("TAREA");
        assertThat(relevo.detalles().subList(3, 5)).extracting(RespuestaDetalleRelevo::tipoElemento)
                .containsOnly("ALERTA");

        RespuestaDetalleRelevo detalleAtrasada = relevo.detalles().stream()
                .filter(detalle -> "TAREA".equals(detalle.tipoElemento())
                        && detalle.idReferencia().equals(idPendienteAtrasada))
                .findFirst().orElseThrow();
        assertThat(detalleAtrasada.resumen()).contains("Atrasada").contains("Responsable: Usuario fixtura");
    }

    // ------------------------------------------------------------------
    // Solo lectura sobre tareas y alertas (regla 5)
    // ------------------------------------------------------------------

    @Test
    void relevoNoAlteraEstados() {
        Escenario e = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.minusHours(2));
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo(), idColaborador, e.idCoordinador());
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());

        String estadoTareaAntes = fixturaTareas.estadoTarea(idTarea);
        String estadoAlertaAntes = fixturaAlertas.estadoAlerta(idAlerta);
        long asignacionesAntes = contarAsignaciones(idTarea);
        String estadoAsignacionAntes = estadoAsignacionVigente(idTarea);

        autenticar(e.uid());
        servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null));

        assertThat(fixturaTareas.estadoTarea(idTarea)).isEqualTo(estadoTareaAntes);
        assertThat(fixturaAlertas.estadoAlerta(idAlerta)).isEqualTo(estadoAlertaAntes);
        assertThat(contarAsignaciones(idTarea)).isEqualTo(asignacionesAntes);
        assertThat(estadoAsignacionVigente(idTarea)).isEqualTo(estadoAsignacionAntes);
    }

    // ------------------------------------------------------------------
    // Aislamiento de la foto (foco de revisión 5)
    // ------------------------------------------------------------------

    @Test
    void resumenSoloDelAdultoYGrupo() {
        Escenario e = escenarioCoordinador();
        Escenario otro = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());
        long idOtroAdultoMismoGrupo = fixturaAdultos.crearAdulto(e.idGrupo(), e.idCoordinador(), "ACTIVO");

        long idTareaOtroAdulto = fixturaTareas.crearTareaManual(e.idGrupo(), idOtroAdultoMismoGrupo,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.minusHours(1));
        long idTareaOtroGrupo = fixturaTareas.crearTareaManual(otro.idGrupo(), otro.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.minusHours(1));
        long idAlertaOtroGrupo = fixturaAlertas.crearAlertaDeTarea(otro.idGrupo(), otro.idAdulto(), idTareaOtroGrupo,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());

        autenticar(e.uid());
        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null));

        assertThat(relevo.detalles()).extracting(RespuestaDetalleRelevo::idReferencia)
                .doesNotContain(idTareaOtroAdulto, idTareaOtroGrupo, idAlertaOtroGrupo);
    }

    // ------------------------------------------------------------------
    // Validaciones del adulto y de las personas (regla 2)
    // ------------------------------------------------------------------

    @Test
    void adultoDeOtroGrupoDa404() {
        Escenario propio = escenarioCoordinador();
        Escenario ajeno = escenarioCoordinador();
        long idColaborador = crearColaborador(propio.idGrupo());
        autenticar(propio.uid());

        assertThatThrownBy(() -> servicio.registrar(propio.idGrupo(),
                new SolicitudRegistrarRelevo(ajeno.idAdulto(), propio.idCoordinador(), idColaborador, null)))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
    }

    @Test
    void adultoInactivoDa422() {
        Escenario e = escenarioCoordinador();
        long idAdultoInactivo = fixturaAdultos.crearAdulto(e.idGrupo(), e.idCoordinador(), "INACTIVO");
        long idColaborador = crearColaborador(e.idGrupo());
        autenticar(e.uid());

        assertThatThrownBy(() -> servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(idAdultoInactivo, e.idCoordinador(), idColaborador, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioRelevos.MENSAJE_ADULTO_INACTIVO);
    }

    @Test
    void entranteDeOtroGrupoDa422() {
        Escenario e = escenarioCoordinador();
        Escenario otro = escenarioCoordinador();
        autenticar(e.uid());

        assertThatThrownBy(() -> servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), otro.idCoordinador(), null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioRelevos.MENSAJE_PERSONAS_NO_ACTIVAS);
    }

    @Test
    void entranteInactivoDa422() {
        Escenario e = escenarioCoordinador();
        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(e.idGrupo(), idColaborador, "COLABORADOR", "INACTIVO");
        autenticar(e.uid());

        assertThatThrownBy(() -> servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioRelevos.MENSAJE_PERSONAS_NO_ACTIVAS);
    }

    @Test
    void mismaPersonaDa422() {
        Escenario e = escenarioCoordinador();
        autenticar(e.uid());

        assertThatThrownBy(() -> servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), e.idCoordinador(), null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioRelevos.MENSAJE_MISMAS_PERSONAS);
    }

    // ------------------------------------------------------------------
    // Permisos de quién registra (regla 2.6)
    // ------------------------------------------------------------------

    @Test
    void colaboradorSoloRegistraComoSaliente() {
        Escenario e = escenarioCoordinador();
        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(e.idGrupo(), idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        assertThatThrownBy(() -> servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioRelevos.MENSAJE_SOLO_PROPIO_SALIENTE);

        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), idColaborador, e.idCoordinador(), null));
        assertThat(relevo.idRelevo()).isNotNull();
        assertThat(relevo.saliente().idUsuario()).isEqualTo(idColaborador);
    }

    @Test
    void coordinadorRegistraParaOtros() {
        Escenario e = escenarioCoordinador();
        long idColaboradorA = crearColaborador(e.idGrupo());
        long idColaboradorB = crearColaborador(e.idGrupo());
        autenticar(e.uid());

        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), idColaboradorA, idColaboradorB, null));

        assertThat(relevo.saliente().idUsuario()).isEqualTo(idColaboradorA);
        assertThat(relevo.entrante().idUsuario()).isEqualTo(idColaboradorB);
        assertThat(relevo.creador().idUsuario()).isEqualTo(e.idCoordinador());
    }

    @Test
    void noMiembroDa403() {
        Escenario e = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());
        String uidAjeno = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAjeno, false);
        autenticar(uidAjeno);

        assertThatThrownBy(() -> servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.listar(e.idGrupo(), null, 0, 20))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    // ------------------------------------------------------------------
    // Truncado a 1000 bytes (regla 4)
    // ------------------------------------------------------------------

    @Test
    void resumenLargoSeTruncaA1000Bytes() {
        Escenario e = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());

        String tituloConTildes = tituloLargoConTildes(200);
        long idTarea = crearTareaConTitulo(e.idGrupo(), e.idAdulto(), tituloConTildes, AHORA.minusHours(1),
                CodigosTareas.ESTADO_TAREA_PENDIENTE);

        String mensajeLargo = "A".repeat(1000);
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        jdbcTemplate.update("UPDATE ALERTA_CUIDADO SET MENSAJE_ALERTA = ? WHERE ID_ALERTA = ?", mensajeLargo,
                idAlerta);

        autenticar(e.uid());
        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null));

        RespuestaDetalleRelevo detalleTarea = relevo.detalles().stream()
                .filter(detalle -> "TAREA".equals(detalle.tipoElemento())).findFirst().orElseThrow();
        RespuestaDetalleRelevo detalleAlerta = relevo.detalles().stream()
                .filter(detalle -> "ALERTA".equals(detalle.tipoElemento())).findFirst().orElseThrow();

        assertThat(detalleTarea.resumen().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1000);
        assertThat(detalleTarea.resumen()).contains(tituloConTildes);

        assertThat(detalleAlerta.resumen().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1000);
        // El mensaje solo (1000 bytes) más la etiqueta y el separador no caben en 1000: se corta.
        assertThat(detalleAlerta.resumen().length()).isLessThan(mensajeLargo.length() + 20);
    }

    // ------------------------------------------------------------------
    // Listado paginado (regla 1)
    // ------------------------------------------------------------------

    @Test
    void listarPaginadoYFiltradoPorAdulto() {
        Escenario e = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());
        long idOtroAdulto = fixturaAdultos.crearAdulto(e.idGrupo(), e.idCoordinador(), "ACTIVO");
        autenticar(e.uid());

        List<Long> idsDelAdulto = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                    new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null));
            idsDelAdulto.add(relevo.idRelevo());
        }
        servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(idOtroAdulto, e.idCoordinador(), idColaborador, null));

        RespuestaPagina<RespuestaRelevoResumen> pagina = servicio.listar(e.idGrupo(), e.idAdulto(), 0, 20);

        assertThat(pagina.total()).isEqualTo(3);
        // Misma fecha (reloj fijo): desempate por idTraspaso descendente (el más reciente creado primero).
        assertThat(pagina.elementos()).extracting(RespuestaRelevoResumen::idRelevo)
                .containsExactly(idsDelAdulto.get(2), idsDelAdulto.get(1), idsDelAdulto.get(0));

        RespuestaPagina<RespuestaRelevoResumen> primeraPagina = servicio.listar(e.idGrupo(), e.idAdulto(), 0, 2);
        assertThat(primeraPagina.elementos()).hasSize(2);
        assertThat(primeraPagina.total()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Obtener (aislamiento por grupo)
    // ------------------------------------------------------------------

    @Test
    void obtenerDeOtroGrupoDa404() {
        Escenario propio = escenarioCoordinador();
        Escenario ajeno = escenarioCoordinador();
        long idColaboradorAjeno = crearColaborador(ajeno.idGrupo());
        autenticar(ajeno.uid());
        RespuestaRelevo relevoAjeno = servicio.registrar(ajeno.idGrupo(),
                new SolicitudRegistrarRelevo(ajeno.idAdulto(), ajeno.idCoordinador(), idColaboradorAjeno, null));

        autenticar(propio.uid());
        assertThatThrownBy(() -> servicio.obtener(propio.idGrupo(), relevoAjeno.idRelevo()))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage(ServicioRelevos.MENSAJE_RELEVO_NO_EXISTE);
    }

    // ------------------------------------------------------------------
    // Cancelar (regla 7)
    // ------------------------------------------------------------------

    @Test
    void cancelarPorCreadorOCoordinador() {
        // Ronda de corrección 1 (Important 1 y 2): el creador es un COLABORADOR con id > 127
        // (fuera de la caché de Long), justo el caso que "ServicioRelevos.cancelar" fallaba al
        // comparar "traspaso.getIdUsuarioCreador() == contexto.usuario().getIdUsuario()" (Long == Long).
        Escenario e = escenarioCoordinador();
        UsuarioAlto colaboradorCreador = crearColaboradorConIdAlto(e.idGrupo());
        long idColaboradorEntrante = crearColaborador(e.idGrupo());

        // Al menos una tarea y una alerta en la foto, para comprobar después que se conservan.
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.minusHours(1));
        fixturaAlertas.crearAlertaDeTarea(e.idGrupo(), e.idAdulto(), idTarea, CodigosAlertas.TIPO_ATRASO_TAREA,
                FixturaAlertas.claveUnica());

        // El colaborador (id > 127) registra el relevo como saliente: queda como creador.
        autenticar(colaboradorCreador.uid());
        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(), new SolicitudRegistrarRelevo(e.idAdulto(),
                colaboradorCreador.idUsuario(), idColaboradorEntrante, null));
        assertThat(relevo.totalTareas() + relevo.totalAlertas()).isGreaterThan(0);

        // Un tercero colaborador (ni creador ni coordinador) -> 403.
        String uidTercero = FixturaGrupos.uidUnico();
        long idTercero = fixturaGrupos.crearUsuario(uidTercero, false);
        fixturaGrupos.agregarIntegrante(e.idGrupo(), idTercero, "COLABORADOR", "ACTIVO");
        autenticar(uidTercero);
        assertThatThrownBy(() -> servicio.cancelar(e.idGrupo(), relevo.idRelevo()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioRelevos.MENSAJE_SOLO_CREADOR_O_COORDINADOR);

        // El creador (colaborador, id > 127) cancela con éxito: antes de la corrección daba 403.
        autenticar(colaboradorCreador.uid());
        RespuestaRelevo cancelado = servicio.cancelar(e.idGrupo(), relevo.idRelevo());
        assertThat(cancelado.codigoEstado()).isEqualTo("CANCELADA");

        // Los detalles se conservan: misma cantidad y las mismas referencias, releyendo con obtener.
        RespuestaRelevo releido = servicio.obtener(e.idGrupo(), relevo.idRelevo());
        assertThat(releido.detalles()).hasSameSizeAs(relevo.detalles());
        assertThat(releido.detalles()).extracting(RespuestaDetalleRelevo::idReferencia)
                .containsExactlyInAnyOrderElementsOf(
                        relevo.detalles().stream().map(RespuestaDetalleRelevo::idReferencia).toList());

        // Cancelar de nuevo -> 409.
        assertThatThrownBy(() -> servicio.cancelar(e.idGrupo(), relevo.idRelevo()))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage(ServicioRelevos.MENSAJE_YA_CANCELADO);

        // El coordinador (no es el creador de este otro relevo) también puede cancelarlo.
        autenticar(colaboradorCreador.uid());
        RespuestaRelevo otroRelevo = servicio.registrar(e.idGrupo(), new SolicitudRegistrarRelevo(e.idAdulto(),
                colaboradorCreador.idUsuario(), idColaboradorEntrante, null));
        autenticar(e.uid());
        RespuestaRelevo otroCancelado = servicio.cancelar(e.idGrupo(), otroRelevo.idRelevo());
        assertThat(otroCancelado.codigoEstado()).isEqualTo("CANCELADA");
    }

    // ------------------------------------------------------------------
    // Consulta del administrador auditada (foco de revisión 3, ronda de corrección 1)
    // ------------------------------------------------------------------

    @Test
    void administradorSinMembresiaConsultaObtenerYQuedaAuditado() throws Exception {
        Escenario e = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());
        autenticar(e.uid());
        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null));

        // El coordinador (miembro) consulta primero: nunca debe generar CONSULTAR_RELEVO.
        servicio.obtener(e.idGrupo(), relevo.idRelevo());
        assertThat(contarEventos(e.idGrupo(), "CONSULTAR_RELEVO")).isZero();

        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);
        servicio.obtener(e.idGrupo(), relevo.idRelevo());
        // listar no audita: solo obtener (mismo patrón del panel, que audita una sola sección).
        servicio.listar(e.idGrupo(), null, 0, 20);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'CONSULTAR_RELEVO'",
                e.idGrupo());
        assertThat(eventos).hasSize(1);
        Map<String, Object> detalles = jsonMapper.readValue((String) eventos.get(0).get("DETALLES_JSON"),
                new TypeReference<Map<String, Object>>() {
                });
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // Minor 6: cuenta de usuario inactiva, y administrador que registra
    // ------------------------------------------------------------------

    @Test
    void entranteConCuentaDeUsuarioInactivaDa422() {
        Escenario e = escenarioCoordinador();
        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(e.idGrupo(), idColaborador, "COLABORADOR", "ACTIVO");
        // La membresía queda ACTIVA; lo que se inactiva es la cuenta global USUARIO (regla 2.5).
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?", idEstadoUsuario("INACTIVO"),
                idColaborador);
        autenticar(e.uid());

        assertThatThrownBy(() -> servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioRelevos.MENSAJE_PERSONAS_NO_ACTIVAS);
    }

    @Test
    void administradorSinMembresiaRegistraYQuedaAuditado() {
        Escenario e = escenarioCoordinador();
        long idColaboradorSaliente = crearColaborador(e.idGrupo());
        long idColaboradorEntrante = crearColaborador(e.idGrupo());
        String uidAdmin = FixturaGrupos.uidUnico();
        long idAdmin = fixturaGrupos.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);

        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), idColaboradorSaliente, idColaboradorEntrante, null));

        assertThat(relevo.idRelevo()).isNotNull();
        assertThat(relevo.creador().idUsuario()).isEqualTo(idAdmin);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'REGISTRAR_RELEVO' "
                        + "AND ID_USUARIO_ACTOR = ?",
                e.idGrupo(), idAdmin);
        assertThat(eventos).hasSize(1);
        Map<String, Object> detalles = jsonMapper.readValue((String) eventos.get(0).get("DETALLES_JSON"),
                new TypeReference<Map<String, Object>>() {
                });
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // Auditoría sin notas (regla 8)
    // ------------------------------------------------------------------

    @Test
    void auditaRegistrarYCancelarSinNotas() {
        Escenario e = escenarioCoordinador();
        long idColaborador = crearColaborador(e.idGrupo());
        autenticar(e.uid());
        String notasSecretas = "NOTA-SECRETA-" + UUID.randomUUID();

        RespuestaRelevo relevo = servicio.registrar(e.idGrupo(),
                new SolicitudRegistrarRelevo(e.idAdulto(), e.idCoordinador(), idColaborador, notasSecretas));
        servicio.cancelar(e.idGrupo(), relevo.idRelevo());

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? "
                        + "AND CODIGO_ACCION IN ('REGISTRAR_RELEVO', 'CANCELAR_RELEVO')",
                e.idGrupo());
        assertThat(eventos).hasSize(2);
        for (Map<String, Object> evento : eventos) {
            String json = (String) evento.get("DETALLES_JSON");
            if (json != null) {
                assertThat(json).doesNotContain(notasSecretas);
            }
        }
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private Escenario escenarioCoordinador() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        return new Escenario(uid, idCoordinador, idGrupo, idAdulto);
    }

    private record Escenario(String uid, long idCoordinador, long idGrupo, long idAdulto) {
    }

    private long crearColaborador(long idGrupo) {
        long idColaborador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        return idColaborador;
    }

    /**
     * Ids de usuario explícitos por sobre el rango cacheado de {@code Long}
     * (> 127), en un rango propio (distinto del 900.000.000 de {@code
     * ServicioDetallesPlanTest} y del 950.000.000 de {@code
     * tareas.FixturaGeneracion}), para el caso "creador colaborador" de
     * {@link #cancelarPorCreadorOCoordinador} (ronda de corrección 1,
     * Important 2): la identidad de {@code USUARIO} nunca llega tan alto en
     * el contenedor de pruebas.
     */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(960_000_000L);

    private record UsuarioAlto(String uid, long idUsuario) {
    }

    /** Usuario con id explícito > 127, integrante COLABORADOR ACTIVO del grupo; devuelve también su uid. */
    private UsuarioAlto crearColaboradorConIdAlto(long idGrupo) {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        String uid = FixturaGrupos.uidUnico();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 'N', ?)",
                idUsuario, uid, "fixtura-" + UUID.randomUUID() + "@relevos.mejorvejez.test", "Usuario fixtura",
                idEstadoUsuario("ACTIVO"));
        fixturaGrupos.agregarIntegrante(idGrupo, idUsuario, "COLABORADOR", "ACTIVO");
        return new UsuarioAlto(uid, idUsuario);
    }

    private long idEstadoUsuario(String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = 'USUARIO' AND NOMBRE_CAMPO = 'ESTADO' "
                        + "AND CODIGO_ESTADO = ?",
                Long.class, codigo);
    }

    private long contarEventos(long idGrupo, String codigoAccion) {
        Long cantidad = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = ?", Long.class,
                idGrupo, codigoAccion);
        return cantidad == null ? 0 : cantidad;
    }

    private OffsetDateTime horario(LocalDate dia, int hora, int minuto) {
        return dia.atTime(hora, minuto).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private long contarAsignaciones(long idTarea) {
        Long cantidad = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ASIGNACION_TAREA WHERE ID_TAREA = ?",
                Long.class, idTarea);
        return cantidad == null ? 0 : cantidad;
    }

    private String estadoAsignacionVigente(long idTarea) {
        return jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM ASIGNACION_TAREA a "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO "
                        + "WHERE a.ID_TAREA = ? ORDER BY a.ID_ASIGNACION DESC FETCH FIRST 1 ROW ONLY",
                String.class, idTarea);
    }

    /** Texto con tildes, truncado de antemano a {@code maximoBytes} para que quepa en una columna VARCHAR2(200). */
    private String tituloLargoConTildes(int maximoBytes) {
        String patron = "áéíóú ñÑ Camión viático café bebé canción médico vigésimo ";
        StringBuilder relleno = new StringBuilder();
        while (relleno.length() < maximoBytes) {
            relleno.append(patron);
        }
        return TextoAcotado.truncarABytes(relleno.toString(), maximoBytes);
    }

    /** Inserta una TAREA_CUIDADO con un título arbitrario: {@code FixturaTareas} usa siempre el mismo título fijo. */
    private long crearTareaConTitulo(long idGrupo, long idAdulto, String titulo, OffsetDateTime horario,
            String codigoEstado) {
        long idEstado = jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'TAREA_CUIDADO' AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, codigoEstado);
        KeyHolder generadorDeClave = new GeneratedKeyHolder();
        jdbcTemplate.update(conexion -> {
            PreparedStatement sentencia = conexion.prepareStatement(
                    "INSERT INTO TAREA_CUIDADO (TIPO_TAREA, ID_GRUPO, ID_ADULTO_MAYOR, TITULO_TAREA, "
                            + "FECHA_HORARIO, ID_ESTADO) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[] {"ID_TAREA"});
            sentencia.setString(1, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS);
            sentencia.setLong(2, idGrupo);
            sentencia.setLong(3, idAdulto);
            sentencia.setString(4, titulo);
            sentencia.setObject(5, horario);
            sentencia.setLong(6, idEstado);
            return sentencia;
        }, generadorDeClave);
        return generadorDeClave.getKey().longValue();
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
