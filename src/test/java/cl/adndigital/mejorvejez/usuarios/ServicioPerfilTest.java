package cl.adndigital.mejorvejez.usuarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import cl.adndigital.mejorvejez.seguridad.ServicioUsuarioActual;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioPerfil} contra Oracle real (Testcontainers). El
 * usuario de cada prueba se da de alta con {@link ServicioUsuarioActual}
 * (mismo patrón que {@code ServicioUsuarioActualTest}), y el reloj se fija a
 * mano en el propio {@link ServicioPerfil} bajo prueba para poder comparar
 * {@code FECHA_ACTUALIZACION} con exactitud.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioPerfilTest {

    @Autowired
    private ServicioUsuarioActual servicioUsuarioActual;

    @Autowired
    private RepositorioUsuario repositorioUsuario;

    @Autowired
    private cl.adndigital.mejorvejez.auditoria.ServicioAuditoria servicioAuditoria;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void actualizaNombreYTelefono() {
        Usuario usuario = darDeAltaUsuario("Ana");
        Instant instanteFijo = Instant.parse("2026-01-15T12:00:00Z");
        ServicioPerfil servicioPerfil = servicioPerfilConReloj(Clock.fixed(instanteFijo, ZoneOffset.UTC));

        Usuario actualizado = servicioPerfil.actualizar(usuario, "  Nuevo Nombre  ", " +56 9 1234 5678 ");

        assertThat(actualizado.getNombre()).isEqualTo("Nuevo Nombre");
        assertThat(actualizado.getTelefono()).isEqualTo("+56912345678");
        assertThat(actualizado.getFechaActualizacion())
                .isEqualTo(OffsetDateTime.ofInstant(instanteFijo, ZoneOffset.UTC));
    }

    @Test
    void telefonoVacioSeBorra() {
        Usuario usuario = darDeAltaUsuario("Ana");
        ServicioPerfil servicioPerfil = servicioPerfilConReloj(Clock.systemUTC());
        servicioPerfil.actualizar(usuario, "Ana", "+56 9 1234 5678");

        Usuario actualizado = servicioPerfil.actualizar(usuario, "Ana", "");

        assertThat(actualizado.getTelefono()).isNull();
    }

    @Test
    void telefonoAntiguoSinCambiosNoImpideGuardarElNombre() {
        Usuario usuario = darDeAltaUsuario("Ana");
        jdbcTemplate.update("UPDATE USUARIO SET TELEFONO = '+1 555 123 4567' WHERE ID_USUARIO = ?",
                usuario.getIdUsuario());
        Usuario conTelefonoAntiguo = repositorioUsuario.findById(usuario.getIdUsuario()).orElseThrow();
        ServicioPerfil servicioPerfil = servicioPerfilConReloj(Clock.systemUTC());

        Usuario actualizado = servicioPerfil.actualizar(conTelefonoAntiguo, "Ana María", "+1 555 123 4567");

        assertThat(actualizado.getNombre()).isEqualTo("Ana María");
        assertThat(actualizado.getTelefono()).isEqualTo("+1 555 123 4567");
    }

    @Test
    void telefonoNuevoInvalidoSeRechazaConErrorDeCampo() {
        Usuario usuario = darDeAltaUsuario("Ana");
        ServicioPerfil servicioPerfil = servicioPerfilConReloj(Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> servicioPerfil.actualizar(usuario, "Ana", "+1 555 123 4567"))
                .isInstanceOf(cl.adndigital.mejorvejez.comun.ExcepcionCampoInvalido.class)
                .hasMessage("Ingresa un teléfono chileno: +56 y 9 dígitos.");
    }

    @Test
    void actualizacionQuedaAuditada() {
        Usuario usuario = darDeAltaUsuario("Ana");
        ServicioPerfil servicioPerfil = servicioPerfilConReloj(Clock.systemUTC());

        Usuario actualizado = servicioPerfil.actualizar(usuario, "Nuevo Nombre", "+56 9 1234 5678");

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT CODIGO_ACCION, ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACTUALIZAR_PERFIL'",
                String.valueOf(actualizado.getIdUsuario()));
        assertThat(eventos).hasSize(1);
        Map<String, Object> evento = eventos.get(0);
        assertThat(((Number) evento.get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(actualizado.getIdUsuario());

        Map<String, Object> detalles = JsonMapper.builder().build().readValue(
                jdbcTemplate.queryForObject(
                        "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA "
                                + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACTUALIZAR_PERFIL'",
                        String.class, String.valueOf(actualizado.getIdUsuario())),
                new TypeReference<Map<String, Object>>() {
                });
        @SuppressWarnings("unchecked")
        List<String> campos = (List<String>) detalles.get("campos");
        assertThat(campos).containsExactlyInAnyOrder("nombre", "telefono");
    }

    @Test
    void perfilSinCambiosNoAudita() {
        Usuario usuario = darDeAltaUsuario("Ana");
        ServicioPerfil servicioPerfil = servicioPerfilConReloj(Clock.systemUTC());

        servicioPerfil.actualizar(usuario, usuario.getNombre(), usuario.getTelefono());

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT CODIGO_ACCION FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACTUALIZAR_PERFIL'",
                String.valueOf(usuario.getIdUsuario()));
        assertThat(eventos).isEmpty();
    }

    private ServicioPerfil servicioPerfilConReloj(Clock reloj) {
        return new ServicioPerfil(repositorioUsuario, servicioAuditoria, reloj);
    }

    private Usuario darDeAltaUsuario(String nombre) {
        String uid = "uid-" + UUID.randomUUID();
        String correo = "persona-" + UUID.randomUUID() + "@ejemplo.cl";
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(construirJwt(uid, correo, nombre)));
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
