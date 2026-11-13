package cl.adndigital.mejorvejez.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

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
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Prueba {@link ServicioAutorizacion} contra Oracle real (Testcontainers).
 * Los datos los crea {@link FixturaGrupos} con uids únicos y quedan
 * confirmados (la auditoría de rechazos corre en transacción propia), así
 * que las aserciones filtran siempre por los IDs propios de cada prueba.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioAutorizacionTest {

    @Autowired
    private ServicioAutorizacion servicioAutorizacion;

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
    void miembroActivoPasaExigirMiembro() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupo = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupo, idUsuario, "COLABORADOR", "ACTIVO");
        autenticar(uid);

        ContextoGrupo contexto = servicioAutorizacion.exigirMiembro(idGrupo);

        assertThat(contexto.usuario().getIdUsuario()).isEqualTo(idUsuario);
        assertThat(contexto.grupo().getIdGrupo()).isEqualTo(idGrupo);
        assertThat(contexto.rol()).isEqualTo("COLABORADOR");
        assertThat(contexto.esAdministrador()).isFalse();
    }

    @Test
    void coordinadorActivoPasaExigirCoordinador() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupo = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupo, idUsuario, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);

        assertThat(contexto.rol()).isEqualTo("COORDINADOR");
        assertThat(contexto.esAdministrador()).isFalse();
    }

    @Test
    void miembroDeOtroGrupoRecibe403YQuedaAuditado() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupoA = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupoA, idUsuario, "COORDINADOR", "ACTIVO");
        long idOtro = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoB = fixtura.crearGrupo(idOtro);
        fixtura.agregarIntegrante(idGrupoB, idOtro, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicioAutorizacion.exigirMiembro(idGrupoB))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("No perteneces a este grupo");

        assertThat(contarRechazos("ACCESO_GRUPO", idGrupoB, idUsuario)).isEqualTo(1);
    }

    @Test
    void integranteInactivoRecibe403() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupo = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupo, idUsuario, "COORDINADOR", "INACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicioAutorizacion.exigirMiembro(idGrupo))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("No perteneces a este grupo");
        assertThat(contarRechazos("ACCESO_GRUPO", idGrupo, idUsuario)).isEqualTo(1);
    }

    @Test
    void colaboradorNoPasaExigirCoordinador() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupo = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupo, idUsuario, "COLABORADOR", "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicioAutorizacion.exigirCoordinador(idGrupo))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo un coordinador puede realizar esta acción");
        assertThat(contarRechazos("ACCESO_GRUPO", idGrupo, idUsuario)).isEqualTo(1);
    }

    /** CP-05: el rol se resuelve por grupo; ser coordinador en A no da permisos de coordinador en B. */
    @Test
    void usuarioEnDosGruposTieneElRolDeCadaGrupo() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupoA = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupoA, idUsuario, "COORDINADOR", "ACTIVO");
        long idOtro = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoB = fixtura.crearGrupo(idOtro);
        fixtura.agregarIntegrante(idGrupoB, idOtro, "COORDINADOR", "ACTIVO");
        fixtura.agregarIntegrante(idGrupoB, idUsuario, "COLABORADOR", "ACTIVO");
        autenticar(uid);

        assertThat(servicioAutorizacion.exigirCoordinador(idGrupoA).rol()).isEqualTo("COORDINADOR");
        assertThat(servicioAutorizacion.exigirMiembro(idGrupoB).rol()).isEqualTo("COLABORADOR");
        assertThatThrownBy(() -> servicioAutorizacion.exigirCoordinador(idGrupoB))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo un coordinador puede realizar esta acción");
        assertThat(contarRechazos("ACCESO_GRUPO", idGrupoB, idUsuario)).isEqualTo(1);
        assertThat(contarRechazos("ACCESO_GRUPO", idGrupoA, idUsuario)).isZero();

        // Las dos membresías siguen activas después de operar en ambos grupos.
        assertThat(servicioAutorizacion.exigirMiembro(idGrupoA).rol()).isEqualTo("COORDINADOR");
        assertThat(servicioAutorizacion.exigirMiembro(idGrupoB).rol()).isEqualTo("COLABORADOR");
    }

    @Test
    void administradorSinMembresiaPasaAmbos() {
        long idCreador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixtura.crearGrupo(idCreador);
        String uidAdmin = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);

        ContextoGrupo comoMiembro = servicioAutorizacion.exigirMiembro(idGrupo);
        ContextoGrupo comoCoordinador = servicioAutorizacion.exigirCoordinador(idGrupo);

        for (ContextoGrupo contexto : new ContextoGrupo[] { comoMiembro, comoCoordinador }) {
            assertThat(contexto.usuario().getIdUsuario()).isEqualTo(idAdmin);
            assertThat(contexto.grupo().getIdGrupo()).isEqualTo(idGrupo);
            assertThat(contexto.rol()).isNull();
            assertThat(contexto.esAdministrador()).isTrue();
        }
    }

    @Test
    void grupoInexistenteDevuelve404() {
        String uid = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uid, false);
        autenticar(uid);
        long idInexistente = jdbcTemplate.queryForObject(
                "SELECT NVL(MAX(ID_GRUPO), 0) + 1000000 FROM GRUPO_FAMILIAR", Long.class);

        assertThatThrownBy(() -> servicioAutorizacion.exigirMiembro(idInexistente))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El grupo no existe");
        assertThatThrownBy(() -> servicioAutorizacion.exigirCoordinador(idInexistente))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El grupo no existe");
    }

    @Test
    void noAdministradorNoPasaExigirAdministrador() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        autenticar(uid);

        assertThatThrownBy(() -> servicioAutorizacion.exigirAdministrador())
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo un administrador puede realizar esta acción");
        assertThat(contarRechazos("ACCESO_ADMINISTRADOR", null, idUsuario)).isEqualTo(1);
    }

    @Test
    void administradorPasaExigirAdministrador() {
        String uid = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uid, true);
        autenticar(uid);

        Usuario usuario = servicioAutorizacion.exigirAdministrador();

        assertThat(usuario.getIdUsuario()).isEqualTo(idAdmin);
    }

    /** Eventos RECHAZADA con la acción, grupo (o sin grupo si es null) y actor indicados. */
    private int contarRechazos(String codigoAccion, Long idGrupo, long idActor) {
        String filtroGrupo = idGrupo == null ? "e.ID_GRUPO IS NULL" : "e.ID_GRUPO = " + idGrupo;
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA e "
                        + "JOIN CATALOGO_ESTADOS r ON r.ID_ESTADO = e.ID_RESULTADO "
                        + "WHERE e.CODIGO_ACCION = ? AND e.ID_USUARIO_ACTOR = ? AND " + filtroGrupo
                        + " AND r.CODIGO_ESTADO = 'RECHAZADA'",
                Integer.class, codigoAccion, idActor);
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
