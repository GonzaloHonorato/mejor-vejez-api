package cl.adndigital.mejorvejez.sucesion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.grupos.RolGrupo;
import cl.adndigital.mejorvejez.grupos.ServicioIntegrantes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;
import cl.adndigital.mejorvejez.tareas.RespuestaTarea;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioSucesion} contra Oracle real (Testcontainers), con el
 * reloj de negocio fijo el lunes 2026-11-02 10:00 (America/Santiago), igual
 * que {@code relevos.ServicioRelevosTest}. Datos propios por prueba
 * (grupos/usuarios con UUID); nunca se cuentan tablas completas (la BD es
 * compartida). Todos los usuarios llevan ids explícitos &gt; 127 (fuera de
 * la caché de {@code Long}), para que una comparación de ids con {@code ==}
 * en vez de {@code Objects.equals} falle aquí (lección de la Fase 2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConfiguracionTestcontainers.class)
class ServicioSucesionTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);
    private static final OffsetDateTime AHORA = HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toOffsetDateTime();

    /**
     * Rango propio de ids de usuario explícitos, sin cruzarse con los de
     * otras pruebas que comparten la BD (900.000.000 de {@code
     * ServicioDetallesPlanTest}/{@code ServicioContactosTest}, 950.000.000
     * de {@code tareas.FixturaGeneracion}, 960.000.000 de {@code
     * relevos.ServicioRelevosTest}, 982 a 985.000.000 de {@code
     * interno}/{@code alertas}) ni con el 993.000.000 de {@link
     * ConcurrenciaSucesionTest}.
     */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(990_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioSucesion servicio;

    @Autowired
    private ServicioIntegrantes servicioIntegrantes;

    @Autowired
    private RepositorioConsultaSucesion repositorioConsulta;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

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
    // Proponer (reglas 16, 17 y 23)
    // ------------------------------------------------------------------

    @Test
    void proponerCreaPendienteYAudita() throws Exception {
        Escenario e = escenario();
        autenticar(e.coordinador().uid());

        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        assertThat(propuesta.idPropuesta()).isNotNull();
        assertThat(propuesta.idGrupo()).isEqualTo(e.idGrupo());
        assertThat(propuesta.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_PENDIENTE);
        assertThat(propuesta.saliente().idUsuario()).isEqualTo(e.coordinador().idUsuario());
        assertThat(propuesta.saliente().nombre()).isNotBlank();
        assertThat(propuesta.candidato().idUsuario()).isEqualTo(e.candidato().idUsuario());
        assertThat(propuesta.fechaCreacion()).isEqualTo(AHORA);
        assertThat(propuesta.fechaCreacion().getOffset()).isEqualTo(AHORA.getOffset());
        assertThat(propuesta.fechaRespuesta()).isNull();
        assertThat(propuesta.fechaCancelacion()).isNull();
        assertThat(propuesta.fechaCompletado()).isNull();
        assertThat(propuesta.motivoCancelacion()).isNull();
        assertThat(propuesta.condicionesPendientes()).isEmpty();
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("PENDIENTE");

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT NOMBRE_SERVICIO, TIPO_ENTIDAD, ID_ENTIDAD, ID_USUARIO_ACTOR, DETALLES_JSON "
                        + "FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'PROPONER_SUCESION'",
                e.idGrupo());
        assertThat(eventos).hasSize(1);
        Map<String, Object> evento = eventos.get(0);
        assertThat(evento.get("NOMBRE_SERVICIO")).isEqualTo("sucesion");
        assertThat(evento.get("TIPO_ENTIDAD")).isEqualTo("PROPUESTA_SUCESION");
        assertThat(evento.get("ID_ENTIDAD")).isEqualTo(String.valueOf(propuesta.idPropuesta()));
        assertThat(((Number) evento.get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(e.coordinador().idUsuario());
        Map<String, Object> detalles = detalles(evento);
        assertThat(((Number) detalles.get("idUsuarioCandidato")).longValue()).isEqualTo(e.candidato().idUsuario());
        assertThat(detalles).doesNotContainKey("accesoAdministrador");
    }

    @Test
    void candidatoNoIntegranteDa422() {
        Escenario e = escenario();
        UsuarioAlto ajeno = crearUsuarioAlto(false);
        autenticar(e.coordinador().uid());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), ajeno.idUsuario()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioSucesion.MENSAJE_CANDIDATO_NO_INTEGRANTE);
        assertThat(contarPropuestas(e.idGrupo())).isZero();
    }

    @Test
    void candidatoIntegranteInactivoDa422() {
        Escenario e = escenario();
        UsuarioAlto inactivo = crearUsuarioAlto(false);
        fixturaGrupos.agregarIntegrante(e.idGrupo(), inactivo.idUsuario(), "COLABORADOR", "INACTIVO");
        autenticar(e.coordinador().uid());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), inactivo.idUsuario()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioSucesion.MENSAJE_CANDIDATO_NO_INTEGRANTE);
    }

    @Test
    void candidatoCuentaInactivaDa422() {
        Escenario e = escenario();
        // La membresía queda ACTIVA; lo que se inactiva es la cuenta global USUARIO.
        inactivarCuenta(e.candidato().idUsuario());
        autenticar(e.coordinador().uid());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), e.candidato().idUsuario()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioSucesion.MENSAJE_CUENTA_CANDIDATO_INACTIVA);
        assertThat(contarPropuestas(e.idGrupo())).isZero();
    }

    @Test
    void candidatoYaCoordinadorDa422() {
        Escenario e = escenario();
        UsuarioAlto otroCoordinador = crearIntegranteAlto(e.idGrupo(), "COORDINADOR");
        autenticar(e.coordinador().uid());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), otroCoordinador.idUsuario()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioSucesion.MENSAJE_CANDIDATO_YA_COORDINADOR);
        assertThat(contarPropuestas(e.idGrupo())).isZero();
    }

    @Test
    void proponerseASiMismoDa422() {
        Escenario e = escenario();
        autenticar(e.coordinador().uid());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), e.coordinador().idUsuario()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioSucesion.MENSAJE_PROPONERSE_A_SI_MISMO);
        assertThat(contarPropuestas(e.idGrupo())).isZero();
    }

    @Test
    void grupoInactivoDa422() {
        Escenario e = escenario();
        jdbcTemplate.update("UPDATE GRUPO_FAMILIAR SET ID_ESTADO = ? WHERE ID_GRUPO = ?",
                idEstado("GRUPO_FAMILIAR", "INACTIVO"), e.idGrupo());
        autenticar(e.coordinador().uid());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), e.candidato().idUsuario()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioSucesion.MENSAJE_GRUPO_INACTIVO);
        assertThat(contarPropuestas(e.idGrupo())).isZero();
    }

    @Test
    void colaboradorNoPuedeProponerDa403() {
        Escenario e = escenario();
        UsuarioAlto otroColaborador = crearIntegranteAlto(e.idGrupo(), "COLABORADOR");
        autenticar(e.candidato().uid());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), otroColaborador.idUsuario()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioSucesion.MENSAJE_SOLO_COORDINADOR_PROPONE);
        assertThat(contarPropuestas(e.idGrupo())).isZero();

        // El 403 de dominio se audita como RECHAZADA, con motivo en código.
        List<Map<String, Object>> eventos = eventosDeResultado(e.idGrupo(), "PROPONER_SUCESION", "RECHAZADA");
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue())
                .isEqualTo(e.candidato().idUsuario());
        assertThat(detalles(eventos.get(0)).get("motivo")).isEqualTo("NO_ES_COORDINADOR");
    }

    @Test
    void noMiembroNoPuedeProponerDa403() {
        Escenario e = escenario();
        UsuarioAlto ajeno = crearUsuarioAlto(false);
        autenticar(ajeno.uid());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), e.candidato().idUsuario()))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThat(contarPropuestas(e.idGrupo())).isZero();
    }

    @Test
    void segundaPropuestaActivaDa409() {
        Escenario e = escenario();
        UsuarioAlto otroCandidato = crearIntegranteAlto(e.idGrupo(), "COLABORADOR");
        autenticar(e.coordinador().uid());
        RespuestaPropuesta primera = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), otroCandidato.idUsuario()))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage(ServicioSucesion.MENSAJE_YA_EXISTE_ACTIVA);

        // También con la primera ya ACEPTADA (sigue activa hasta completarse o cancelarse).
        autenticar(e.candidato().uid());
        servicio.aceptar(e.idGrupo(), primera.idPropuesta());
        autenticar(e.coordinador().uid());
        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), otroCandidato.idUsuario()))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage(ServicioSucesion.MENSAJE_YA_EXISTE_ACTIVA);
        assertThat(contarPropuestas(e.idGrupo())).isEqualTo(1);
    }

    @Test
    void administradorNoPuedeAceptarNiProponer() {
        Escenario e = escenario();
        UsuarioAlto administrador = crearUsuarioAlto(true);

        autenticar(administrador.uid());
        assertThatThrownBy(() -> servicio.proponer(e.idGrupo(), e.candidato().idUsuario()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioSucesion.MENSAJE_SOLO_COORDINADOR_PROPONE);
        assertThat(contarPropuestas(e.idGrupo())).isZero();

        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        autenticar(administrador.uid());
        assertThatThrownBy(() -> servicio.aceptar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioSucesion.MENSAJE_SOLO_CANDIDATO_RESPONDE);
        assertThatThrownBy(() -> servicio.rechazar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioSucesion.MENSAJE_SOLO_CANDIDATO_RESPONDE);
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("PENDIENTE");

        // El administrador sí puede leer (exigirMiembro lo deja pasar).
        assertThat(servicio.obtener(e.idGrupo(), propuesta.idPropuesta()).idPropuesta())
                .isEqualTo(propuesta.idPropuesta());
    }

    // ------------------------------------------------------------------
    // Aceptar / rechazar (reglas 16, 19 y 20)
    // ------------------------------------------------------------------

    @Test
    void aceptacionConTareasPendientes() throws Exception {
        Escenario e = escenario();
        long idTareaTarde = tareaAsignada(e, e.coordinador().idUsuario(), AHORA.plusHours(5));
        long idTareaTemprano = tareaAsignada(e, e.coordinador().idUsuario(), AHORA.plusHours(1));
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        autenticar(e.candidato().uid());
        RespuestaPropuesta aceptada = servicio.aceptar(e.idGrupo(), propuesta.idPropuesta());

        assertThat(aceptada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_ACEPTADA);
        assertThat(aceptada.fechaRespuesta()).isEqualTo(AHORA);
        assertThat(aceptada.fechaCompletado()).isNull();
        assertThat(aceptada.condicionesPendientes()).extracting(RespuestaTarea::idTarea)
                .containsExactly(idTareaTemprano, idTareaTarde);
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("ACEPTADA");
        // La aceptación no cambia los roles (HU-13).
        assertThat(rolDe(e.idGrupo(), e.coordinador().idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COLABORADOR");

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, DETALLES_JSON FROM EVENTO_AUDITORIA "
                        + "WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'ACEPTAR_SUCESION'",
                e.idGrupo());
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue())
                .isEqualTo(e.candidato().idUsuario());
        Map<String, Object> detalles = detalles(eventos.get(0));
        assertThat(((Number) detalles.get("condicionesPendientes")).intValue()).isEqualTo(2);
        assertThat(((List<?>) detalles.get("idsTareasPendientes")).stream()
                .map(id -> ((Number) id).longValue()).toList())
                .containsExactly(idTareaTemprano, idTareaTarde);

        // La activa la refleja con las mismas condiciones.
        Optional<RespuestaPropuesta> activa = servicio.activa(e.idGrupo());
        assertThat(activa).isPresent();
        assertThat(activa.get().condicionesPendientes()).hasSize(2);
    }

    @Test
    void aceptarSinCondicionesNoCompletaSolo() {
        Escenario e = escenario();
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        autenticar(e.candidato().uid());
        RespuestaPropuesta aceptada = servicio.aceptar(e.idGrupo(), propuesta.idPropuesta());

        assertThat(aceptada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_ACEPTADA);
        assertThat(aceptada.condicionesPendientes()).isEmpty();
        assertThat(aceptada.fechaCompletado()).isNull();
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("ACEPTADA");
        assertThat(rolDe(e.idGrupo(), e.coordinador().idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COLABORADOR");
    }

    @Test
    void aceptarConCuentaInactivaNoCambiaLaPropuesta() {
        Escenario e = escenario();
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        // La cuenta del candidato se inactiva después de proponer: ServicioUsuarioActual ya lo
        // rechaza (403) antes de llegar a la revalidación de la regla 20, que queda como defensa
        // en profundidad. Lo que importa: la propuesta no cambia.
        inactivarCuenta(e.candidato().idUsuario());

        autenticar(e.candidato().uid());
        assertThatThrownBy(() -> servicio.aceptar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("PENDIENTE");
    }

    /**
     * Si promueven al candidato a COORDINADOR mientras la
     * propuesta sigue PENDIENTE, aceptar revalida el rol y la rechaza (en vez de dejarla
     * ACEPTADA sabiendo que completar fallará igual).
     */
    @Test
    void aceptarConCandidatoYaCoordinadorDa422() {
        Escenario e = escenario();
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        long idIntegranteCandidato = idIntegranteDe(e.idGrupo(), e.candidato().idUsuario());
        servicioIntegrantes.cambiarRol(e.idGrupo(), idIntegranteCandidato, idRol(RolGrupo.COORDINADOR));

        autenticar(e.candidato().uid());
        assertThatThrownBy(() -> servicio.aceptar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage(ServicioSucesion.MENSAJE_CANDIDATO_YA_COORDINADOR);
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("PENDIENTE");
    }

    @Test
    void soloElCandidatoResponde() {
        Escenario e = escenario();
        UsuarioAlto tercero = crearIntegranteAlto(e.idGrupo(), "COLABORADOR");
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        // El saliente no puede responder su propia propuesta.
        assertThatThrownBy(() -> servicio.aceptar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioSucesion.MENSAJE_SOLO_CANDIDATO_RESPONDE);
        assertThatThrownBy(() -> servicio.rechazar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioSucesion.MENSAJE_SOLO_CANDIDATO_RESPONDE);

        // Un tercero del grupo tampoco.
        autenticar(tercero.uid());
        assertThatThrownBy(() -> servicio.aceptar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioSucesion.MENSAJE_SOLO_CANDIDATO_RESPONDE);
        assertThatThrownBy(() -> servicio.rechazar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage(ServicioSucesion.MENSAJE_SOLO_CANDIDATO_RESPONDE);

        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("PENDIENTE");

        // Cada 403 (de ambos actores, dos intentos cada uno) se
        // audita como RECHAZADA con motivo en código, nunca texto libre.
        List<Map<String, Object>> aceptarRechazado = eventosDeResultado(e.idGrupo(), "ACEPTAR_SUCESION", "RECHAZADA");
        assertThat(aceptarRechazado).hasSize(2);
        assertThat(aceptarRechazado).allSatisfy(evento -> assertThat(detalles(evento).get("motivo"))
                .isEqualTo("NO_ES_CANDIDATO"));
        List<Map<String, Object>> rechazarRechazado =
                eventosDeResultado(e.idGrupo(), "RECHAZAR_SUCESION", "RECHAZADA");
        assertThat(rechazarRechazado).hasSize(2);
        assertThat(rechazarRechazado).allSatisfy(evento -> assertThat(detalles(evento).get("motivo"))
                .isEqualTo("NO_ES_CANDIDATO"));
    }

    @Test
    void rechazarCierraYConservaHistorial() {
        Escenario e = escenario();
        tareaAsignada(e, e.coordinador().idUsuario(), AHORA.plusHours(1));
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        autenticar(e.candidato().uid());
        RespuestaPropuesta rechazada = servicio.rechazar(e.idGrupo(), propuesta.idPropuesta());

        assertThat(rechazada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_RECHAZADA);
        assertThat(rechazada.fechaRespuesta()).isEqualTo(AHORA);
        // Fuera de PENDIENTE/ACEPTADA las condiciones van vacías, aunque el saliente tenga tareas (regla 19).
        assertThat(rechazada.condicionesPendientes()).isEmpty();
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("RECHAZADA");
        assertThat(servicio.activa(e.idGrupo())).isEmpty();
        assertThat(contarEventos(e.idGrupo(), "RECHAZAR_SUCESION")).isEqualTo(1);

        // Permite una nueva propuesta, y el historial conserva las dos (más reciente primero).
        autenticar(e.coordinador().uid());
        RespuestaPropuesta nueva = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        List<RespuestaPropuesta> historial = servicio.historial(e.idGrupo());
        assertThat(historial).extracting(RespuestaPropuesta::idPropuesta)
                .containsExactly(nueva.idPropuesta(), propuesta.idPropuesta());
        assertThat(historial).extracting(RespuestaPropuesta::codigoEstado)
                .containsExactly(CodigosSucesion.ESTADO_PENDIENTE, CodigosSucesion.ESTADO_RECHAZADA);
        // El historial va sin condiciones (regla 15).
        assertThat(historial).allSatisfy(fila -> assertThat(fila.condicionesPendientes()).isEmpty());
        assertThat(historial.get(1).candidato().nombre()).isNotBlank();
    }

    // ------------------------------------------------------------------
    // Cancelar (reglas 16 y 23)
    // ------------------------------------------------------------------

    @Test
    void cancelacionDespuesDeAceptar() throws Exception {
        Escenario e = escenario();
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        autenticar(e.candidato().uid());
        servicio.aceptar(e.idGrupo(), propuesta.idPropuesta());

        String motivo = "MOTIVO-LIBRE-" + UUID.randomUUID();
        autenticar(e.coordinador().uid());
        RespuestaPropuesta cancelada = servicio.cancelar(e.idGrupo(), propuesta.idPropuesta(), "  " + motivo + "  ");

        assertThat(cancelada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_CANCELADA);
        assertThat(cancelada.motivoCancelacion()).isEqualTo(motivo);
        assertThat(cancelada.fechaCancelacion()).isEqualTo(AHORA);
        assertThat(cancelada.fechaRespuesta()).isEqualTo(AHORA);
        assertThat(cancelada.condicionesPendientes()).isEmpty();
        Map<String, Object> fila = jdbcTemplate.queryForMap(
                "SELECT FECHA_CANCELACION, MOTIVO_CANCELACION FROM PROPUESTA_SUCESION WHERE ID_PROPUESTA = ?",
                propuesta.idPropuesta());
        assertThat(fila.get("FECHA_CANCELACION")).isNotNull();
        assertThat(fila.get("MOTIVO_CANCELACION")).isEqualTo(motivo);
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("CANCELADA");
        // Roles intactos.
        assertThat(rolDe(e.idGrupo(), e.coordinador().idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(e.idGrupo(), e.candidato().idUsuario())).isEqualTo("COLABORADOR");

        // Auditoría: estado anterior, nunca el motivo (texto libre).
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'CANCELAR_SUCESION'",
                e.idGrupo());
        assertThat(eventos).hasSize(1);
        assertThat((String) eventos.get(0).get("DETALLES_JSON")).doesNotContain(motivo);
        assertThat(detalles(eventos.get(0)).get("estadoAnterior")).isEqualTo(CodigosSucesion.ESTADO_ACEPTADA);

        // Permite una nueva propuesta.
        assertThat(servicio.activa(e.idGrupo())).isEmpty();
        RespuestaPropuesta nueva = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        assertThat(nueva.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_PENDIENTE);
    }

    @Test
    void cancelarPendienteSinMotivoYAdministradorPuedeCancelar() throws Exception {
        Escenario e = escenario();
        UsuarioAlto administrador = crearUsuarioAlto(true);
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        autenticar(administrador.uid());
        RespuestaPropuesta cancelada = servicio.cancelar(e.idGrupo(), propuesta.idPropuesta(), "   ");

        assertThat(cancelada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_CANCELADA);
        assertThat(cancelada.motivoCancelacion()).isNull();
        assertThat(cancelada.fechaRespuesta()).isNull();
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'CANCELAR_SUCESION'",
                e.idGrupo());
        assertThat(eventos).hasSize(1);
        Map<String, Object> detalles = detalles(eventos.get(0));
        assertThat(detalles.get("estadoAnterior")).isEqualTo(CodigosSucesion.ESTADO_PENDIENTE);
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
    }

    /**
     * Un colaborador que no es el saliente sigue recibiendo
     * 403 al cancelar (ya no basta con ser un COLABORADOR del grupo; hay que ser el saliente, un
     * COORDINADOR activo o un administrador). El candidato, colaborador en este escenario,
     * también cae en este caso.
     */
    @Test
    void cancelarPorTerceroDa403() {
        Escenario e = escenario();
        UsuarioAlto tercero = crearIntegranteAlto(e.idGrupo(), "COLABORADOR");
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        for (String uid : List.of(tercero.uid(), e.candidato().uid())) {
            autenticar(uid);
            assertThatThrownBy(() -> servicio.cancelar(e.idGrupo(), propuesta.idPropuesta(), null))
                    .isInstanceOf(ExcepcionAccesoDenegado.class)
                    .hasMessage(ServicioSucesion.MENSAJE_SOLO_SALIENTE_CANCELA);
        }
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("PENDIENTE");

        // Ambos 403 se auditan como RECHAZADA con motivo en código.
        List<Map<String, Object>> rechazados = eventosDeResultado(e.idGrupo(), "CANCELAR_SUCESION", "RECHAZADA");
        assertThat(rechazados).hasSize(2);
        assertThat(rechazados).allSatisfy(evento -> assertThat(detalles(evento).get("motivo"))
                .isEqualTo("NO_ES_SALIENTE_NI_COORDINADOR"));
    }

    /**
     * En un grupo con dos coordinadores, si el saliente sale
     * del grupo (acá, lo desactiva el otro coordinador con {@code ServicioIntegrantes.desactivar}),
     * la propuesta ya no queda huérfana: el otro coordinador activo puede cancelarla y, con eso
     * resuelto, volver a proponer.
     */
    @Test
    void otroCoordinadorCancelaCuandoElSalienteYaNoEsIntegranteActivo() {
        Escenario e = escenario();
        UsuarioAlto otroCoordinador = crearIntegranteAlto(e.idGrupo(), "COORDINADOR");
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        // El saliente sale del grupo: el otro coordinador lo desactiva (sigue habiendo un
        // coordinador activo, así que la regla del último coordinador no lo bloquea).
        long idIntegranteSaliente = idIntegranteDe(e.idGrupo(), e.coordinador().idUsuario());
        autenticar(otroCoordinador.uid());
        servicioIntegrantes.desactivar(e.idGrupo(), idIntegranteSaliente);

        RespuestaPropuesta cancelada = servicio.cancelar(e.idGrupo(), propuesta.idPropuesta(), null);
        assertThat(cancelada.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_CANCELADA);
        assertThat(estadoPropuesta(propuesta.idPropuesta())).isEqualTo("CANCELADA");

        // Y, con la propuesta huérfana ya cerrada, el otro coordinador puede proponer de nuevo.
        RespuestaPropuesta nueva = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        assertThat(nueva.codigoEstado()).isEqualTo(CodigosSucesion.ESTADO_PENDIENTE);
        assertThat(nueva.saliente().idUsuario()).isEqualTo(otroCoordinador.idUsuario());
    }

    @Test
    void transicionInvalidaDa409() {
        Escenario e = escenario();
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        autenticar(e.candidato().uid());
        servicio.rechazar(e.idGrupo(), propuesta.idPropuesta());

        // Aceptar o rechazar una RECHAZADA.
        assertThatThrownBy(() -> servicio.aceptar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA);
        assertThatThrownBy(() -> servicio.rechazar(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA);
        // Cancelar una RECHAZADA.
        autenticar(e.coordinador().uid());
        assertThatThrownBy(() -> servicio.cancelar(e.idGrupo(), propuesta.idPropuesta(), null))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA);

        // Cancelar una COMPLETADA (aquí se fuerza el estado por JDBC).
        RespuestaPropuesta otra = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        jdbcTemplate.update("UPDATE PROPUESTA_SUCESION SET ID_ESTADO = ?, FECHA_COMPLETADO = SYSTIMESTAMP "
                + "WHERE ID_PROPUESTA = ?", idEstado("PROPUESTA_SUCESION", "COMPLETADA"), otra.idPropuesta());
        assertThatThrownBy(() -> servicio.cancelar(e.idGrupo(), otra.idPropuesta(), "tarde"))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA);
        assertThat(estadoPropuesta(otra.idPropuesta())).isEqualTo("COMPLETADA");
    }

    // ------------------------------------------------------------------
    // Consultas (regla 15)
    // ------------------------------------------------------------------

    @Test
    void activaDevuelve204SinPropuesta() throws Exception {
        Escenario e = escenario();
        autenticar(e.coordinador().uid());
        assertThat(servicio.activa(e.idGrupo())).isEmpty();
        assertThat(servicio.historial(e.idGrupo())).isEmpty();

        mockMvc.perform(get("/api/grupos/{idGrupo}/sucesion/activa", e.idGrupo())
                        .with(jwt().jwt(constructor -> constructor.subject(e.coordinador().uid()))))
                .andExpect(status().isNoContent());

        // MockMvc limpia el SecurityContext al terminar la petición: se vuelve a autenticar.
        autenticar(e.coordinador().uid());
        servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        mockMvc.perform(get("/api/grupos/{idGrupo}/sucesion/activa", e.idGrupo())
                        .with(jwt().jwt(constructor -> constructor.subject(e.candidato().uid()))))
                .andExpect(status().isOk());
    }

    @Test
    void lecturasExigenMembresia() {
        Escenario e = escenario();
        UsuarioAlto ajeno = crearUsuarioAlto(false);
        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());

        autenticar(ajeno.uid());
        assertThatThrownBy(() -> servicio.activa(e.idGrupo())).isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.historial(e.idGrupo())).isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.obtener(e.idGrupo(), propuesta.idPropuesta()))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void propuestaDeOtroGrupoDa404() {
        Escenario propio = escenario();
        Escenario ajeno = escenario();
        autenticar(ajeno.coordinador().uid());
        RespuestaPropuesta propuestaAjena = servicio.proponer(ajeno.idGrupo(), ajeno.candidato().idUsuario());

        autenticar(propio.coordinador().uid());
        assertThatThrownBy(() -> servicio.obtener(propio.idGrupo(), propuestaAjena.idPropuesta()))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage(ServicioSucesion.MENSAJE_PROPUESTA_NO_EXISTE);
        assertThatThrownBy(() -> servicio.cancelar(propio.idGrupo(), propuestaAjena.idPropuesta(), null))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage(ServicioSucesion.MENSAJE_PROPUESTA_NO_EXISTE);
        autenticar(propio.candidato().uid());
        assertThatThrownBy(() -> servicio.aceptar(propio.idGrupo(), propuestaAjena.idPropuesta()))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage(ServicioSucesion.MENSAJE_PROPUESTA_NO_EXISTE);
        assertThatThrownBy(() -> servicio.obtener(propio.idGrupo(), Long.MAX_VALUE))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage(ServicioSucesion.MENSAJE_PROPUESTA_NO_EXISTE);
        assertThat(estadoPropuesta(propuestaAjena.idPropuesta())).isEqualTo("PENDIENTE");
    }

    // ------------------------------------------------------------------
    // Condiciones pendientes (regla 19)
    // ------------------------------------------------------------------

    @Test
    void condicionesExcluyenTareasResueltasYDeOtros() {
        Escenario e = escenario();
        UsuarioAlto otroIntegrante = crearIntegranteAlto(e.idGrupo(), "COLABORADOR");
        long idSaliente = e.coordinador().idUsuario();

        // Cuentan: PENDIENTE y EN_CURSO asignadas al saliente (dos a la misma hora: desempata ID_TAREA).
        long idPendienteA = tareaAsignada(e, idSaliente, AHORA.plusHours(2));
        long idPendienteB = tareaAsignada(e, idSaliente, AHORA.plusHours(2));
        long idEnCurso = tareaAsignada(e, idSaliente, AHORA.minusDays(3));
        fixturaTareas.cambiarEstadoTarea(idEnCurso, CodigosTareas.ESTADO_TAREA_EN_CURSO);
        // No cuentan: resueltas del saliente, PENDIENTE de otro integrante, PENDIENTE sin asignar,
        // y PENDIENTE del saliente en otro grupo.
        long idCompletada = tareaAsignada(e, idSaliente, AHORA.plusHours(1));
        fixturaTareas.cambiarEstadoTarea(idCompletada, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        long idNoRealizada = tareaAsignada(e, idSaliente, AHORA.plusHours(1));
        fixturaTareas.cambiarEstadoTarea(idNoRealizada, CodigosTareas.ESTADO_TAREA_NO_REALIZADA);
        long idCancelada = tareaAsignada(e, idSaliente, AHORA.plusHours(1));
        fixturaTareas.cambiarEstadoTarea(idCancelada, CodigosTareas.ESTADO_TAREA_CANCELADA);
        tareaAsignada(e, otroIntegrante.idUsuario(), AHORA.plusHours(1));
        fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(), CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                AHORA.plusHours(1));
        long idOtroGrupo = fixturaGrupos.crearGrupo(idSaliente);
        fixturaGrupos.agregarIntegrante(idOtroGrupo, idSaliente, "COORDINADOR", "ACTIVO");
        long idAdultoOtroGrupo = fixturaAdultos.crearAdulto(idOtroGrupo, idSaliente, "ACTIVO");
        long idTareaOtroGrupo = fixturaTareas.crearTareaManual(idOtroGrupo, idAdultoOtroGrupo,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, AHORA.plusHours(1));
        fixturaTareas.crearAsignacion(idTareaOtroGrupo, idOtroGrupo, idSaliente, idSaliente);

        assertThat(repositorioConsulta.idsCondicionesPendientes(e.idGrupo(), idSaliente))
                .containsExactly(idEnCurso, Math.min(idPendienteA, idPendienteB), Math.max(idPendienteA, idPendienteB));

        autenticar(e.coordinador().uid());
        RespuestaPropuesta propuesta = servicio.proponer(e.idGrupo(), e.candidato().idUsuario());
        assertThat(propuesta.condicionesPendientes()).extracting(RespuestaTarea::idTarea)
                .containsExactly(idEnCurso, Math.min(idPendienteA, idPendienteB),
                        Math.max(idPendienteA, idPendienteB));
        assertThat(propuesta.condicionesPendientes().get(0).responsable().idUsuario()).isEqualTo(idSaliente);

        // Una tarea resuelta después deja de contar (se calculan en vivo).
        fixturaTareas.cambiarEstadoTarea(idEnCurso, CodigosTareas.ESTADO_TAREA_COMPLETADA);
        assertThat(servicio.obtener(e.idGrupo(), propuesta.idPropuesta()).condicionesPendientes())
                .extracting(RespuestaTarea::idTarea)
                .containsExactly(Math.min(idPendienteA, idPendienteB), Math.max(idPendienteA, idPendienteB));
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private record UsuarioAlto(String uid, long idUsuario) {
    }

    private record Escenario(UsuarioAlto coordinador, UsuarioAlto candidato, long idGrupo, long idAdulto) {
    }

    /** Grupo con un coordinador y un colaborador (el candidato), ambos ACTIVO y con ids &gt; 127, y un adulto. */
    private Escenario escenario() {
        UsuarioAlto coordinador = crearUsuarioAlto(false);
        long idGrupo = fixturaGrupos.crearGrupo(coordinador.idUsuario());
        fixturaGrupos.agregarIntegrante(idGrupo, coordinador.idUsuario(), "COORDINADOR", "ACTIVO");
        UsuarioAlto candidato = crearIntegranteAlto(idGrupo, "COLABORADOR");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, coordinador.idUsuario(), "ACTIVO");
        return new Escenario(coordinador, candidato, idGrupo, idAdulto);
    }

    private UsuarioAlto crearIntegranteAlto(long idGrupo, String rol) {
        UsuarioAlto usuario = crearUsuarioAlto(false);
        fixturaGrupos.agregarIntegrante(idGrupo, usuario.idUsuario(), rol, "ACTIVO");
        return usuario;
    }

    /** USUARIO ACTIVO con id explícito &gt; 127 (la identidad nunca llega a este rango en el contenedor). */
    private UsuarioAlto crearUsuarioAlto(boolean administrador) {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        String uid = FixturaGrupos.uidUnico();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                idUsuario, uid, "fixtura-" + UUID.randomUUID() + "@sucesion.mejorvejez.test",
                "Usuario sucesión " + idUsuario, administrador ? "S" : "N", idEstado("USUARIO", "ACTIVO"));
        return new UsuarioAlto(uid, idUsuario);
    }

    /** Tarea manual PENDIENTE del adulto del escenario, con asignación ACTIVO a {@code idAsignado}. */
    private long tareaAsignada(Escenario e, long idAsignado, OffsetDateTime horario) {
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo(), e.idAdulto(),
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario);
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo(), idAsignado, e.coordinador().idUsuario());
        return idTarea;
    }

    private void inactivarCuenta(long idUsuario) {
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?", idEstado("USUARIO", "INACTIVO"),
                idUsuario);
    }

    private String estadoPropuesta(long idPropuesta) {
        return jdbcTemplate.queryForObject(
                "SELECT ce.CODIGO_ESTADO FROM PROPUESTA_SUCESION p "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = p.ID_ESTADO WHERE p.ID_PROPUESTA = ?",
                String.class, idPropuesta);
    }

    private String rolDe(long idGrupo, long idUsuario) {
        return jdbcTemplate.queryForObject(
                "SELECT r.NOMBRE_ROL FROM INTEGRANTE_GRUPO i JOIN ROL_GRUPO r ON r.ID_ROL = i.ID_ROL "
                        + "WHERE i.ID_GRUPO = ? AND i.ID_USUARIO = ?",
                String.class, idGrupo, idUsuario);
    }

    private long contarPropuestas(long idGrupo) {
        Long cantidad = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM PROPUESTA_SUCESION WHERE ID_GRUPO = ?",
                Long.class, idGrupo);
        return cantidad == null ? 0 : cantidad;
    }

    private long contarEventos(long idGrupo, String codigoAccion) {
        Long cantidad = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = ?", Long.class,
                idGrupo, codigoAccion);
        return cantidad == null ? 0 : cantidad;
    }

    /** Eventos del grupo con la acción y el código de resultado (EXITO | FALLO | RECHAZADA) dados. */
    private List<Map<String, Object>> eventosDeResultado(long idGrupo, String codigoAccion, String resultado) {
        return jdbcTemplate.queryForList(
                "SELECT e.ID_USUARIO_ACTOR, e.DETALLES_JSON FROM EVENTO_AUDITORIA e "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = e.ID_RESULTADO "
                        + "WHERE e.ID_GRUPO = ? AND e.CODIGO_ACCION = ? AND ce.CODIGO_ESTADO = ?",
                idGrupo, codigoAccion, resultado);
    }

    /** Id de la membresía ACTIVA del usuario en el grupo. */
    private long idIntegranteDe(long idGrupo, long idUsuario) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_INTEGRANTE FROM INTEGRANTE_GRUPO WHERE ID_GRUPO = ? AND ID_USUARIO = ?", Long.class,
                idGrupo, idUsuario);
    }

    private long idRol(String nombreRol) {
        return jdbcTemplate.queryForObject("SELECT ID_ROL FROM ROL_GRUPO WHERE NOMBRE_ROL = ?", Long.class,
                nombreRol);
    }

    private Map<String, Object> detalles(Map<String, Object> evento) {
        return jsonMapper.readValue((String) evento.get("DETALLES_JSON"), new TypeReference<Map<String, Object>>() {
        });
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' "
                        + "AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
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
