package cl.adndigital.mejorvejez.grupos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;

/**
 * Prueba {@link ServicioGrupos} contra Oracle real (Testcontainers). Los
 * datos los crea {@link FixturaGrupos} con uids únicos; quedan confirmados en
 * la base compartida (ver javadoc de {@code ServicioAdministracionUsuariosTest}).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioGruposTest {

    @Autowired
    private ServicioGrupos servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixtura;

    @BeforeEach
    void prepararFixtura() {
        fixtura = new FixturaGrupos(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void crearGrupoDejaAlCreadorComoCoordinador() {
        String uid = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uid, false);
        autenticar(uid);

        RespuestaGrupo creado = servicio.crear("Familia Pérez");

        assertThat(creado.nombreGrupo()).isEqualTo("Familia Pérez");
        assertThat(creado.notificaCoordinador()).isTrue();
        assertThat(creado.miRol()).isEqualTo("COORDINADOR");

        Long idRol = jdbcTemplate.queryForObject(
                "SELECT ID_ROL FROM INTEGRANTE_GRUPO WHERE ID_GRUPO = ?", Long.class, creado.idGrupo());
        String nombreRol = jdbcTemplate.queryForObject(
                "SELECT NOMBRE_ROL FROM ROL_GRUPO WHERE ID_ROL = ?", String.class, idRol);
        assertThat(nombreRol).isEqualTo("COORDINADOR");

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'GRUPO_FAMILIAR' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_GRUPO'",
                String.valueOf(creado.idGrupo()));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void listarSoloMisGruposActivos() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupoActivo = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupoActivo, idUsuario, "COLABORADOR", "ACTIVO");
        long idGrupoInactivo = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupoInactivo, idUsuario, "COLABORADOR", "INACTIVO");
        autenticar(uid);

        List<RespuestaGrupo> grupos = servicio.listar(false);

        assertThat(grupos).extracting(RespuestaGrupo::idGrupo)
                .contains(idGrupoActivo)
                .doesNotContain(idGrupoInactivo);
    }

    @Test
    void listarTodosSoloAdministrador() {
        String uidAdmin = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uidAdmin, true);
        long idGrupo = fixtura.crearGrupo(idAdmin);
        autenticar(uidAdmin);

        List<RespuestaGrupo> grupos = servicio.listar(true);

        assertThat(grupos).extracting(RespuestaGrupo::idGrupo).contains(idGrupo);

        String uidNoAdmin = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uidNoAdmin, false);
        autenticar(uidNoAdmin);

        assertThatThrownBy(() -> servicio.listar(true)).isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void colaboradorNoPuedeEditarGrupo() {
        String uidCreador = FixturaGrupos.uidUnico();
        long idCreador = fixtura.crearUsuario(uidCreador, false);
        long idGrupo = fixtura.crearGrupo(idCreador);
        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixtura.crearUsuario(uidColaborador, false);
        fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, "Nuevo nombre", false))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void coordinadorEditaYQuedaAuditado() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinador);

        RespuestaGrupo actualizado = servicio.actualizar(idGrupo, "Familia renombrada", false);

        assertThat(actualizado.nombreGrupo()).isEqualTo("Familia renombrada");
        assertThat(actualizado.notificaCoordinador()).isFalse();
        assertThat(actualizado.miRol()).isEqualTo("COORDINADOR");

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'GRUPO_FAMILIAR' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACTUALIZAR_GRUPO'",
                String.valueOf(idGrupo));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idCoordinador);
    }

    @Test
    void administradorEditaGrupoAjenoYQuedaAuditado() {
        String uidCreador = FixturaGrupos.uidUnico();
        long idCreador = fixtura.crearUsuario(uidCreador, false);
        long idGrupo = fixtura.crearGrupo(idCreador);
        fixtura.agregarIntegrante(idGrupo, idCreador, "COORDINADOR", "ACTIVO");
        String uidAdmin = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);

        RespuestaGrupo actualizado = servicio.actualizar(idGrupo, "Editado por admin", true);

        assertThat(actualizado.nombreGrupo()).isEqualTo("Editado por admin");
        assertThat(actualizado.miRol()).isNull();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'GRUPO_FAMILIAR' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACTUALIZAR_GRUPO'",
                String.valueOf(idGrupo));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idAdmin);
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
