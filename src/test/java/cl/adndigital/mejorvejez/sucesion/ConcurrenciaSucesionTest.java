package cl.adndigital.mejorvejez.sucesion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;

/**
 * Concurrencia de la sucesión (regla 18 de la Fase 7, foco de revisión 1):
 * dos coordinadores del mismo grupo proponen a la vez y solo uno gana; el
 * índice {@code UX_PROPUESTA_ACTIVA_GRUPO} respalda la regla aunque alguien
 * esquive el servicio; y dos completar simultáneos de la misma propuesta
 * (Tarea 4) aplican los roles una sola vez. Mismo patrón de dos hilos que escriben, barrera de
 * inicio y {@link TransactionTemplate} que {@code
 * tareas.ServicioAsignacionTareasTest#reasignacionesConcurrentes}.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ConcurrenciaSucesionTest {

    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);

    /** Rango propio de ids de usuario explícitos &gt; 127 (ver {@link ServicioSucesionTest}). */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(993_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioSucesion servicio;

    @Autowired
    private ServicioCompletarSucesion servicioCompletar;

    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void dosPropuestasConcurrentesSoloUnaGana() throws InterruptedException {
        UsuarioAlto coordinadorUno = crearUsuarioAlto();
        long idGrupo = fixturaGrupos.crearGrupo(coordinadorUno.idUsuario());
        fixturaGrupos.agregarIntegrante(idGrupo, coordinadorUno.idUsuario(), "COORDINADOR", "ACTIVO");
        UsuarioAlto coordinadorDos = crearIntegranteAlto(idGrupo, "COORDINADOR");
        UsuarioAlto candidatoUno = crearIntegranteAlto(idGrupo, "COLABORADOR");
        UsuarioAlto candidatoDos = crearIntegranteAlto(idGrupo, "COLABORADOR");

        CyclicBarrier barrera = new CyclicBarrier(2);
        CountDownLatch terminaron = new CountDownLatch(2);
        List<Long> exitos = new CopyOnWriteArrayList<>();
        List<Throwable> conflictos = new CopyOnWriteArrayList<>();
        List<Throwable> otrasFallas = new CopyOnWriteArrayList<>();

        new Thread(() -> proponerConcurrente(coordinadorUno.uid(), idGrupo, candidatoUno.idUsuario(), barrera,
                exitos, conflictos, otrasFallas, terminaron)).start();
        new Thread(() -> proponerConcurrente(coordinadorDos.uid(), idGrupo, candidatoDos.idUsuario(), barrera,
                exitos, conflictos, otrasFallas, terminaron)).start();
        boolean terminaronATiempo = terminaron.await(60, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(otrasFallas).isEmpty();
        assertThat(exitos).hasSize(1);
        assertThat(conflictos).hasSize(1);
        assertThat(conflictos.get(0)).hasMessage(ServicioSucesion.MENSAJE_YA_EXISTE_ACTIVA);

        Long pendientes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PROPUESTA_SUCESION WHERE ID_GRUPO = ? AND ID_ESTADO = ?", Long.class, idGrupo,
                idEstado("PROPUESTA_SUCESION", "PENDIENTE"));
        assertThat(pendientes).isEqualTo(1L);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM PROPUESTA_SUCESION WHERE ID_GRUPO = ?",
                Long.class, idGrupo);
        assertThat(total).isEqualTo(1L);
    }

    @Test
    void indiceUnicoRespaldaLaRegla() {
        UsuarioAlto coordinador = crearUsuarioAlto();
        long idGrupo = fixturaGrupos.crearGrupo(coordinador.idUsuario());
        fixturaGrupos.agregarIntegrante(idGrupo, coordinador.idUsuario(), "COORDINADOR", "ACTIVO");
        UsuarioAlto candidato = crearIntegranteAlto(idGrupo, "COLABORADOR");
        UsuarioAlto otroCandidato = crearIntegranteAlto(idGrupo, "COLABORADOR");
        autenticar(coordinador.uid());
        servicio.proponer(idGrupo, candidato.idUsuario());

        // Un INSERT directo, que esquiva el bloqueo y la verificación del servicio.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO PROPUESTA_SUCESION (ID_GRUPO, ID_USUARIO_SALIENTE, ID_USUARIO_CANDIDATO, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?)",
                idGrupo, coordinador.idUsuario(), otroCandidato.idUsuario(),
                idEstado("PROPUESTA_SUCESION", "PENDIENTE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CodigosSucesion.INDICE_PROPUESTA_ACTIVA);

        // Una ACEPTADA también cuenta como activa para el índice.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO PROPUESTA_SUCESION (ID_GRUPO, ID_USUARIO_SALIENTE, ID_USUARIO_CANDIDATO, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?)",
                idGrupo, coordinador.idUsuario(), otroCandidato.idUsuario(),
                idEstado("PROPUESTA_SUCESION", "ACEPTADA")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(CodigosSucesion.INDICE_PROPUESTA_ACTIVA);

        // Una cerrada (RECHAZADA) no choca.
        jdbcTemplate.update(
                "INSERT INTO PROPUESTA_SUCESION (ID_GRUPO, ID_USUARIO_SALIENTE, ID_USUARIO_CANDIDATO, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?)",
                idGrupo, coordinador.idUsuario(), otroCandidato.idUsuario(),
                idEstado("PROPUESTA_SUCESION", "RECHAZADA"));
    }

    @Test
    void completarConcurrenteSoloUnoGana() throws InterruptedException {
        UsuarioAlto saliente = crearUsuarioAlto();
        long idGrupo = fixturaGrupos.crearGrupo(saliente.idUsuario());
        fixturaGrupos.agregarIntegrante(idGrupo, saliente.idUsuario(), "COORDINADOR", "ACTIVO");
        UsuarioAlto candidato = crearIntegranteAlto(idGrupo, "COLABORADOR");
        UsuarioAlto administrador = crearUsuarioAlto(true);
        autenticar(saliente.uid());
        long idPropuesta = servicio.proponer(idGrupo, candidato.idUsuario()).idPropuesta();
        autenticar(candidato.uid());
        servicio.aceptar(idGrupo, idPropuesta);
        SecurityContextHolder.clearContext();

        CyclicBarrier barrera = new CyclicBarrier(2);
        CountDownLatch terminaron = new CountDownLatch(2);
        List<Long> exitos = new CopyOnWriteArrayList<>();
        List<Throwable> conflictos = new CopyOnWriteArrayList<>();
        List<Throwable> otrasFallas = new CopyOnWriteArrayList<>();

        new Thread(() -> completarConcurrente(saliente.uid(), idGrupo, idPropuesta, barrera, exitos, conflictos,
                otrasFallas, terminaron)).start();
        new Thread(() -> completarConcurrente(administrador.uid(), idGrupo, idPropuesta, barrera, exitos,
                conflictos, otrasFallas, terminaron)).start();
        boolean terminaronATiempo = terminaron.await(60, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(otrasFallas).isEmpty();
        assertThat(exitos).containsExactly(idPropuesta);
        assertThat(conflictos).hasSize(1);
        assertThat(conflictos.get(0)).hasMessage(ServicioSucesion.MENSAJE_TRANSICION_INVALIDA);

        assertThat(jdbcTemplate.queryForObject("SELECT ID_ESTADO FROM PROPUESTA_SUCESION WHERE ID_PROPUESTA = ?",
                Long.class, idPropuesta)).isEqualTo(idEstado("PROPUESTA_SUCESION", "COMPLETADA"));
        assertThat(rolDe(idGrupo, candidato.idUsuario())).isEqualTo("COORDINADOR");
        assertThat(rolDe(idGrupo, saliente.idUsuario())).isEqualTo("COLABORADOR");
        // Los roles se aplicaron una sola vez: 2 cambios de rol y 1 COMPLETAR_SUCESION exitoso.
        assertThat(contarEventos(idGrupo, "CAMBIAR_ROL_INTEGRANTE", "EXITO")).isEqualTo(2L);
        assertThat(contarEventos(idGrupo, "COMPLETAR_SUCESION", "EXITO")).isEqualTo(1L);
        assertThat(contarEventos(idGrupo, "COMPLETAR_SUCESION", "RECHAZADA")).isEqualTo(1L);
    }

    private void completarConcurrente(String uid, long idGrupo, long idPropuesta, CyclicBarrier barrera,
            List<Long> exitos, List<Throwable> conflictos, List<Throwable> otrasFallas, CountDownLatch terminaron) {
        try {
            autenticar(uid);
            try {
                barrera.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException barreraFallida) {
                throw new IllegalStateException(barreraFallida);
            }
            RespuestaPropuesta propuesta = new TransactionTemplate(gestorTransacciones)
                    .execute(estado -> servicioCompletar.completar(idGrupo, idPropuesta,
                            new SolicitudCompletarSucesion(null)));
            exitos.add(propuesta.idPropuesta());
        } catch (ExcepcionConflicto conflicto) {
            conflictos.add(conflicto);
        } catch (Throwable error) {
            otrasFallas.add(error);
        } finally {
            SecurityContextHolder.clearContext();
            terminaron.countDown();
        }
    }

    private void proponerConcurrente(String uid, long idGrupo, long idCandidato, CyclicBarrier barrera,
            List<Long> exitos, List<Throwable> conflictos, List<Throwable> otrasFallas, CountDownLatch terminaron) {
        try {
            autenticar(uid);
            try {
                barrera.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException barreraFallida) {
                throw new IllegalStateException(barreraFallida);
            }
            RespuestaPropuesta propuesta = new TransactionTemplate(gestorTransacciones)
                    .execute(estado -> servicio.proponer(idGrupo, idCandidato));
            exitos.add(propuesta.idPropuesta());
        } catch (ExcepcionConflicto conflicto) {
            conflictos.add(conflicto);
        } catch (Throwable error) {
            otrasFallas.add(error);
        } finally {
            SecurityContextHolder.clearContext();
            terminaron.countDown();
        }
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private record UsuarioAlto(String uid, long idUsuario) {
    }

    private UsuarioAlto crearIntegranteAlto(long idGrupo, String rol) {
        UsuarioAlto usuario = crearUsuarioAlto();
        fixturaGrupos.agregarIntegrante(idGrupo, usuario.idUsuario(), rol, "ACTIVO");
        return usuario;
    }

    private UsuarioAlto crearUsuarioAlto() {
        return crearUsuarioAlto(false);
    }

    private UsuarioAlto crearUsuarioAlto(boolean administrador) {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        String uid = FixturaGrupos.uidUnico();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                idUsuario, uid, "fixtura-" + UUID.randomUUID() + "@sucesion.mejorvejez.test",
                "Usuario concurrencia " + idUsuario, administrador ? "S" : "N", idEstado("USUARIO", "ACTIVO"));
        return new UsuarioAlto(uid, idUsuario);
    }

    private String rolDe(long idGrupo, long idUsuario) {
        return jdbcTemplate.queryForObject(
                "SELECT r.NOMBRE_ROL FROM INTEGRANTE_GRUPO i JOIN ROL_GRUPO r ON r.ID_ROL = i.ID_ROL "
                        + "WHERE i.ID_GRUPO = ? AND i.ID_USUARIO = ?",
                String.class, idGrupo, idUsuario);
    }

    private long contarEventos(long idGrupo, String codigoAccion, String resultado) {
        Long cantidad = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA e JOIN CATALOGO_ESTADOS ce ON ce.ID_ESTADO = e.ID_RESULTADO "
                        + "WHERE e.ID_GRUPO = ? AND e.CODIGO_ACCION = ? AND ce.CODIGO_ESTADO = ?",
                Long.class, idGrupo, codigoAccion, resultado);
        return cantidad == null ? 0 : cantidad;
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
