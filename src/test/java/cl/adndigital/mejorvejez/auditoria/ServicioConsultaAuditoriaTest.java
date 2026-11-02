package cl.adndigital.mejorvejez.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;
import cl.adndigital.mejorvejez.tareas.ServicioAsignacionTareas;
import cl.adndigital.mejorvejez.tareas.SolicitudAsignarTarea;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioConsultaAuditoria} contra Oracle real
 * (Testcontainers), con el reloj de negocio fijo el lunes 2026-11-02 10:00
 * (America/Santiago), igual que {@code panel.ServicioPanelTareasTest}. Datos
 * propios por prueba (grupos/usuarios con UUID); {@code EVENTO_AUDITORIA} es
 * append-only, así que nunca se limpia lo que se confirma: cada prueba
 * filtra por su propio grupo o id de correlación.
 *
 * <p>{@code @AutoConfigureMockMvc} agrega {@link MockMvc} sobre la
 * aplicación completa (controlador + servicio + Oracle real), para que
 * {@link #detallesConSecretosAnidadosNoSeExponen} revise el JSON de la
 * respuesta HTTP tal cual la recibe el navegador, no una llamada directa al
 * servicio.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConfiguracionTestcontainers.class)
class ServicioConsultaAuditoriaTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);

    private static final FiltroConsultaAuditoria FILTRO_VACIO =
            new FiltroConsultaAuditoria(null, null, null, null, null, null, null);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioConsultaAuditoria servicio;

    @Autowired
    private ServicioAuditoria servicioAuditoria;

    @Autowired
    private ServicioAsignacionTareas servicioAsignacion;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MockMvc mockMvc;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaTareas fixturaTareas;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Aislamiento por grupo (regla 9)
    // ------------------------------------------------------------------

    @Test
    void coordinadorVeSoloEventosDeSuGrupo() {
        Escenario a = escenarioCoordinador();
        Escenario b = escenarioCoordinador();
        registrarExito(a.idGrupo, "TIPO_A", "1", a.idCoordinador, "ACCION_A");
        registrarExito(b.idGrupo, "TIPO_B", "1", b.idCoordinador, "ACCION_B");
        autenticar(a.uid);

        RespuestaPagina<RespuestaEventoAuditoria> respuesta = servicio.consultarGrupo(a.idGrupo, FILTRO_VACIO, 0, 20);

        assertThat(respuesta.total()).isEqualTo(1);
        assertThat(respuesta.elementos()).extracting(RespuestaEventoAuditoria::idGrupo).containsExactly(a.idGrupo);
    }

    // ------------------------------------------------------------------
    // Filtros: entidad, acción, resultado y fechas inclusivas en Santiago
    // ------------------------------------------------------------------

    @Test
    void filtrosPorEntidadAccionResultadoYFechas() {
        Escenario e = escenarioCoordinador();
        LocalDate desde = HOY.minusDays(1);
        LocalDate hasta = HOY;
        OffsetDateTime dentroDelRango = hasta.atTime(23, 59).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime fueraDelRango = hasta.plusDays(1).atStartOfDay(ZonaNegocio.ZONA).toOffsetDateTime();

        long idDentroRechazada = crearEvento(e.idGrupo, "ADULTO_MAYOR", "9", e.idCoordinador, "ACCION_FOCO",
                "RECHAZADA", dentroDelRango, null);
        crearEvento(e.idGrupo, "ADULTO_MAYOR", "9", e.idCoordinador, "ACCION_FOCO", "RECHAZADA", fueraDelRango, null);
        crearEvento(e.idGrupo, "ADULTO_MAYOR", "9", e.idCoordinador, "ACCION_FOCO", "EXITO", dentroDelRango, null);
        crearEvento(e.idGrupo, "OTRA_ENTIDAD", "9", e.idCoordinador, "ACCION_FOCO", "RECHAZADA", dentroDelRango, null);
        crearEvento(e.idGrupo, "ADULTO_MAYOR", "9", e.idCoordinador, "OTRA_ACCION", "RECHAZADA", dentroDelRango, null);
        autenticar(e.uid);

        FiltroConsultaAuditoria filtro =
                new FiltroConsultaAuditoria(null, "ADULTO_MAYOR", null, "ACCION_FOCO", "RECHAZADA", desde, hasta);
        RespuestaPagina<RespuestaEventoAuditoria> respuesta = servicio.consultarGrupo(e.idGrupo, filtro, 0, 20);

        assertThat(respuesta.elementos()).extracting(RespuestaEventoAuditoria::idEvento)
                .containsExactly(idDentroRechazada);
    }

    @Test
    void desdePosteriorAHastaDa422() {
        Escenario e = escenarioCoordinador();
        autenticar(e.uid);
        FiltroConsultaAuditoria filtro =
                new FiltroConsultaAuditoria(null, null, null, null, null, HOY, HOY.minusDays(1));

        assertThatThrownBy(() -> servicio.consultarGrupo(e.idGrupo, filtro, 0, 20))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La fecha desde no puede ser posterior a la fecha hasta");
    }

    @Test
    void codigoResultadoInvalidoDa422() {
        Escenario e = escenarioCoordinador();
        autenticar(e.uid);
        FiltroConsultaAuditoria filtro = new FiltroConsultaAuditoria(null, null, null, null, "OTRO", null, null);

        assertThatThrownBy(() -> servicio.consultarGrupo(e.idGrupo, filtro, 0, 20))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioConsultaAuditoria.MENSAJE_RESULTADO_INVALIDO);
    }

    // ------------------------------------------------------------------
    // Filtros de texto en blanco: se tratan como "sin filtro" (Corrección ronda 1)
    // ------------------------------------------------------------------

    @Test
    void filtroDeTextoEnBlancoSeIgnora() {
        Escenario e = escenarioCoordinador();
        long idEvento = registrarExito(e.idGrupo, "USUARIO", "1", e.idCoordinador, "ACCION_EN_BLANCO");
        autenticar(e.uid);

        FiltroConsultaAuditoria filtroEnBlanco =
                new FiltroConsultaAuditoria(null, "  ", "", "   ", null, null, null);
        RespuestaPagina<RespuestaEventoAuditoria> respuesta =
                servicio.consultarGrupo(e.idGrupo, filtroEnBlanco, 0, 20);

        // Un tipoEntidad/idEntidad/codigoAccion en blanco no debe excluir nada: si se tratara como un
        // valor real, "e.TIPO_ENTIDAD = ''" no calzaría con ninguna fila (en Oracle '' = NULL).
        assertThat(respuesta.elementos()).extracting(RespuestaEventoAuditoria::idEvento).contains(idEvento);
    }

    // ------------------------------------------------------------------
    // Paginación y orden descendente
    // ------------------------------------------------------------------

    @Test
    void paginacionOrdenDescendenteYTotal() {
        Escenario e = escenarioCoordinador();
        List<Long> idsEnOrdenDeCreacion = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            OffsetDateTime fecha = HOY.atTime(0, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime().plusMinutes(i);
            idsEnOrdenDeCreacion.add(
                    crearEvento(e.idGrupo, "PAGINACION", "1", e.idCoordinador, "ACCION_PAGINACION", "EXITO", fecha,
                            null));
        }
        autenticar(e.uid);

        RespuestaPagina<RespuestaEventoAuditoria> pagina2 = servicio.consultarGrupo(e.idGrupo, FILTRO_VACIO, 2, 10);

        assertThat(pagina2.total()).isEqualTo(25);
        assertThat(pagina2.elementos()).hasSize(5);
        // Orden descendente: la página 2 (offset 20) trae los 5 primeros creados (los más antiguos).
        List<Long> esperados = idsEnOrdenDeCreacion.subList(0, 5);
        List<Long> esperadosDescendentes = new ArrayList<>(esperados);
        Collections.reverse(esperadosDescendentes);
        assertThat(pagina2.elementos()).extracting(RespuestaEventoAuditoria::idEvento)
                .containsExactlyElementsOf(esperadosDescendentes);
    }

    // ------------------------------------------------------------------
    // Permisos
    // ------------------------------------------------------------------

    @Test
    void colaboradorDa403() {
        String uidColaborador = FixturaGrupos.uidUnico();
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCreador, "COORDINADOR", "ACTIVO");
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        assertThatThrownBy(() -> servicio.consultarGrupo(idGrupo, FILTRO_VACIO, 0, 20))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void noMiembroDa403() {
        Escenario e = escenarioCoordinador();
        String uidAjeno = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAjeno, false);
        autenticar(uidAjeno);

        assertThatThrownBy(() -> servicio.consultarGrupo(e.idGrupo, FILTRO_VACIO, 0, 20))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void globalNoAdministradorDa403() {
        Escenario e = escenarioCoordinador();
        autenticar(e.uid);

        assertThatThrownBy(() -> servicio.consultarGlobal(FILTRO_VACIO, 0, 20))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    // ------------------------------------------------------------------
    // Administrador sin membresía: puede consultar y queda auditado
    // ------------------------------------------------------------------

    @Test
    void administradorSinMembresiaConsultaYQuedaAuditado() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        Escenario e = escenarioCoordinador();

        // El coordinador consulta primero: no debe generar CONSULTAR_AUDITORIA.
        autenticar(e.uid);
        servicio.consultarGrupo(e.idGrupo, FILTRO_VACIO, 0, 20);
        long eventosDelCoordinador = contarConsultasAuditoria(e.idGrupo);
        assertThat(eventosDelCoordinador).isZero();

        autenticar(uidAdmin);
        FiltroConsultaAuditoria filtroConEntidad =
                new FiltroConsultaAuditoria(null, "ADULTO_MAYOR", "9", null, null, null, null);
        servicio.consultarGrupo(e.idGrupo, filtroConEntidad, 0, 20);
        servicio.consultarGlobal(FILTRO_VACIO, 0, 20);

        // El evento CONSULTAR_AUDITORIA del endpoint de grupo tiene ID_GRUPO = e.idGrupo; el del
        // endpoint global (sin filtro de idGrupo) queda con ID_GRUPO = null, así que esta consulta
        // (filtrada por ID_GRUPO = e.idGrupo) solo encuentra la del grupo.
        List<Map<String, Object>> eventosDelGrupo = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'CONSULTAR_AUDITORIA'",
                e.idGrupo);
        assertThat(eventosDelGrupo).hasSize(1);
        Map<String, Object> detalles = jsonMapper.readValue((String) eventosDelGrupo.get(0).get("DETALLES_JSON"),
                new TypeReference<Map<String, Object>>() {
                });
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
        // Regla 13: los detalles llevan los filtros usados SIN idEntidad.
        assertThat(detalles).containsEntry("tipoEntidad", "ADULTO_MAYOR");
        assertThat(detalles).doesNotContainKey("idEntidad");

        long eventosGlobales = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA WHERE ID_GRUPO IS NULL AND CODIGO_ACCION = 'CONSULTAR_AUDITORIA' "
                        + "AND ID_USUARIO_ACTOR = (SELECT ID_USUARIO FROM USUARIO WHERE UID_FIREBASE = ?)",
                Long.class, uidAdmin);
        assertThat(eventosGlobales).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Consulta global: incluye eventos sin grupo y filtra por idGrupo
    // ------------------------------------------------------------------

    @Test
    void globalIncluyeEventosSinGrupoYFiltraPorIdGrupo() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        Escenario e = escenarioCoordinador();

        // ID_ENTIDAD propio: la base es compartida y el reloj fijo de esta
        // prueba coincide con la fecha real, así que sin este filtro los
        // eventos de otras pruebas pueden empujar estos fuera de la página.
        String idEntidad = "global-" + FixturaGrupos.uidUnico();
        long idSinGrupo = registrarExito(null, "USUARIO", idEntidad, e.idCoordinador, "ACCION_SIN_GRUPO");
        long idDelGrupo = registrarExito(e.idGrupo, "GRUPO_FAMILIAR", idEntidad, e.idCoordinador, "ACCION_DEL_GRUPO");
        autenticar(uidAdmin);

        FiltroConsultaAuditoria filtroEntidad =
                new FiltroConsultaAuditoria(null, null, idEntidad, null, null, null, null);
        RespuestaPagina<RespuestaEventoAuditoria> sinFiltro = servicio.consultarGlobal(filtroEntidad, 0, 100);
        assertThat(sinFiltro.elementos()).extracting(RespuestaEventoAuditoria::idEvento)
                .contains(idSinGrupo, idDelGrupo);

        FiltroConsultaAuditoria filtroGrupo =
                new FiltroConsultaAuditoria(e.idGrupo, null, idEntidad, null, null, null, null);
        RespuestaPagina<RespuestaEventoAuditoria> conFiltro = servicio.consultarGlobal(filtroGrupo, 0, 100);
        assertThat(conFiltro.elementos()).extracting(RespuestaEventoAuditoria::idEvento)
                .contains(idDelGrupo)
                .doesNotContain(idSinGrupo);
    }

    /**
     * Corrección ronda 1 (Important 1): un {@code idGrupo} inexistente en el
     * filtro de la consulta global no debe romper con un 409 por la FK
     * {@code FK_AUDITORIA_GRUPO} al auditar la consulta. Debe devolver una
     * página vacía (no hay ningún {@code EVENTO_AUDITORIA} con ese grupo) y
     * la consulta en sí queda auditada con {@code ID_GRUPO = null} (nunca
     * con el id inexistente), llevando ese {@code idGrupo} en los detalles.
     */
    @Test
    void globalConIdGrupoInexistenteDaPaginaVaciaYAuditaConGrupoNulo() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);
        long idGrupoInexistente = 999_999_999L;

        RespuestaPagina<RespuestaEventoAuditoria> respuesta = servicio.consultarGlobal(
                new FiltroConsultaAuditoria(idGrupoInexistente, null, null, null, null, null, null), 0, 20);

        assertThat(respuesta.elementos()).isEmpty();
        assertThat(respuesta.total()).isZero();

        List<Map<String, Object>> filas = jdbcTemplate.queryForList(
                "SELECT ID_GRUPO, DETALLES_JSON FROM EVENTO_AUDITORIA WHERE CODIGO_ACCION = 'CONSULTAR_AUDITORIA' "
                        + "AND ID_USUARIO_ACTOR = (SELECT ID_USUARIO FROM USUARIO WHERE UID_FIREBASE = ?)",
                uidAdmin);
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).get("ID_GRUPO")).isNull();
        Map<String, Object> detalles = jsonMapper.readValue((String) filas.get(0).get("DETALLES_JSON"),
                new TypeReference<Map<String, Object>>() {
                });
        assertThat(((Number) detalles.get("idGrupo")).longValue()).isEqualTo(idGrupoInexistente);
    }

    // ------------------------------------------------------------------
    // Actor nulo = proceso automático
    // ------------------------------------------------------------------

    @Test
    void actorNuloSeMuestraComoProcesoAutomatico() {
        Escenario e = escenarioCoordinador();
        long idEvento = registrarExitoSinActor(e.idGrupo, "USUARIO", "1", "ACCION_AUTOMATICA");
        autenticar(e.uid);

        RespuestaPagina<RespuestaEventoAuditoria> respuesta = servicio.consultarGrupo(e.idGrupo,
                new FiltroConsultaAuditoria(null, null, null, "ACCION_AUTOMATICA", null, null, null), 0, 20);

        assertThat(respuesta.elementos()).hasSize(1);
        assertThat(respuesta.elementos().get(0).idEvento()).isEqualTo(idEvento);
        assertThat(respuesta.elementos().get(0).actor()).isNull();
    }

    // ------------------------------------------------------------------
    // Secretos anidados en mapas y listas: nunca se exponen (foco de revisión 4)
    // ------------------------------------------------------------------

    @Test
    void detallesConSecretosAnidadosNoSeExponen() throws Exception {
        // Corrección ronda 1 (Spec confirmado): se revisa el JSON de la respuesta HTTP real
        // (MockMvc sobre el controlador + servicio + Oracle, no una llamada directa al servicio),
        // con valores centinela únicos por caso (no "x"/"y"/"z", que podrían calzar por casualidad
        // con un id o una fecha).
        Escenario e = escenarioCoordinador();
        String centinela = "SECRETO-" + UUID.randomUUID();
        String secretoToken = centinela + "-TOKEN";
        String secretoClave = centinela + "-CLAVE";
        String secretoPassword = centinela + "-PASSWORD";
        String detallesConSecretos = "{\"token\":\"" + secretoToken + "\",\"datos\":{\"clave\":\"" + secretoClave
                + "\"},\"lista\":[{\"password\":\"" + secretoPassword + "\",\"ok\":1}],\"idUsuario\":5}";
        crearEvento(e.idGrupo, "USUARIO", "1", e.idCoordinador, "ACCION_CON_SECRETOS", "EXITO",
                HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime(), detallesConSecretos);

        String cuerpo = mockMvc.perform(get("/api/grupos/{idGrupo}/auditoria", e.idGrupo)
                        .with(jwt().jwt(constructor -> constructor.subject(e.uid())))
                        .param("codigoAccion", "ACCION_CON_SECRETOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].detalles.idUsuario").value(5))
                .andExpect(jsonPath("$.elementos[0].detalles.lista[0].ok").value(1))
                .andExpect(jsonPath("$.elementos[0].detalles.token").doesNotExist())
                .andExpect(jsonPath("$.elementos[0].detalles.datos.clave").doesNotExist())
                .andExpect(jsonPath("$.elementos[0].detalles.lista[0].password").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        // Ni los valores centinela (ninguna variante) ni los nombres de clave sensibles aparecen
        // en ningún lugar del cuerpo completo (defensa en profundidad sobre la aserción por jsonPath).
        assertThat(cuerpo).doesNotContain(centinela, "token", "clave", "password");
        assertThat(cuerpo).contains("\"idUsuario\":5", "\"ok\":1");
    }

    // ------------------------------------------------------------------
    // Registro de reasignaciones (HU-06), consultadas desde la auditoría
    // ------------------------------------------------------------------

    @Test
    void registroDeCambiosYReasignaciones() {
        Escenario e = escenarioCoordinador();
        long idColaboradorA = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(e.idGrupo, idColaboradorA, "COLABORADOR", "ACTIVO");
        long idColaboradorB = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(e.idGrupo, idColaboradorB, "COLABORADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(e.idGrupo, e.idCoordinador, "ACTIVO");
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                HOY.atTime(9, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime());
        autenticar(e.uid);

        servicioAsignacion.asignar(e.idGrupo, idTarea, new SolicitudAsignarTarea(idColaboradorA, "Primera"));
        servicioAsignacion.asignar(e.idGrupo, idTarea, new SolicitudAsignarTarea(idColaboradorB, "Reasignación"));

        RespuestaPagina<RespuestaEventoAuditoria> respuesta = servicio.consultarGrupo(e.idGrupo,
                new FiltroConsultaAuditoria(null, "TAREA_CUIDADO", null, null, null, null, null), 0, 20);

        RespuestaEventoAuditoria reasignacion = respuesta.elementos().stream()
                .filter(evento -> "REASIGNAR_TAREA".equals(evento.codigoAccion()))
                .findFirst().orElseThrow();
        assertThat(((Number) reasignacion.detalles().get("idUsuarioAnterior")).longValue())
                .isEqualTo(idColaboradorA);
        assertThat(((Number) reasignacion.detalles().get("idUsuarioNuevo")).longValue()).isEqualTo(idColaboradorB);
    }

    // ------------------------------------------------------------------
    // Inmutabilidad (regla 14): el intento de modificar se rechaza
    // ------------------------------------------------------------------

    @Test
    void intentoDeModificarAuditoriaSeRechaza() {
        Escenario e = escenarioCoordinador();
        long idEvento = registrarExito(e.idGrupo, "USUARIO", "1", e.idCoordinador, "ACCION_INMUTABLE");

        DataAccessException excepcionUpdate = assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE EVENTO_AUDITORIA SET CODIGO_ACCION = 'OTRA' WHERE ID_EVENTO_AUDITORIA = ?", idEvento));
        assertThat(excepcionUpdate.getMessage()).contains("ORA-20010");

        DataAccessException excepcionDelete = assertThrows(DataAccessException.class, () -> jdbcTemplate
                .update("DELETE FROM EVENTO_AUDITORIA WHERE ID_EVENTO_AUDITORIA = ?", idEvento));
        assertThat(excepcionDelete.getMessage()).contains("ORA-20010");

        String codigoAccionActual = jdbcTemplate.queryForObject(
                "SELECT CODIGO_ACCION FROM EVENTO_AUDITORIA WHERE ID_EVENTO_AUDITORIA = ?", String.class, idEvento);
        assertThat(codigoAccionActual).isEqualTo("ACCION_INMUTABLE");
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private Escenario escenarioCoordinador() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        return new Escenario(uid, idCoordinador, idGrupo);
    }

    private record Escenario(String uid, long idCoordinador, long idGrupo) {
    }

    private long registrarExito(Long idGrupo, String tipoEntidad, String idEntidad, long idUsuarioActor,
            String codigoAccion) {
        servicioAuditoria.registrar(new EventoARegistrar("fixtura", tipoEntidad, idEntidad, idGrupo, idUsuarioActor,
                codigoAccion, ResultadoAuditoria.EXITO, null));
        return ultimoIdInsertado();
    }

    private long registrarExitoSinActor(Long idGrupo, String tipoEntidad, String idEntidad, String codigoAccion) {
        servicioAuditoria.registrar(new EventoARegistrar("fixtura", tipoEntidad, idEntidad, idGrupo, null,
                codigoAccion, ResultadoAuditoria.EXITO, null));
        return ultimoIdInsertado();
    }

    private long ultimoIdInsertado() {
        return jdbcTemplate.queryForObject("SELECT MAX(ID_EVENTO_AUDITORIA) FROM EVENTO_AUDITORIA", Long.class);
    }

    private long contarConsultasAuditoria(long idGrupo) {
        Long cantidad = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'CONSULTAR_AUDITORIA'",
                Long.class, idGrupo);
        return cantidad == null ? 0 : cantidad;
    }

    /** Inserta un EVENTO_AUDITORIA con los valores exactos indicados (sin pasar por {@code ServicioAuditoria}). */
    private long crearEvento(Long idGrupo, String tipoEntidad, String idEntidad, Long idUsuarioActor,
            String codigoAccion, String codigoResultado, OffsetDateTime fechaEvento, String detallesJson) {
        long idResultado = jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'EVENTO_AUDITORIA' AND NOMBRE_CAMPO = 'RESULTADO' AND CODIGO_ESTADO = ?",
                Long.class, codigoResultado);
        KeyHolder generadorDeClave = new GeneratedKeyHolder();
        jdbcTemplate.update(conexion -> {
            PreparedStatement sentencia = conexion.prepareStatement(
                    "INSERT INTO EVENTO_AUDITORIA (NOMBRE_SERVICIO, TIPO_ENTIDAD, ID_ENTIDAD, ID_GRUPO, "
                            + "ID_USUARIO_ACTOR, CODIGO_ACCION, FECHA_EVENTO, ID_RESULTADO, DETALLES_JSON) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[] {"ID_EVENTO_AUDITORIA"});
            sentencia.setString(1, "fixtura");
            sentencia.setString(2, tipoEntidad);
            sentencia.setString(3, idEntidad);
            sentencia.setObject(4, idGrupo);
            sentencia.setObject(5, idUsuarioActor);
            sentencia.setString(6, codigoAccion);
            sentencia.setObject(7, fechaEvento);
            sentencia.setLong(8, idResultado);
            sentencia.setString(9, detallesJson);
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
