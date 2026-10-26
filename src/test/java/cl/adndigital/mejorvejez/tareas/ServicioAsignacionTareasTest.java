package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;

/**
 * Prueba {@link ServicioAsignacionTareas} contra Oracle real (Testcontainers),
 * con el reloj de negocio fijo en el lunes 2026-11-02 10:00
 * (America/Santiago), igual que {@link ServicioEstadoTareasTest}. Datos
 * propios por prueba; nunca se cuentan tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioAsignacionTareasTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);
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
    private ServicioAsignacionTareas servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaTareas fixturaTareas;
    private FixturaGeneracion fixturaGeneracion;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaGeneracion = new FixturaGeneracion(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Asignación inicial y reasignación
    // ------------------------------------------------------------------

    @Test
    void asignacionInicial() {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");

        RespuestaTarea respuesta = servicio.asignar(e.idGrupo, idTarea,
                new SolicitudAsignarTarea(colaborador.idUsuario(), "Responsable inicial"));

        assertThat(respuesta.idTarea()).isEqualTo(idTarea);
        assertThat(respuesta.responsable()).isNotNull();
        assertThat(respuesta.responsable().idUsuario()).isEqualTo(colaborador.idUsuario());

        List<Map<String, Object>> asignaciones = asignacionesDeLaTarea(idTarea);
        assertThat(asignaciones).hasSize(1);
        assertThat(asignaciones.get(0).get("CODIGO_ESTADO")).isEqualTo("ACTIVO");
        assertThat(((Number) asignaciones.get(0).get("ID_USUARIO_ASIGNADO")).longValue())
                .isEqualTo(colaborador.idUsuario());
        assertThat(((Number) asignaciones.get(0).get("ID_USUARIO_ASIGNADOR")).longValue())
                .isEqualTo(e.idCoordinador);
        assertThat(asignaciones.get(0).get("MOTIVO")).isEqualTo("Responsable inicial");

        List<Map<String, Object>> eventos = eventos(idTarea, "ASIGNAR_TAREA");
        assertThat(eventos).hasSize(1);
        Map<String, Object> detalles = leerJson((String) eventos.get(0).get("DETALLES_JSON"));
        assertThat(((Number) detalles.get("idUsuarioNuevo")).longValue()).isEqualTo(colaborador.idUsuario());
        assertThat(detalles).doesNotContainKey("idUsuarioAnterior");
    }

    @Test
    void reasignacionAutorizada() {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto anterior = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo, anterior.idUsuario(), e.idCoordinador);
        FixturaGeneracion.UsuarioAlto nuevo = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo, "COLABORADOR",
                "ACTIVO");

        RespuestaTarea respuesta = servicio.asignar(e.idGrupo, idTarea,
                new SolicitudAsignarTarea(nuevo.idUsuario(), "Cambio de turno"));

        assertThat(respuesta.idTarea()).isEqualTo(idTarea);
        assertThat(respuesta.responsable().idUsuario()).isEqualTo(nuevo.idUsuario());

        List<Map<String, Object>> asignaciones = asignacionesDeLaTarea(idTarea);
        assertThat(asignaciones).hasSize(2);

        Map<String, Object> filaAnterior = asignaciones.stream()
                .filter(fila -> ((Number) fila.get("ID_USUARIO_ASIGNADO")).longValue() == anterior.idUsuario())
                .findFirst().orElseThrow();
        assertThat(filaAnterior.get("CODIGO_ESTADO")).isEqualTo("REEMPLAZADA");
        assertThat(filaAnterior.get("FECHA_TERMINO")).isNotNull();

        Map<String, Object> filaNueva = asignaciones.stream()
                .filter(fila -> ((Number) fila.get("ID_USUARIO_ASIGNADO")).longValue() == nuevo.idUsuario())
                .findFirst().orElseThrow();
        assertThat(filaNueva.get("CODIGO_ESTADO")).isEqualTo("ACTIVO");
        assertThat(filaNueva.get("MOTIVO")).isEqualTo("Cambio de turno");
    }

    @Test
    void conservaResponsableAnteriorEnAuditoria() {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto anterior = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        assertThat(anterior.idUsuario()).isGreaterThan(127);
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo, anterior.idUsuario(), e.idCoordinador);
        FixturaGeneracion.UsuarioAlto nuevo = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo, "COLABORADOR",
                "ACTIVO");
        assertThat(nuevo.idUsuario()).isGreaterThan(127);

        servicio.asignar(e.idGrupo, idTarea, new SolicitudAsignarTarea(nuevo.idUsuario(), null));

        List<Map<String, Object>> eventos = eventos(idTarea, "REASIGNAR_TAREA");
        assertThat(eventos).hasSize(1);
        Map<String, Object> detalles = leerJson((String) eventos.get(0).get("DETALLES_JSON"));
        assertThat(((Number) detalles.get("idUsuarioAnterior")).longValue()).isEqualTo(anterior.idUsuario());
        assertThat(((Number) detalles.get("idUsuarioNuevo")).longValue()).isEqualTo(nuevo.idUsuario());
    }

    // ------------------------------------------------------------------
    // Motivo opcional (trim y blanco -> null)
    // ------------------------------------------------------------------

    @Test
    void motivoEnBlancoQuedaNulo() {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");

        servicio.asignar(e.idGrupo, idTarea, new SolicitudAsignarTarea(colaborador.idUsuario(), "   "));

        List<Map<String, Object>> asignaciones = asignacionesDeLaTarea(idTarea);
        assertThat(asignaciones.get(0).get("MOTIVO")).isNull();
    }

    // ------------------------------------------------------------------
    // Ids cruzados (foco de revisión 4 de la Fase 4)
    // ------------------------------------------------------------------

    @Test
    void tareaDeOtroGrupoDevuelve404() {
        Escenario grupoA = escenario();
        Escenario grupoB = escenario();
        long idTareaDeA = tareaManual(grupoA);
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(grupoB.idGrupo,
                "COLABORADOR", "ACTIVO");
        autenticar(grupoB.uid);

        assertThatThrownBy(() -> servicio.asignar(grupoB.idGrupo, idTareaDeA,
                new SolicitudAsignarTarea(colaborador.idUsuario(), null)))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("La tarea no existe");
    }

    @Test
    void responsableIntegranteSoloDeOtroGrupo422() {
        Escenario grupoA = escenario();
        Escenario grupoB = escenario();
        long idTareaDeB = tareaManual(grupoB);
        FixturaGeneracion.UsuarioAlto colaboradorDeA = fixturaGeneracion.crearIntegranteConIdAlto(grupoA.idGrupo,
                "COLABORADOR", "ACTIVO");
        autenticar(grupoB.uid);

        assertThatThrownBy(() -> servicio.asignar(grupoB.idGrupo, idTareaDeB,
                new SolicitudAsignarTarea(colaboradorDeA.idUsuario(), null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El responsable debe ser integrante activo del grupo");
    }

    @Test
    void responsableIntegranteInactivo422() {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto inactivo = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "INACTIVO");

        assertThatThrownBy(() -> servicio.asignar(e.idGrupo, idTarea,
                new SolicitudAsignarTarea(inactivo.idUsuario(), null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El responsable debe ser integrante activo del grupo");
    }

    // ------------------------------------------------------------------
    // Permisos
    // ------------------------------------------------------------------

    @Test
    void colaborador403() {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        autenticar(colaborador.uid());

        assertThatThrownBy(() -> servicio.asignar(e.idGrupo, idTarea,
                new SolicitudAsignarTarea(colaborador.idUsuario(), null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo un coordinador puede realizar esta acción");
    }

    @Test
    void administradorPasaConTrazabilidad() {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);

        RespuestaTarea respuesta = servicio.asignar(e.idGrupo, idTarea,
                new SolicitudAsignarTarea(colaborador.idUsuario(), "Asignación de administrador"));

        assertThat(respuesta.responsable().idUsuario()).isEqualTo(colaborador.idUsuario());

        List<Map<String, Object>> eventos = eventos(idTarea, "ASIGNAR_TAREA");
        assertThat(eventos).hasSize(1);
        Map<String, Object> detalles = leerJson((String) eventos.get(0).get("DETALLES_JSON"));
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // Tarea finalizada y mismo responsable
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {CodigosTareas.ESTADO_TAREA_COMPLETADA, CodigosTareas.ESTADO_TAREA_NO_REALIZADA,
            CodigosTareas.ESTADO_TAREA_CANCELADA})
    void tareaFinalizada422(String codigoEstadoFinal) {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        fixturaTareas.cambiarEstadoTarea(idTarea, codigoEstadoFinal);
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");

        assertThatThrownBy(() -> servicio.asignar(e.idGrupo, idTarea,
                new SolicitudAsignarTarea(colaborador.idUsuario(), null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La tarea ya está finalizada; no se puede reasignar");
    }

    @Test
    void mismoResponsable422() {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto colaborador = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        fixturaTareas.crearAsignacion(idTarea, e.idGrupo, colaborador.idUsuario(), e.idCoordinador);

        assertThatThrownBy(() -> servicio.asignar(e.idGrupo, idTarea,
                new SolicitudAsignarTarea(colaborador.idUsuario(), "Otro motivo")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La tarea ya está asignada a ese integrante");
    }

    // ------------------------------------------------------------------
    // Concurrencia (reglas 2 y 6 de la Fase 4)
    // ------------------------------------------------------------------

    @Test
    void reasignacionesConcurrentes() throws InterruptedException {
        Escenario e = escenario();
        long idTarea = tareaManual(e);
        FixturaGeneracion.UsuarioAlto candidatoUno = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");
        FixturaGeneracion.UsuarioAlto candidatoDos = fixturaGeneracion.crearIntegranteConIdAlto(e.idGrupo,
                "COLABORADOR", "ACTIVO");

        CyclicBarrier barrera = new CyclicBarrier(2);
        CountDownLatch terminaron = new CountDownLatch(2);
        List<Throwable> fallas = new CopyOnWriteArrayList<>();

        Runnable intentoUno = () -> reasignarConcurrente(e, idTarea, candidatoUno.idUsuario(), barrera, fallas,
                terminaron);
        Runnable intentoDos = () -> reasignarConcurrente(e, idTarea, candidatoDos.idUsuario(), barrera, fallas,
                terminaron);
        new Thread(intentoUno).start();
        new Thread(intentoDos).start();
        boolean terminaronATiempo = terminaron.await(30, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(fallas).isEmpty();

        List<Map<String, Object>> activas = asignacionesDeLaTarea(idTarea).stream()
                .filter(fila -> "ACTIVO".equals(fila.get("CODIGO_ESTADO")))
                .toList();
        assertThat(activas).hasSize(1);
    }

    private void reasignarConcurrente(Escenario e, long idTarea, long idUsuarioNuevo, CyclicBarrier barrera,
            List<Throwable> fallas, CountDownLatch terminaron) {
        try {
            autenticar(e.uid);
            try {
                barrera.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException barreraFallida) {
                throw new IllegalStateException(barreraFallida);
            }
            servicio.asignar(e.idGrupo, idTarea, new SolicitudAsignarTarea(idUsuarioNuevo, "Reasignación concurrente"));
        } catch (Throwable error) {
            fallas.add(error);
        } finally {
            SecurityContextHolder.clearContext();
            terminaron.countDown();
        }
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

    private long tareaManual(Escenario e) {
        return fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
    }

    private OffsetDateTime horario(LocalDate dia, int hora, int minuto) {
        return dia.atTime(hora, minuto).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
    }

    private List<Map<String, Object>> asignacionesDeLaTarea(long idTarea) {
        return jdbcTemplate.queryForList(
                "SELECT a.ID_USUARIO_ASIGNADO, a.ID_USUARIO_ASIGNADOR, a.MOTIVO, a.FECHA_TERMINO, "
                        + "ce.CODIGO_ESTADO FROM ASIGNACION_TAREA a "
                        + "JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = a.ID_ESTADO "
                        + "WHERE a.ID_TAREA = ? ORDER BY a.ID_ASIGNACION",
                idTarea);
    }

    private List<Map<String, Object>> eventos(long idTarea, String accion) {
        return jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'TAREA_CUIDADO' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = ?",
                String.valueOf(idTarea), accion);
    }

    private Map<String, Object> leerJson(String json) {
        return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
        });
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
