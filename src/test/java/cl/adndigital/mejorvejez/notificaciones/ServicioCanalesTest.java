package cl.adndigital.mejorvejez.notificaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.seguridad.ServicioUsuarioActual;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Prueba {@link ServicioCanales} contra Oracle real (Testcontainers),
 * mismo patrón que {@code usuarios.ServicioPerfilTest}: cada usuario se da
 * de alta con {@link ServicioUsuarioActual} y queda en el {@code
 * SecurityContext} para que {@code servicioCanales} lo resuelva como el
 * usuario autenticado.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioCanalesTest {

    @Autowired
    private ServicioCanales servicioCanales;

    @Autowired
    private ServicioUsuarioActual servicioUsuarioActual;

    @Autowired
    private RepositorioCanalNotificacion repositorioCanal;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sinFilasDevuelveCorreoDeLaCuentaPredeterminado() {
        Usuario usuario = darDeAltaUsuario("Ana");

        RespuestaCanales respuesta = servicioCanales.misCanales();

        assertThat(respuesta.canales()).hasSize(1);
        RespuestaCanal correo = respuesta.canales().get(0);
        assertThat(correo.canal()).isEqualTo(CodigosNotificaciones.CANAL_CORREO);
        assertThat(correo.destino()).isEqualTo(usuario.getEmail());
        assertThat(correo.habilitado()).isTrue();
        assertThat(correo.predeterminado()).isTrue();
        assertThat(respuesta.whatsappDisponible()).isFalse();
    }

    @Test
    void putReemplazaElConjunto() {
        darDeAltaUsuario("Ana");
        String correoNuevo = correoDePrueba();

        RespuestaCanales primera = servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoNuevo, true),
                new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, "+56912345678", true))));

        assertThat(primera.canales()).hasSize(2);
        assertThat(primera.canales().get(0).canal()).isEqualTo(CodigosNotificaciones.CANAL_CORREO);
        assertThat(primera.canales().get(0).destino()).isEqualTo(correoNuevo);
        assertThat(primera.canales().get(0).predeterminado()).isFalse();
        assertThat(primera.canales().get(1).canal()).isEqualTo(CodigosNotificaciones.CANAL_WHATSAPP);

        RespuestaCanales segunda = servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(
                List.of(new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoNuevo, true))));

        assertThat(segunda.canales()).hasSize(1);
        assertThat(segunda.canales().get(0).canal()).isEqualTo(CodigosNotificaciones.CANAL_CORREO);
    }

    @Test
    void putSinCorreoVuelveAlPredeterminado() {
        Usuario usuario = darDeAltaUsuario("Ana");
        servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoDePrueba(), true),
                new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, "+56912345678", true))));

        RespuestaCanales respuesta = servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(
                List.of(new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, "+56912345678", true))));

        assertThat(respuesta.canales()).hasSize(2);
        RespuestaCanal correo = respuesta.canales().get(0);
        assertThat(correo.canal()).isEqualTo(CodigosNotificaciones.CANAL_CORREO);
        assertThat(correo.destino()).isEqualTo(usuario.getEmail());
        assertThat(correo.habilitado()).isTrue();
        assertThat(correo.predeterminado()).isTrue();
        assertThat(respuesta.canales().get(1).canal()).isEqualTo(CodigosNotificaciones.CANAL_WHATSAPP);

        assertThat(repositorioCanal.findByIdUsuario(usuario.getIdUsuario()))
                .extracting(CanalNotificacionUsuario::getCanal)
                .containsExactly(CodigosNotificaciones.CANAL_WHATSAPP);
    }

    @Test
    void correoInvalidoDa422() {
        darDeAltaUsuario("Ana");

        assertThatThrownBy(() -> servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(
                List.of(new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, "no-es-un-correo", true)))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El correo de notificación no es válido.");
    }

    @Test
    void whatsappQueNoEsChilenoDa422() {
        darDeAltaUsuario("Ana");

        for (String invalido : List.of("+5491123456789", "12345", "", "abc")) {
            assertThatThrownBy(() -> servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                    new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoDePrueba(), true),
                    new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, invalido, true)))))
                    .isInstanceOf(ExcepcionReglaNegocio.class)
                    .hasMessage("Ingresa un teléfono chileno: +56 y 9 dígitos.");
        }
    }

    @Test
    void whatsappAntiguoSinCambiosPermiteGuardarElCorreo() {
        Usuario usuario = darDeAltaUsuario("Ana");
        jdbcTemplate.update("INSERT INTO CANAL_NOTIFICACION_USUARIO (ID_USUARIO, CANAL, DESTINO, HABILITADO) "
                + "VALUES (?, 'WHATSAPP', '+5491123456789', 'N')", usuario.getIdUsuario());
        String correoNuevo = correoDePrueba();

        RespuestaCanales respuesta = servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoNuevo, true),
                new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, "+5491123456789", true))));

        assertThat(respuesta.canales().get(0).destino()).isEqualTo(correoNuevo);
        assertThat(respuesta.canales().get(1).destino()).isEqualTo("+5491123456789");
        assertThat(respuesta.canales().get(1).habilitado()).isTrue();

        assertThatThrownBy(() -> servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoNuevo, true),
                new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, "+5491123456780", true)))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Ingresa un teléfono chileno: +56 y 9 dígitos.");
    }

    @Test
    void whatsappSeGuardaNormalizado() {
        Usuario usuario = darDeAltaUsuario("Ana");

        RespuestaCanales respuesta = servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoDePrueba(), true),
                new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, " 9 1234-5678 ", false))));

        assertThat(respuesta.canales().get(1).destino()).isEqualTo("+56912345678");
        assertThat(repositorioCanal.findByIdUsuario(usuario.getIdUsuario()))
                .filteredOn(fila -> CodigosNotificaciones.CANAL_WHATSAPP.equals(fila.getCanal()))
                .extracting(CanalNotificacionUsuario::getDestino)
                .containsExactly("+56912345678");
    }

    @Test
    void canalRepetidoDa422() {
        darDeAltaUsuario("Ana");

        assertThatThrownBy(() -> servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoDePrueba(), true),
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoDePrueba(), true)))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Cada canal puede aparecer una sola vez.");
    }

    @Test
    void canalInexistenteDa422() {
        darDeAltaUsuario("Ana");

        assertThatThrownBy(() -> servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(
                List.of(new SolicitudCanal("SMS", "+56912345678", true)))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El canal indicado no existe.");
    }

    @Test
    void listaNulaOConElementoNuloDa422() {
        darDeAltaUsuario("Ana");
        List<SolicitudCanal> conNulo = new java.util.ArrayList<>();
        conNulo.add(null);

        assertThatThrownBy(() -> servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(conNulo)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La lista de canales no es válida.");
        assertThatThrownBy(() -> servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La lista de canales no es válida.");
    }

    @Test
    void dejarSoloWhatsappDeshabilitaTodoYDa422() {
        darDeAltaUsuario("Ana");

        assertThatThrownBy(() -> servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoDePrueba(), false),
                new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, "+56912345678", true)))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Debes mantener al menos un canal habilitado que el sistema pueda usar (hoy, el correo).");
    }

    @Test
    void canalesOperativosExcluyeWhatsappNoDisponibleYDeshabilitados() {
        Usuario sinFilas = darDeAltaUsuario("Ana");
        SecurityContextHolder.clearContext();
        Usuario conCorreoYWhatsapp = darDeAltaUsuario("Luis");
        String correoLuis = correoDePrueba();
        OffsetDateTime ahora = OffsetDateTime.now(Clock.systemUTC());
        repositorioCanal.save(new CanalNotificacionUsuario(conCorreoYWhatsapp.getIdUsuario(),
                CodigosNotificaciones.CANAL_CORREO, correoLuis, true, ahora));
        repositorioCanal.save(new CanalNotificacionUsuario(conCorreoYWhatsapp.getIdUsuario(),
                CodigosNotificaciones.CANAL_WHATSAPP, "+56911111111", true, ahora));
        SecurityContextHolder.clearContext();
        Usuario correoDeshabilitado = darDeAltaUsuario("Marta");
        repositorioCanal.save(new CanalNotificacionUsuario(correoDeshabilitado.getIdUsuario(),
                CodigosNotificaciones.CANAL_CORREO, correoDePrueba(), false, ahora));

        Map<Long, List<CanalOperativo>> resultado = servicioCanales.canalesOperativos(
                List.of(sinFilas, conCorreoYWhatsapp, correoDeshabilitado));

        assertThat(resultado.get(sinFilas.getIdUsuario()))
                .containsExactly(new CanalOperativo(CodigosNotificaciones.CANAL_CORREO, sinFilas.getEmail()));
        assertThat(resultado.get(conCorreoYWhatsapp.getIdUsuario()))
                .containsExactly(new CanalOperativo(CodigosNotificaciones.CANAL_CORREO, correoLuis));
        assertThat(resultado.get(correoDeshabilitado.getIdUsuario())).isEmpty();
    }

    @Test
    void auditaSinDestinos() {
        Usuario usuario = darDeAltaUsuario("Ana");
        String correo = correoDePrueba();

        servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correo, true),
                new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, "+56912345678", true))));

        String detallesJson = jdbcTemplate.queryForObject(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? "
                        + "AND CODIGO_ACCION = ?",
                String.class, String.valueOf(usuario.getIdUsuario()),
                CodigosNotificaciones.ACCION_ACTUALIZAR_CANALES_NOTIFICACION);

        assertThat(detallesJson).isNotNull();
        assertThat(detallesJson).doesNotContain("@");
        assertThat(detallesJson).doesNotContain("+56");
        assertThat(detallesJson).contains("CORREO").contains("WHATSAPP");
    }

    // ------------------------------------------------------------------
    // Concurrencia (ronda de corrección 1: bloqueo de la fila de USUARIO)
    // ------------------------------------------------------------------

    @Test
    void putsConcurrentesDelMismoUsuarioNoChocan() throws InterruptedException {
        String uid = "uid-" + UUID.randomUUID();
        String correoCuenta = correoDePrueba();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(construirJwt(uid, correoCuenta, "Ana")));
        Usuario usuario = servicioUsuarioActual.obtener();
        SecurityContextHolder.clearContext();

        CyclicBarrier barrera = new CyclicBarrier(2);
        CountDownLatch terminaron = new CountDownLatch(2);
        List<Throwable> fallas = new CopyOnWriteArrayList<>();

        Runnable intentoUno = () -> actualizarCanalesConcurrente(uid, correoCuenta, "Ana", correoDePrueba(),
                "+56911111111", barrera, fallas, terminaron);
        Runnable intentoDos = () -> actualizarCanalesConcurrente(uid, correoCuenta, "Ana", correoDePrueba(),
                "+56922222222", barrera, fallas, terminaron);
        new Thread(intentoUno).start();
        new Thread(intentoDos).start();

        boolean terminaronATiempo = terminaron.await(30, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(fallas).isEmpty();

        List<CanalNotificacionUsuario> filas = repositorioCanal.findByIdUsuario(usuario.getIdUsuario());
        assertThat(filas).extracting(CanalNotificacionUsuario::getCanal)
                .containsExactlyInAnyOrder(CodigosNotificaciones.CANAL_CORREO, CodigosNotificaciones.CANAL_WHATSAPP);
    }

    private void actualizarCanalesConcurrente(String uid, String correoCuenta, String nombre, String correoCanal,
            String whatsapp, CyclicBarrier barrera, List<Throwable> fallas, CountDownLatch terminaron) {
        try {
            SecurityContextHolder.getContext()
                    .setAuthentication(new JwtAuthenticationToken(construirJwt(uid, correoCuenta, nombre)));
            try {
                barrera.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException barreraFallida) {
                throw new IllegalStateException(barreraFallida);
            }
            servicioCanales.actualizarMisCanales(new SolicitudActualizarCanales(List.of(
                    new SolicitudCanal(CodigosNotificaciones.CANAL_CORREO, correoCanal, true),
                    new SolicitudCanal(CodigosNotificaciones.CANAL_WHATSAPP, whatsapp, true))));
        } catch (Throwable error) {
            fallas.add(error);
        } finally {
            SecurityContextHolder.clearContext();
            terminaron.countDown();
        }
    }

    private String correoDePrueba() {
        return "correo-" + UUID.randomUUID() + "@ejemplo.cl";
    }

    private Usuario darDeAltaUsuario(String nombre) {
        String uid = "uid-" + UUID.randomUUID();
        String correo = correoDePrueba();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(construirJwt(uid, correo, nombre)));
        return servicioUsuarioActual.obtener();
    }

    private Jwt construirJwt(String uid, String correo, String nombre) {
        return Jwt.withTokenValue("token-de-prueba")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .subject(uid)
                .claim("email", correo)
                .claim("name", nombre)
                .build();
    }
}
