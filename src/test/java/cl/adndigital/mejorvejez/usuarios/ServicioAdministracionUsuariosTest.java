package cl.adndigital.mejorvejez.usuarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioAdministracionUsuarios} contra Oracle real
 * (Testcontainers). Los datos los crea {@link FixturaGrupos} con uids
 * únicos; cuando una prueba necesita filtrar por nombre o email propios se
 * inserta directo con {@link RepositorioUsuario}. Los datos quedan
 * confirmados en la base compartida, salvo {@link
 * #ultimoAdministradorNoPuedeQuitarseElRol()} y {@link
 * #ultimoAdministradorNoPuedeDarseDeBaja()}, que corren en su propia
 * transacción con rollback porque tocan {@code ES_ADMINISTRADOR} de otros
 * usuarios; y salvo la ventana (confirmada, restaurada en un {@code
 * finally}) de {@link #quitarseElRolConcurrentementeConservaUnAdministrador()},
 * que necesita dos hilos con conexión propia y por eso no puede apoyarse en
 * el rollback de una única transacción de prueba.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioAdministracionUsuariosTest {

    @Autowired
    private ServicioAdministracionUsuarios servicio;

    @Autowired
    private RepositorioUsuario repositorioUsuario;

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
    void listarFiltraPorTextoSinDistinguirMayusculas() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);

        String prefijo = UUID.randomUUID().toString();
        Usuario coincideEnEmail = repositorioUsuario.save(
                new Usuario("uid-" + UUID.randomUUID(), prefijo.toUpperCase() + "@ejemplo.cl", "Alguien",
                        idEstado("USUARIO", "ACTIVO"), OffsetDateTime.now()));
        Usuario coincideEnNombre = repositorioUsuario.save(
                new Usuario("uid-" + UUID.randomUUID(), "otro-" + UUID.randomUUID() + "@ejemplo.cl",
                        "Nombre " + prefijo.toLowerCase(), idEstado("USUARIO", "ACTIVO"), OffsetDateTime.now()));

        Page<Usuario> resultado = servicio.listar(prefijo.toLowerCase(), 0, 20);

        assertThat(resultado.getContent())
                .extracting(Usuario::getIdUsuario)
                .containsExactlyInAnyOrder(coincideEnEmail.getIdUsuario(), coincideEnNombre.getIdUsuario());
    }

    @Test
    void buscarConPorcentajeNoEsComodin() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);

        String prefijo = UUID.randomUUID().toString();
        Usuario conGuionBajoLiteral = repositorioUsuario.save(
                new Usuario("uid-" + UUID.randomUUID(), "otro-" + UUID.randomUUID() + "@ejemplo.cl",
                        prefijo + "_coincide", idEstado("USUARIO", "ACTIVO"), OffsetDateTime.now()));
        repositorioUsuario.save(
                new Usuario("uid-" + UUID.randomUUID(), "otro-" + UUID.randomUUID() + "@ejemplo.cl",
                        prefijo + "Xcoincide", idEstado("USUARIO", "ACTIVO"), OffsetDateTime.now()));

        Page<Usuario> resultado = servicio.listar(prefijo + "_coincide", 0, 20);

        assertThat(resultado.getContent())
                .extracting(Usuario::getIdUsuario)
                .containsExactly(conGuionBajoLiteral.getIdUsuario());
    }

    @Test
    void administradorActualizaUsuarioYQuedaAuditado() {
        String uidAdmin = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uidAdmin, true);
        long idUsuario = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        autenticar(uidAdmin);

        Usuario actualizado = servicio.actualizar(idUsuario, "Nuevo Nombre", "+56 9 1234 5678", true);

        assertThat(actualizado.getNombre()).isEqualTo("Nuevo Nombre");
        assertThat(actualizado.getTelefono()).isEqualTo("+56 9 1234 5678");
        assertThat(actualizado.getEsAdministrador()).isTrue();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, DETALLES_JSON FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ADMIN_ACTUALIZAR_USUARIO'",
                String.valueOf(idUsuario));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idAdmin);

        Map<String, Object> detalles = JsonMapper.builder().build().readValue(
                (String) eventos.get(0).get("DETALLES_JSON"), new TypeReference<Map<String, Object>>() {
                });
        @SuppressWarnings("unchecked")
        List<String> campos = (List<String>) detalles.get("campos");
        assertThat(campos).containsExactlyInAnyOrder("nombre", "telefono", "esAdministrador");
    }

    @Test
    void bajaGlobalConservaMembresias() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uidAdmin, true);
        long idUsuario = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idIntegrante = fixtura.agregarIntegrante(idGrupo, idUsuario, "COLABORADOR", "ACTIVO");
        autenticar(uidAdmin);

        Usuario actualizado = servicio.cambiarEstado(idUsuario, idEstado("USUARIO", "INACTIVO"));

        assertThat(actualizado.getIdEstado()).isEqualTo(idEstado("USUARIO", "INACTIVO"));
        Long idEstadoIntegrante = jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM INTEGRANTE_GRUPO WHERE ID_INTEGRANTE = ?", Long.class, idIntegrante);
        assertThat(idEstadoIntegrante).isEqualTo(idEstado("INTEGRANTE_GRUPO", "ACTIVO"));
    }

    @Test
    void estadoDeOtroDominioDevuelve422() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uidAdmin, true);
        long idUsuario = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        autenticar(uidAdmin);
        long idEstadoDeOtroDominio = idEstado("GRUPO_FAMILIAR", "ACTIVO");

        assertThatThrownBy(() -> servicio.cambiarEstado(idUsuario, idEstadoDeOtroDominio))
                .isInstanceOf(ExcepcionReglaNegocio.class);
    }

    @Test
    @Transactional
    void ultimoAdministradorNoPuedeQuitarseElRol() {
        String uidAdmin = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uidAdmin, true);
        jdbcTemplate.update("UPDATE USUARIO SET ES_ADMINISTRADOR = 'N' WHERE ID_USUARIO != ?", idAdmin);
        autenticar(uidAdmin);

        assertThatThrownBy(() -> servicio.actualizar(idAdmin, "Admin", "", false))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Debe existir al menos un administrador activo");
    }

    @Test
    @Transactional
    void ultimoAdministradorNoPuedeDarseDeBaja() {
        String uidAdmin = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uidAdmin, true);
        jdbcTemplate.update("UPDATE USUARIO SET ES_ADMINISTRADOR = 'N' WHERE ID_USUARIO != ?", idAdmin);
        autenticar(uidAdmin);

        assertThatThrownBy(() -> servicio.cambiarEstado(idAdmin, idEstado("USUARIO", "INACTIVO")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Debe existir al menos un administrador activo");
    }

    /**
     * Dos administradores con uid propio se quitan el rol casi al mismo
     * tiempo (hilos separados, cada uno con su propia transacción/conexión,
     * así que un {@code @Transactional} en el método de prueba no sirve
     * acá: no comparte hilo con los dos que ejecutan la operación). Para
     * que la regla "al menos un administrador activo" los cuente solo a
     * ellos dos, se neutraliza temporalmente (confirmado, no en una
     * transacción con rollback) a cualquier otro administrador activo de
     * la base compartida, y se restaura en el {@code finally}: ambos hilos
     * necesitan ver ese estado confirmado desde su propia conexión.
     */
    @Test
    void quitarseElRolConcurrentementeConservaUnAdministrador() throws InterruptedException {
        long idActivo = idEstado("USUARIO", "ACTIVO");
        List<Long> idsAdministradoresActivosOriginales = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO FROM USUARIO WHERE ES_ADMINISTRADOR = 'S' AND ID_ESTADO = ?",
                Long.class, idActivo);
        jdbcTemplate.update("UPDATE USUARIO SET ES_ADMINISTRADOR = 'N' WHERE ES_ADMINISTRADOR = 'S'");

        try {
            String uidA = FixturaGrupos.uidUnico();
            String uidB = FixturaGrupos.uidUnico();
            long idAdminA = fixtura.crearUsuario(uidA, true);
            long idAdminB = fixtura.crearUsuario(uidB, true);

            AtomicInteger exitosos = new AtomicInteger();
            AtomicInteger rechazados = new AtomicInteger();
            CountDownLatch terminaron = new CountDownLatch(2);

            Thread hiloA = new Thread(() -> intentarQuitarseElRol(uidA, idAdminA, exitosos, rechazados, terminaron));
            Thread hiloB = new Thread(() -> intentarQuitarseElRol(uidB, idAdminB, exitosos, rechazados, terminaron));
            hiloA.start();
            hiloB.start();
            boolean terminaronATiempo = terminaron.await(10, TimeUnit.SECONDS);

            assertThat(terminaronATiempo).isTrue();
            assertThat(exitosos.get()).isEqualTo(1);
            assertThat(rechazados.get()).isEqualTo(1);
            long administradoresActivosRestantes = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM USUARIO WHERE ES_ADMINISTRADOR = 'S' AND ID_ESTADO = ? "
                            + "AND ID_USUARIO IN (?, ?)",
                    Long.class, idActivo, idAdminA, idAdminB);
            assertThat(administradoresActivosRestantes).isEqualTo(1);
        } finally {
            jdbcTemplate.update("UPDATE USUARIO SET ES_ADMINISTRADOR = 'N' WHERE ES_ADMINISTRADOR = 'S'");
            for (Long idOriginal : idsAdministradoresActivosOriginales) {
                jdbcTemplate.update("UPDATE USUARIO SET ES_ADMINISTRADOR = 'S' WHERE ID_USUARIO = ?", idOriginal);
            }
        }
    }

    /** Autentica {@code uid} en el hilo en curso e intenta quitarse el rol de administrador. */
    private void intentarQuitarseElRol(String uid, long idUsuario, AtomicInteger exitosos, AtomicInteger rechazados,
            CountDownLatch terminaron) {
        try {
            autenticar(uid);
            servicio.actualizar(idUsuario, "Admin concurrente", "", false);
            exitosos.incrementAndGet();
        } catch (ExcepcionReglaNegocio excepcion) {
            rechazados.incrementAndGet();
        } finally {
            SecurityContextHolder.clearContext();
            terminaron.countDown();
        }
    }

    @Test
    void noAdministradorRecibe403() {
        String uid = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uid, false);
        autenticar(uid);

        assertThatThrownBy(() -> servicio.listar(null, 0, 20))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo un administrador puede realizar esta acción");
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
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
