package cl.adndigital.mejorvejez.grupos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionDemasiadosIntentos;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.cuentas.ProveedorIdentidadFalso;
import cl.adndigital.mejorvejez.interno.ClienteServicios;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;

/**
 * Prueba {@link ServicioIntegrantes} contra Oracle real (Testcontainers). Los
 * datos los crea {@link FixturaGrupos} con uids únicos; quedan confirmados en
 * la base compartida (ver javadoc de {@code ServicioAdministracionUsuariosTest}),
 * salvo {@link #degradacionesConcurrentesConservanUnCoordinador()}, que usa
 * dos hilos con conexión propia (mismo patrón que {@code
 * ServicioAdministracionUsuariosTest#quitarseElRolConcurrentementeConservaUnAdministrador()}),
 * pero sin necesitar neutralizar datos de otros grupos: la regla del último
 * coordinador se evalúa solo dentro del grupo de la prueba.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioIntegrantesTest {

    @Autowired
    private ServicioIntegrantes servicio;

    @Autowired
    private RepositorioRolGrupo repositorioRol;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProveedorIdentidadFalso proveedorFalso;

    @MockitoBean
    private ClienteServicios clienteServicios;

    @MockitoSpyBean
    private ServicioAuditoria servicioAuditoria;

    private FixturaGrupos fixtura;

    @BeforeEach
    void prepararFixtura() {
        fixtura = new FixturaGrupos(jdbcTemplate);
        proveedorFalso.limpiar();
        when(clienteServicios.enviarCorreoCuentaNueva(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
        proveedorFalso.limpiar();
    }

    @Test
    void coordinadorIncorporaPorCorreo() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idNuevo = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        String correoNuevo = correoDe(idNuevo);
        autenticar(uidCoordinador);

        RespuestaIntegrante creado = servicio.incorporar(idGrupo, correoNuevo, idRol("COLABORADOR"));

        assertThat(creado.idUsuario()).isEqualTo(idNuevo);
        assertThat(creado.nombreRol()).isEqualTo("COLABORADOR");
        assertThat(creado.fechaDesactivacion()).isNull();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'INTEGRANTE_GRUPO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'INCORPORAR_INTEGRANTE'",
                String.valueOf(creado.idIntegrante()));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idCoordinador);
    }

    @Test
    void incorporarNormalizaElCorreo() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        String correoMinusculas = "correo-" + java.util.UUID.randomUUID() + "@ejemplo.cl";
        crearUsuarioActivoConCorreo(correoMinusculas);
        autenticar(uidCoordinador);

        RespuestaIntegrante creado = servicio.incorporar(idGrupo, "  " + correoMinusculas.toUpperCase() + "  ",
                idRol("COLABORADOR"));

        assertThat(creado.email()).isEqualTo(correoMinusculas);
    }

    @Test
    void incorporarCorreoSinCuentaDevuelve422() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinador);

        String correoInexistente = "sin-cuenta-" + java.util.UUID.randomUUID() + "@ejemplo.cl";

        assertThatThrownBy(() -> servicio.incorporar(idGrupo, correoInexistente, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("No existe una cuenta con ese correo");
    }

    @Test
    void incorporarCuentaInactivaDevuelve422() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        String correoInactivo = "inactivo-" + java.util.UUID.randomUUID() + "@ejemplo.cl";
        crearUsuarioConCorreoYEstado(correoInactivo, idEstado("USUARIO", "ESTADO", "INACTIVO"));
        autenticar(uidCoordinador);

        assertThatThrownBy(() -> servicio.incorporar(idGrupo, correoInactivo, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La cuenta indicada no está activa");
    }

    @Test
    void incorporarDuplicadoDevuelve409() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idYaIntegrante = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixtura.agregarIntegrante(idGrupo, idYaIntegrante, "COLABORADOR", "ACTIVO");
        String correoYaIntegrante = correoDe(idYaIntegrante);
        autenticar(uidCoordinador);

        assertThatThrownBy(() -> servicio.incorporar(idGrupo, correoYaIntegrante, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("La persona ya es integrante del grupo");
    }

    @Test
    void incorporarInactivoLoReactiva() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idInactivo = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idIntegranteOriginal = fixtura.agregarIntegrante(idGrupo, idInactivo, "COLABORADOR", "INACTIVO");
        String correoInactivo = correoDe(idInactivo);
        autenticar(uidCoordinador);

        RespuestaIntegrante reactivado = servicio.incorporar(idGrupo, correoInactivo, idRol("COORDINADOR"));

        assertThat(reactivado.idIntegrante()).isEqualTo(idIntegranteOriginal);
        assertThat(reactivado.nombreRol()).isEqualTo("COORDINADOR");
        assertThat(reactivado.fechaDesactivacion()).isNull();

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_GRUPO = ? AND ID_USUARIO = ?",
                Long.class, idGrupo, idInactivo);
        assertThat(cantidadFilas).isEqualTo(1);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'INTEGRANTE_GRUPO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'REACTIVAR_INTEGRANTE'",
                String.valueOf(idIntegranteOriginal));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void colaboradorNoPuedeIncorporar() {
        String uidCreador = FixturaGrupos.uidUnico();
        long idCreador = fixtura.crearUsuario(uidCreador, false);
        long idGrupo = fixtura.crearGrupo(idCreador);
        fixtura.agregarIntegrante(idGrupo, idCreador, "COORDINADOR", "ACTIVO");

        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixtura.crearUsuario(uidColaborador, false);
        fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        long idNuevo = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        String correoNuevo = correoDe(idNuevo);

        assertThatThrownBy(() -> servicio.incorporar(idGrupo, correoNuevo, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void coordinadorDeOtroGrupoNoPuedeAdministrar() {
        String uidCreadorA = FixturaGrupos.uidUnico();
        long idCreadorA = fixtura.crearUsuario(uidCreadorA, false);
        long idGrupoA = fixtura.crearGrupo(idCreadorA);
        fixtura.agregarIntegrante(idGrupoA, idCreadorA, "COORDINADOR", "ACTIVO");

        String uidCoordinadorB = FixturaGrupos.uidUnico();
        long idCoordinadorB = fixtura.crearUsuario(uidCoordinadorB, false);
        long idGrupoB = fixtura.crearGrupo(idCoordinadorB);
        fixtura.agregarIntegrante(idGrupoB, idCoordinadorB, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinadorB);

        long idNuevo = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        String correoNuevo = correoDe(idNuevo);

        assertThatThrownBy(() -> servicio.incorporar(idGrupoA, correoNuevo, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    // ------------------------------------------------------------------
    // Alta de un integrante con cuenta nueva (HU-01)
    // ------------------------------------------------------------------

    @Test
    void coordinadorCreaIntegranteConCuentaYRol() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinador);
        String email = correoUnico();

        RespuestaIntegranteCreado creado = servicio.crearIntegrante(idGrupo, "Ana Nueva", email, idRol("COLABORADOR"));

        assertThat(creado.integrante().email()).isEqualTo(email);
        assertThat(creado.integrante().nombreRol()).isEqualTo("COLABORADOR");
        assertThat(creado.integrante().fechaDesactivacion()).isNull();
        assertThat(creado.claveTemporal()).isNotBlank();
        assertThat(creado.correoEnviado()).isTrue();

        Long idUsuarioNuevo = jdbcTemplate.queryForObject("SELECT ID_USUARIO FROM USUARIO WHERE EMAIL = ?",
                Long.class, email);
        String uidNuevo = jdbcTemplate.queryForObject("SELECT UID_FIREBASE FROM USUARIO WHERE ID_USUARIO = ?",
                String.class, idUsuarioNuevo);
        assertThat(proveedorFalso.claveActualDe(uidNuevo)).isEqualTo(creado.claveTemporal());

        List<Map<String, Object>> eventosIncorporar = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'INTEGRANTE_GRUPO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'INCORPORAR_INTEGRANTE'",
                String.valueOf(creado.integrante().idIntegrante()));
        assertThat(eventosIncorporar).hasSize(1);
        assertThat(((Number) eventosIncorporar.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idCoordinador);

        List<Map<String, Object>> eventosCrearCuenta = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_CUENTA'",
                String.valueOf(idUsuarioNuevo));
        assertThat(eventosCrearCuenta).hasSize(1);
        assertThat(((Number) eventosCrearCuenta.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idCoordinador);
    }

    @Test
    void colaboradorNoPuedeCrearIntegrante403() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixtura.crearUsuario(uidColaborador, false);
        fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);
        String email = correoUnico();

        assertThatThrownBy(() -> servicio.crearIntegrante(idGrupo, "Ana", email, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionAccesoDenegado.class);

        assertThat(proveedorFalso.buscarPorCorreo(email)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USUARIO WHERE EMAIL = ?", Long.class, email))
                .isZero();
    }

    @Test
    void correoExistenteDa409() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinador);

        long idYaExistente = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        String correoExistente = correoDe(idYaExistente);

        assertThatThrownBy(() -> servicio.crearIntegrante(idGrupo, "Ana", correoExistente, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("Ya existe una cuenta con ese correo");
    }

    @Test
    void rolInexistenteNoDejaCuentaCreada() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinador);
        String email = correoUnico();

        assertThatThrownBy(() -> servicio.crearIntegrante(idGrupo, "Ana", email, idRolInexistente()))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El rol indicado no existe");

        assertThat(proveedorFalso.buscarPorCorreo(email)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USUARIO WHERE EMAIL = ?", Long.class, email))
                .isZero();
    }

    @Test
    void adminPuedeCrearIntegranteEnCualquierGrupo() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        String uidAdmin = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);
        String email = correoUnico();

        RespuestaIntegranteCreado creado = servicio.crearIntegrante(idGrupo, "Ana", email, idRol("COLABORADOR"));

        assertThat(creado.integrante().email()).isEqualTo(email);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'INTEGRANTE_GRUPO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'INCORPORAR_INTEGRANTE'",
                String.valueOf(creado.integrante().idIntegrante()));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idAdmin);
    }

    @Test
    void fallaAlIncorporarBorraLaCuentaDeFirebaseYNoDejaUsuario() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinador);
        String email = correoUnico();
        doThrow(new RuntimeException("Falla simulada al incorporar")).when(servicioAuditoria)
                .registrar(argThat(evento -> evento != null && "INCORPORAR_INTEGRANTE".equals(evento.codigoAccion())
                        && Long.valueOf(idGrupo).equals(evento.idGrupo())));

        assertThatThrownBy(() -> servicio.crearIntegrante(idGrupo, "Ana", email, idRol("COLABORADOR")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falla simulada al incorporar");

        assertThat(proveedorFalso.buscarPorCorreo(email)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USUARIO WHERE EMAIL = ?", Long.class, email))
                .isZero();
    }

    @Test
    void coordinadorQueSuperaElLimiteDeAltasRecibe429SinTocarElProveedor() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uidCoordinador);
        for (int i = 0; i < 20; i++) {
            servicio.crearIntegrante(idGrupo, "Persona " + i, correoUnico(), idRol("COLABORADOR"));
        }
        // Si el alta llegara al proveedor, fallaría con otro error: el 429 prueba que no se lo llamó.
        proveedorFalso.forzarErrorAlBuscar(new IllegalStateException("No se debía consultar el proveedor"));
        proveedorFalso.forzarErrorAlCrear(new IllegalStateException("No se debía crear la cuenta"));
        String email = correoUnico();

        assertThatThrownBy(() -> servicio.crearIntegrante(idGrupo, "Persona 21", email, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionDemasiadosIntentos.class)
                .hasMessage("Demasiados intentos. Prueba más tarde.");

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USUARIO WHERE EMAIL = ?", Long.class, email))
                .isZero();
    }

    @Test
    void administradorNoTieneLimiteDeAltas() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        String uidAdmin = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uidAdmin, true);
        autenticar(uidAdmin);

        for (int i = 0; i < 21; i++) {
            servicio.crearIntegrante(idGrupo, "Persona " + i, correoUnico(), idRol("COLABORADOR"));
        }

        assertThat(servicio.crearIntegrante(idGrupo, "Persona 22", correoUnico(), idRol("COLABORADOR"))
                .integrante().nombreRol()).isEqualTo("COLABORADOR");
    }

    @Test
    void usuarioSaleDeUnGrupoSinPerderOtrasMembresias() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupoA = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupoA, idUsuario, "COLABORADOR", "ACTIVO");
        long idGrupoB = fixtura.crearGrupo(idUsuario);
        fixtura.agregarIntegrante(idGrupoB, idUsuario, "COLABORADOR", "ACTIVO");
        autenticar(uid);

        servicio.salir(idGrupoA);

        assertThat(estadoIntegrante(idGrupoA, idUsuario)).isEqualTo(idEstado("INTEGRANTE_GRUPO", "ESTADO", "INACTIVO"));
        assertThat(estadoIntegrante(idGrupoB, idUsuario)).isEqualTo(idEstado("INTEGRANTE_GRUPO", "ESTADO", "ACTIVO"));
    }

    @Test
    void desactivacionEsLogicaYConservaHistorial() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idColaborador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idIntegranteColaborador = fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidCoordinador);

        RespuestaIntegrante desactivado = servicio.desactivar(idGrupo, idIntegranteColaborador);

        assertThat(desactivado.idEstado()).isEqualTo(idEstado("INTEGRANTE_GRUPO", "ESTADO", "INACTIVO"));
        assertThat(desactivado.fechaDesactivacion()).isNotNull();

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_INTEGRANTE = ?", Long.class, idIntegranteColaborador);
        assertThat(cantidadFilas).isEqualTo(1);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'INTEGRANTE_GRUPO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'DESACTIVAR_INTEGRANTE'",
                String.valueOf(idIntegranteColaborador));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void ultimoCoordinadorNoPuedeDejarDeSerlo() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idIntegrante = fixtura.agregarIntegrante(idGrupo, idUsuario, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.cambiarRol(idGrupo, idIntegrante, idRol("COLABORADOR")))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El grupo debe conservar al menos un coordinador activo");

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idIntegrante))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El grupo debe conservar al menos un coordinador activo");

        assertThatThrownBy(() -> servicio.salir(idGrupo))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El grupo debe conservar al menos un coordinador activo");

        assertThat(estadoIntegrante(idGrupo, idUsuario)).isEqualTo(idEstado("INTEGRANTE_GRUPO", "ESTADO", "ACTIVO"));
    }

    @Test
    void degradacionesConcurrentesConservanUnCoordinador() throws InterruptedException {
        String uidA = FixturaGrupos.uidUnico();
        String uidB = FixturaGrupos.uidUnico();
        long idUsuarioA = fixtura.crearUsuario(uidA, false);
        long idUsuarioB = fixtura.crearUsuario(uidB, false);
        long idGrupo = fixtura.crearGrupo(idUsuarioA);
        long idIntegranteA = fixtura.agregarIntegrante(idGrupo, idUsuarioA, "COORDINADOR", "ACTIVO");
        long idIntegranteB = fixtura.agregarIntegrante(idGrupo, idUsuarioB, "COORDINADOR", "ACTIVO");
        long idRolColaborador = idRol("COLABORADOR");

        AtomicInteger exitosos = new AtomicInteger();
        AtomicInteger rechazados = new AtomicInteger();
        CountDownLatch terminaron = new CountDownLatch(2);

        Thread hiloA = new Thread(() -> intentarDegradar(uidA, idGrupo, idIntegranteB, idRolColaborador, exitosos,
                rechazados, terminaron));
        Thread hiloB = new Thread(() -> intentarDegradar(uidB, idGrupo, idIntegranteA, idRolColaborador, exitosos,
                rechazados, terminaron));
        hiloA.start();
        hiloB.start();
        boolean terminaronATiempo = terminaron.await(10, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(exitosos.get()).isEqualTo(1);
        assertThat(rechazados.get()).isEqualTo(1);

        long idEstadoActivo = idEstado("INTEGRANTE_GRUPO", "ESTADO", "ACTIVO");
        long idRolCoordinador = idRol("COORDINADOR");
        Long coordinadoresActivos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_GRUPO = ? AND ID_ROL = ? AND ID_ESTADO = ?",
                Long.class, idGrupo, idRolCoordinador, idEstadoActivo);
        assertThat(coordinadoresActivos).isEqualTo(1);
    }

    /**
     * Intenta degradar a {@code idIntegranteObjetivo}. Tras la corrección
     * ronda 1 (bloquear el grupo ANTES de autorizar/leer), el hilo que pierde
     * la carrera por el bloqueo puede rechazarse por dos vías igualmente
     * válidas, según el momento exacto: {@link ExcepcionReglaNegocio} (422,
     * dejaría al grupo sin coordinador activo) si su propio rol sigue intacto,
     * o {@link ExcepcionAccesoDenegado} (403, ya no es coordinador) si el
     * objetivo que el otro hilo degradó era, precisamente, el propio actor
     * (como en este test, donde A degrada a B y B degrada a A): al re-leer su
     * membresía ya con el bloqueo tomado, puede ver su propia degradación ya
     * confirmada por el otro hilo. Ambas vías cuentan como "rechazado": lo
     * que importa es que exactamente un hilo tenga éxito y el otro no.
     */
    private void intentarDegradar(String uid, long idGrupo, long idIntegranteObjetivo, long idRolNuevo,
            AtomicInteger exitosos, AtomicInteger rechazados, CountDownLatch terminaron) {
        try {
            autenticar(uid);
            servicio.cambiarRol(idGrupo, idIntegranteObjetivo, idRolNuevo);
            exitosos.incrementAndGet();
        } catch (ExcepcionReglaNegocio | ExcepcionAccesoDenegado excepcion) {
            rechazados.incrementAndGet();
        } finally {
            SecurityContextHolder.clearContext();
            terminaron.countDown();
        }
    }

    /**
     * Corrección ronda 1, hallazgo 1: reproduce la carrera "U (colaborador)
     * llama salir y espera el bloqueo; C (único coordinador) asciende a U y
     * luego sale; U retoma con una copia vieja de su propia membresía y se
     * salta la regla". Con el orden corregido (bloquear el grupo ANTES de
     * autorizar/leer), sin importar qué hilo gane la carrera por el bloqueo,
     * el grupo nunca debe quedar en 0 coordinadores activos: se verifica el
     * invariante final, no un orden de hilos específico (no es controlable
     * de forma determinista desde el test sin instrumentar el servicio).
     */
    @Test
    void salirConcurrenteConAscensoConservaUnCoordinador() throws InterruptedException {
        String uidCoordinador = FixturaGrupos.uidUnico();
        String uidColaborador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idColaborador = fixtura.crearUsuario(uidColaborador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idIntegranteColaborador = fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        long idRolCoordinador = idRol("COORDINADOR");

        AtomicReference<Throwable> errorInesperado = new AtomicReference<>();
        CountDownLatch terminaron = new CountDownLatch(2);

        Thread hiloColaborador = new Thread(() -> {
            try {
                autenticar(uidColaborador);
                try {
                    servicio.salir(idGrupo);
                } catch (ExcepcionReglaNegocio esperada) {
                    // rechazo válido si, para entonces, ya era el único coordinador activo.
                }
            } catch (Throwable inesperado) {
                errorInesperado.compareAndSet(null, inesperado);
            } finally {
                SecurityContextHolder.clearContext();
                terminaron.countDown();
            }
        });

        Thread hiloCoordinador = new Thread(() -> {
            try {
                autenticar(uidCoordinador);
                try {
                    servicio.cambiarRol(idGrupo, idIntegranteColaborador, idRolCoordinador);
                } catch (ExcepcionReglaNegocio ignorada) {
                    // la fila del colaborador pudo haber cambiado de estado antes.
                }
                try {
                    servicio.salir(idGrupo);
                } catch (ExcepcionReglaNegocio esperada) {
                    // rechazo válido si, para entonces, quedó como único coordinador activo.
                }
            } catch (Throwable inesperado) {
                errorInesperado.compareAndSet(null, inesperado);
            } finally {
                SecurityContextHolder.clearContext();
                terminaron.countDown();
            }
        });

        hiloColaborador.start();
        hiloCoordinador.start();
        boolean terminaronATiempo = terminaron.await(15, TimeUnit.SECONDS);

        assertThat(terminaronATiempo).isTrue();
        assertThat(errorInesperado.get()).isNull();

        long idEstadoActivo = idEstado("INTEGRANTE_GRUPO", "ESTADO", "ACTIVO");
        Long coordinadoresActivos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_GRUPO = ? AND ID_ROL = ? AND ID_ESTADO = ?",
                Long.class, idGrupo, idRolCoordinador, idEstadoActivo);
        assertThat(coordinadoresActivos).isGreaterThanOrEqualTo(1);
    }

    @Test
    void eliminarUltimoCoordinadorDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        long idGrupo = fixtura.crearGrupo(idUsuario);
        long idIntegrante = fixtura.agregarIntegrante(idGrupo, idUsuario, "COORDINADOR", "ACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.eliminar(idGrupo, idIntegrante))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El grupo debe conservar al menos un coordinador activo");

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_INTEGRANTE = ?", Long.class, idIntegrante);
        assertThat(cantidadFilas).isEqualTo(1);
    }

    @Test
    void reactivarYaActivaDevuelve409() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idColaborador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idIntegranteColaborador = fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidCoordinador);

        assertThatThrownBy(() -> servicio.reactivar(idGrupo, idIntegranteColaborador))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("La persona ya es integrante del grupo");
    }

    @Test
    void reactivarCuentaInactivaDevuelve422() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idColaborador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idIntegranteColaborador = fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "INACTIVO");
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?",
                idEstado("USUARIO", "ESTADO", "INACTIVO"), idColaborador);
        autenticar(uidCoordinador);

        assertThatThrownBy(() -> servicio.reactivar(idGrupo, idIntegranteColaborador))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La cuenta indicada no está activa");
    }

    @Test
    void salirSinMembresiaActivaQuedaAuditadaComoRechazada() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uidAdmin, true);
        long idCoordinador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        autenticar(uidAdmin);

        assertThatThrownBy(() -> servicio.salir(idGrupo))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("No perteneces a este grupo");

        long idResultadoRechazada = idEstado("EVENTO_AUDITORIA", "RESULTADO", "RECHAZADA");
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'GRUPO_FAMILIAR' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'SALIR_GRUPO' AND ID_RESULTADO = ?",
                String.valueOf(idGrupo), idResultadoRechazada);
        assertThat(eventos).hasSize(1);
    }

    @Test
    void eliminarSinHistorialBorraLaFila() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idColaborador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idIntegranteColaborador = fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidCoordinador);

        servicio.eliminar(idGrupo, idIntegranteColaborador);

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_INTEGRANTE = ?", Long.class, idIntegranteColaborador);
        assertThat(cantidadFilas).isZero();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'INTEGRANTE_GRUPO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ELIMINAR_INTEGRANTE'",
                String.valueOf(idIntegranteColaborador));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void eliminarConHistorialDevuelve422() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idColaborador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idIntegranteColaborador = fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        insertarEventoHistorial(idGrupo, idColaborador);
        autenticar(uidCoordinador);

        assertThatThrownBy(() -> servicio.eliminar(idGrupo, idIntegranteColaborador))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La membresía tiene historial; desactívala en su lugar");

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_INTEGRANTE = ?", Long.class, idIntegranteColaborador);
        assertThat(cantidadFilas).isEqualTo(1);
    }

    /**
     * Tarea 6 de la Fase 4 (HU-07): un cambio de estado de una tarea de
     * cuidado del grupo, hecho por el colaborador, también cuenta como
     * historial de uso (EXISTS agregado a {@link RepositorioHistorialIntegrante}
     * sobre {@code HISTORIAL_ESTADO_TAREA} join {@code TAREA_CUIDADO}).
     */
    @Test
    void eliminarConHistorialDeCambioDeEstadoDeTareaDevuelve422() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idColaborador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idIntegranteColaborador = fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        FixturaModelo fixturaModelo = new FixturaModelo(jdbcTemplate);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idTarea = fixturaModelo.crearTareaManual(idGrupo, idAdulto);
        insertarHistorialEstadoTarea(idTarea, idColaborador);
        autenticar(uidCoordinador);

        assertThatThrownBy(() -> servicio.eliminar(idGrupo, idIntegranteColaborador))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La membresía tiene historial; desactívala en su lugar");

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_INTEGRANTE = ?", Long.class, idIntegranteColaborador);
        assertThat(cantidadFilas).isEqualTo(1);
    }

    /**
     * Revisión final de F4: un colaborador al que el coordinador le asignó
     * una tarea, aunque nunca haya cambiado un estado, tiene historial
     * ({@code EXISTS} sobre {@code ASIGNACION_TAREA} por asignado o asignador
     * en {@link RepositorioHistorialIntegrante}) y no se elimina físicamente.
     */
    @Test
    void eliminarColaboradorConTareaAsignadaDevuelve422() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixtura.crearUsuario(uidCoordinador, false);
        long idGrupo = fixtura.crearGrupo(idCoordinador);
        fixtura.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");

        long idColaborador = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idIntegranteColaborador = fixtura.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        FixturaModelo fixturaModelo = new FixturaModelo(jdbcTemplate);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idTarea = fixturaModelo.crearTareaManual(idGrupo, idAdulto);
        new FixturaTareas(jdbcTemplate).crearAsignacion(idTarea, idGrupo, idColaborador, idCoordinador);
        autenticar(uidCoordinador);

        assertThatThrownBy(() -> servicio.eliminar(idGrupo, idIntegranteColaborador))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La membresía tiene historial; desactívala en su lugar");

        Long cantidadFilas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INTEGRANTE_GRUPO WHERE ID_INTEGRANTE = ?", Long.class, idIntegranteColaborador);
        assertThat(cantidadFilas).isEqualTo(1);
    }

    @Test
    void integranteDeOtroGrupoDevuelve404() {
        String uidCoordinadorA = FixturaGrupos.uidUnico();
        long idCoordinadorA = fixtura.crearUsuario(uidCoordinadorA, false);
        long idGrupoA = fixtura.crearGrupo(idCoordinadorA);
        fixtura.agregarIntegrante(idGrupoA, idCoordinadorA, "COORDINADOR", "ACTIVO");

        long idUsuarioB = fixtura.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoB = fixtura.crearGrupo(idUsuarioB);
        long idIntegranteB = fixtura.agregarIntegrante(idGrupoB, idUsuarioB, "COLABORADOR", "ACTIVO");
        autenticar(uidCoordinadorA);

        assertThatThrownBy(() -> servicio.desactivar(idGrupoA, idIntegranteB))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El integrante no existe");
    }

    private void insertarHistorialEstadoTarea(long idTarea, long idUsuario) {
        long idEstadoNuevo = idEstado("TAREA_CUIDADO", "ESTADO", "EN_CURSO");
        jdbcTemplate.update(
                "INSERT INTO HISTORIAL_ESTADO_TAREA (ID_TAREA, ID_ESTADO_NUEVO, ID_USUARIO_CAMBIO, FECHA_CAMBIO) "
                        + "VALUES (?, ?, ?, SYSTIMESTAMP)",
                idTarea, idEstadoNuevo, idUsuario);
    }

    private void insertarEventoHistorial(long idGrupo, long idUsuario) {
        long idResultadoExito = idEstado("EVENTO_AUDITORIA", "RESULTADO", "EXITO");
        jdbcTemplate.update(
                "INSERT INTO EVENTO_AUDITORIA "
                        + "(NOMBRE_SERVICIO, TIPO_ENTIDAD, ID_ENTIDAD, ID_GRUPO, ID_USUARIO_ACTOR, CODIGO_ACCION, ID_RESULTADO) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                "prueba", "PRUEBA_HISTORIAL", "1", idGrupo, idUsuario, "ACCION_PRUEBA_HISTORIAL", idResultadoExito);
    }

    private long estadoIntegrante(long idGrupo, long idUsuario) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM INTEGRANTE_GRUPO WHERE ID_GRUPO = ? AND ID_USUARIO = ?",
                Long.class, idGrupo, idUsuario);
    }

    private String correoDe(long idUsuario) {
        return jdbcTemplate.queryForObject("SELECT EMAIL FROM USUARIO WHERE ID_USUARIO = ?", String.class, idUsuario);
    }

    private void crearUsuarioActivoConCorreo(String correo) {
        crearUsuarioConCorreoYEstado(correo, idEstado("USUARIO", "ESTADO", "ACTIVO"));
    }

    private void crearUsuarioConCorreoYEstado(String correo, long idEstado) {
        jdbcTemplate.update(
                "INSERT INTO USUARIO (UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) VALUES (?, ?, ?, 'N', ?)",
                FixturaGrupos.uidUnico(), correo, "Usuario fixtura", idEstado);
    }

    private long idRol(String nombreRol) {
        return repositorioRol.findByNombreRol(nombreRol).orElseThrow().getIdRol();
    }

    private long idRolInexistente() {
        Long maximo = jdbcTemplate.queryForObject("SELECT MAX(ID_ROL) FROM ROL_GRUPO", Long.class);
        return (maximo == null ? 0L : maximo) + 1_000_000L;
    }

    private String correoUnico() {
        return "integrante-" + UUID.randomUUID() + "@mejorvejez.test";
    }

    private long idEstado(String tabla, String campo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = ? AND CODIGO_ESTADO = ?",
                Long.class, tabla, campo, codigo);
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
