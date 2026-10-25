package cl.adndigital.mejorvejez.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoAutenticado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.usuarios.Usuario;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioUsuarioActual} contra Oracle real (Testcontainers).
 *
 * <p>Sin rollback automático: la prueba de concurrencia necesita que cada
 * hilo confirme su propia transacción, y {@code obtener()} abre las suyas.
 * Por eso lo confirmado queda en la base compartida, y cada prueba usa uid y
 * correo únicos ({@link UUID#randomUUID()}) para no chocar con otras.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioUsuarioActualTest {

    @Autowired
    private ServicioUsuarioActual servicioUsuarioActual;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void primerIngresoCreaUsuario() {
        String uid = uidUnico();
        String correo = correoUnico();
        autenticar(uid, correo, "Ana Pérez");

        Usuario usuario = servicioUsuarioActual.obtener();

        assertThat(usuario.getIdUsuario()).isNotNull();
        assertThat(usuario.getUidFirebase()).isEqualTo(uid);
        assertThat(usuario.getEmail()).isEqualTo(correo);
        assertThat(usuario.getNombre()).isEqualTo("Ana Pérez");
        assertThat(usuario.getEsAdministrador()).isFalse();
        assertThat(usuario.getIdEstado()).isEqualTo(idEstadoUsuario("ACTIVO"));
        assertThat(contarPorUid(uid)).isEqualTo(1);
    }

    @Test
    void primerIngresoSinNombreUsaParteLocalDelCorreo() {
        String uid = uidUnico();
        String correo = correoUnico();
        autenticar(uid, correo, null);

        Usuario usuario = servicioUsuarioActual.obtener();

        assertThat(usuario.getNombre()).isEqualTo(correo.substring(0, correo.indexOf('@')));
    }

    @Test
    void segundoIngresoNoDuplica() {
        String uid = uidUnico();
        autenticar(uid, correoUnico(), "Ana");

        Usuario primero = servicioUsuarioActual.obtener();
        Usuario segundo = servicioUsuarioActual.obtener();

        assertThat(segundo.getIdUsuario()).isEqualTo(primero.getIdUsuario());
        assertThat(contarPorUid(uid)).isEqualTo(1);
    }

    @Test
    void primerIngresoSinCorreoSeRechaza() {
        String uid = uidUnico();
        autenticar(uid, null, "Ana");

        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La cuenta no tiene un correo asociado");
        assertThat(contarPorUid(uid)).isZero();
    }

    @Test
    void correoDeOtraCuentaDevuelveConflicto() {
        String correo = correoUnico();
        autenticar(uidUnico(), correo, "Ana");
        servicioUsuarioActual.obtener();

        String otroUid = uidUnico();
        autenticar(otroUid, correo, "Impostora");

        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("El correo ya está asociado a otra cuenta");
        assertThat(contarPorUid(otroUid)).isZero();
    }

    @Test
    void usuarioBloqueadoNoPuedeIngresar() {
        String uid = uidUnico();
        autenticar(uid, correoUnico(), "Ana");
        Usuario usuario = servicioUsuarioActual.obtener();
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?",
                idEstadoUsuario("BLOQUEADO"), usuario.getIdUsuario());

        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("La cuenta está deshabilitada");
    }

    @Test
    void usuarioInactivoNoPuedeIngresar() {
        String uid = uidUnico();
        autenticar(uid, correoUnico(), "Ana");
        Usuario usuario = servicioUsuarioActual.obtener();
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?",
                idEstadoUsuario("INACTIVO"), usuario.getIdUsuario());

        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("La cuenta está deshabilitada");
    }

    @Test
    void primerIngresoConcurrenteNoDuplica() throws Exception {
        String uid = uidUnico();
        Jwt jwt = construirJwt(uid, correoUnico(), "Ana");
        CountDownLatch largada = new CountDownLatch(1);

        Callable<Usuario> ingreso = ingresoConcurrente(jwt, largada);

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<Usuario> primero = hilos.submit(ingreso);
            Future<Usuario> segundo = hilos.submit(ingreso);
            largada.countDown();

            Usuario resultadoPrimero = primero.get(60, TimeUnit.SECONDS);
            Usuario resultadoSegundo = segundo.get(60, TimeUnit.SECONDS);

            assertThat(resultadoSegundo.getIdUsuario()).isEqualTo(resultadoPrimero.getIdUsuario());
        } finally {
            hilos.shutdownNow();
        }
        assertThat(contarPorUid(uid)).isEqualTo(1);
    }

    @Test
    void primerIngresoQuedaAuditado() {
        String uid = uidUnico();
        autenticar(uid, correoUnico(), "Ana");

        Usuario usuario = servicioUsuarioActual.obtener();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT CODIGO_ACCION, ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ?",
                String.valueOf(usuario.getIdUsuario()));
        assertThat(eventos).hasSize(1);
        Map<String, Object> evento = eventos.get(0);
        assertThat(evento.get("CODIGO_ACCION")).isEqualTo("CREAR_USUARIO_PRIMER_INGRESO");
        assertThat(((Number) evento.get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(usuario.getIdUsuario());

        Map<String, Object> detalles = JsonMapper.builder().build().readValue(
                jdbcTemplate.queryForObject(
                        "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ?",
                        String.class, String.valueOf(usuario.getIdUsuario())),
                new TypeReference<Map<String, Object>>() {
                });
        assertThat(detalles).containsExactlyEntriesOf(Map.of("uidFirebase", uid));
    }

    @Test
    void primerIngresoConcurrenteConMismoCorreoDistintoUid() throws Exception {
        String correo = correoUnico();
        Jwt jwtA = construirJwt(uidUnico(), correo, "Ana");
        Jwt jwtB = construirJwt(uidUnico(), correo, "Otra Ana");
        CountDownLatch largada = new CountDownLatch(1);

        ExecutorService hilos = Executors.newFixedThreadPool(2);
        List<Object> resultados = new ArrayList<>();
        try {
            Future<Usuario> primero = hilos.submit(ingresoConcurrente(jwtA, largada));
            Future<Usuario> segundo = hilos.submit(ingresoConcurrente(jwtB, largada));
            largada.countDown();
            for (Future<Usuario> resultado : List.of(primero, segundo)) {
                try {
                    resultados.add(resultado.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException fallo) {
                    resultados.add(fallo.getCause());
                }
            }
        } finally {
            hilos.shutdownNow();
        }

        assertThat(resultados).filteredOn(Usuario.class::isInstance).hasSize(1);
        assertThat(resultados).filteredOn(ExcepcionConflicto.class::isInstance).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USUARIO WHERE EMAIL = ?",
                Integer.class, correo)).isEqualTo(1);
    }

    @Test
    void sinAutenticacionDevuelveNoAutenticado() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionNoAutenticado.class)
                .hasMessage("Se requiere una sesión válida.");

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "clave", "anonimo", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionNoAutenticado.class);
    }

    @Test
    void jwtSinSubjectDevuelveNoAutenticado() {
        autenticar(null, correoUnico(), "Ana");
        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionNoAutenticado.class);

        autenticar("  ", correoUnico(), "Ana");
        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionNoAutenticado.class);
    }

    @Test
    void correoSeGuardaNormalizado() {
        String parteLocal = "Ana-" + UUID.randomUUID();
        autenticar(uidUnico(), " " + parteLocal + "@X.cl ", "Ana");

        Usuario usuario = servicioUsuarioActual.obtener();

        String normalizado = parteLocal.toLowerCase(Locale.ROOT) + "@x.cl";
        assertThat(usuario.getEmail()).isEqualTo(normalizado);

        String otroUid = uidUnico();
        autenticar(otroUid, normalizado, "Otra");
        assertThatThrownBy(() -> servicioUsuarioActual.obtener())
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("El correo ya está asociado a otra cuenta");
        assertThat(contarPorUid(otroUid)).isZero();
    }

    @Test
    void nombreLargoSeTrunca() {
        autenticar(uidUnico(), correoUnico(), "  " + "a".repeat(200));

        Usuario usuario = servicioUsuarioActual.obtener();

        assertThat(usuario.getNombre()).isEqualTo("a".repeat(160));
    }

    @Test
    void nombreLargoConTildesSeTruncaPorBytes() {
        String uid = uidUnico();
        autenticar(uid, correoUnico(), "á".repeat(160));

        Usuario usuario = servicioUsuarioActual.obtener();

        assertThat(usuario.getNombre().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(160);
        assertThat(usuario.getNombre()).isEqualTo("á".repeat(80));
        assertThat(jdbcTemplate.queryForObject("SELECT NOMBRE FROM USUARIO WHERE UID_FIREBASE = ?",
                String.class, uid)).isEqualTo("á".repeat(80));
    }

    private Callable<Usuario> ingresoConcurrente(Jwt jwt, CountDownLatch largada) {
        return () -> {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
            try {
                largada.await();
                return servicioUsuarioActual.obtener();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private void autenticar(String uid, String correo, String nombre) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(construirJwt(uid, correo, nombre)));
    }

    private Jwt construirJwt(String uid, String correo, String nombre) {
        Jwt.Builder constructor = Jwt.withTokenValue("token-de-prueba")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        if (uid != null) {
            constructor.subject(uid);
        }
        if (correo != null) {
            constructor.claim("email", correo);
        }
        if (nombre != null) {
            constructor.claim("name", nombre);
        }
        return constructor.build();
    }

    private String uidUnico() {
        return "uid-" + UUID.randomUUID();
    }

    private String correoUnico() {
        return "persona-" + UUID.randomUUID() + "@ejemplo.cl";
    }

    private int contarPorUid(String uid) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USUARIO WHERE UID_FIREBASE = ?",
                Integer.class, uid);
    }

    private long idEstadoUsuario(String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'USUARIO' AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, codigo);
    }
}
