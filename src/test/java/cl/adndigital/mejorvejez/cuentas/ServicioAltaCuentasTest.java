package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionCuentasNoConfiguradas;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.interno.ClienteServicios;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Prueba {@link ServicioAltaCuentas} contra Oracle real (Testcontainers) con
 * {@link ProveedorIdentidadFalso} (nunca Firebase real) y {@link
 * ClienteServicios} simulado con Mockito (Alertas tiene su propia ruta
 * interna aparte, probada en {@code ClienteServiciosTest}).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioAltaCuentasTest {

    @Autowired
    private ServicioAltaCuentas servicioAltaCuentas;

    @Autowired
    private ProveedorIdentidadFalso proveedorFalso;

    @Autowired
    private RepositorioUsuario repositorioUsuario;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void adminCreaCuentaActivaNoAdministrador() {
        autenticarComoAdministrador();
        String email = correoUnico();

        CuentaCreada creada = servicioAltaCuentas.crearCuenta("Ana Pérez", email, "+56912345678");

        assertThat(creada.usuario().getIdEstado()).isEqualTo(idEstado("USUARIO", "ACTIVO"));
        assertThat(creada.usuario().getEsAdministrador()).isFalse();
        assertThat(creada.usuario().getEmail()).isEqualTo(email);
        assertThat(creada.correoEnviado()).isTrue();
        assertThat(proveedorFalso.claveActualDe(creada.usuario().getUidFirebase())).isEqualTo(creada.claveTemporal());
        verify(clienteServicios).enviarCorreoCuentaNueva("Ana Pérez", email, creada.claveTemporal());
    }

    @Test
    void correoExistenteEnCoreDa409() {
        autenticarComoAdministrador();
        String email = correoUnico();
        repositorioUsuario.save(new Usuario("uid-" + UUID.randomUUID(), email, "Alguien",
                idEstado("USUARIO", "ACTIVO"), OffsetDateTime.now()));

        assertThatThrownBy(() -> servicioAltaCuentas.crearCuenta("Otro", email, null))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("Ya existe una cuenta con ese correo");
    }

    @Test
    void correoExistenteSoloEnFirebaseDa409() {
        autenticarComoAdministrador();
        String email = correoUnico();
        proveedorFalso.agregarCuentaExistente(email);

        assertThatThrownBy(() -> servicioAltaCuentas.crearCuenta("Otro", email, null))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("Ya existe una cuenta con ese correo");
    }

    @Test
    void correoSeNormalizaAntesDeValidarDuplicado() {
        autenticarComoAdministrador();
        String email = correoUnico();
        repositorioUsuario.save(new Usuario("uid-" + UUID.randomUUID(), email, "Alguien",
                idEstado("USUARIO", "ACTIVO"), OffsetDateTime.now()));
        String mismoCorreoConEspaciosYMayusculas = "  " + email.substring(0, 1).toUpperCase()
                + email.substring(1).toUpperCase() + "  ";

        assertThatThrownBy(() -> servicioAltaCuentas.crearCuenta("Otro", mismoCorreoConEspaciosYMayusculas, null))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("Ya existe una cuenta con ese correo");
    }

    @Test
    void fallaLaBaseSeBorraLaCuentaDeFirebase() {
        long idActor = fixtura.crearUsuario(FixturaGrupos.uidUnico(), true);
        String email = correoUnico();
        AltaCuenta alta = new AltaCuenta("Ana", email, null, OrigenCuenta.ADMINISTRADOR, idActor, null, false,
                usuario -> {
                    throw new RuntimeException("Falla simulada");
                });

        assertThatThrownBy(() -> servicioAltaCuentas.crear(alta))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falla simulada");

        assertThat(proveedorFalso.buscarPorCorreo(email)).isEmpty();
        assertThat(repositorioUsuario.findByEmail(email)).isEmpty();
    }

    @Test
    void fallaLaCompensacionConservaElErrorOriginal() {
        long idActor = fixtura.crearUsuario(FixturaGrupos.uidUnico(), true);
        String email = correoUnico();
        proveedorFalso.forzarErrorAlEliminar(new RuntimeException("Error al eliminar"));
        AltaCuenta alta = new AltaCuenta("Ana", email, null, OrigenCuenta.ADMINISTRADOR, idActor, null, false,
                usuario -> {
                    throw new RuntimeException("Falla simulada");
                });

        assertThatThrownBy(() -> servicioAltaCuentas.crear(alta))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falla simulada")
                .satisfies(excepcion -> assertThat(excepcion.getSuppressed())
                        .anySatisfy(suprimida -> assertThat(suprimida).hasMessage("Error al eliminar")));
    }

    @Test
    void alertasCaidoCuentaCreadaSinCorreo() {
        autenticarComoAdministrador();
        when(clienteServicios.enviarCorreoCuentaNueva(any(), any(), any())).thenReturn(false);
        String email = correoUnico();

        CuentaCreada creada = servicioAltaCuentas.crearCuenta("Ana", email, null);

        assertThat(creada.correoEnviado()).isFalse();
        assertThat(creada.claveTemporal()).isNotBlank();
        assertThat(repositorioUsuario.findByEmail(email)).isPresent();
    }

    @Test
    void claveNoQuedaEnAuditoria() {
        long idAdmin = autenticarComoAdministrador();
        String email = correoUnico();

        CuentaCreada creada = servicioAltaCuentas.crearCuenta("Ana", email, null);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, DETALLES_JSON FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_CUENTA'",
                String.valueOf(creada.usuario().getIdUsuario()));
        assertThat(eventos).hasSize(1);
        String detallesJson = (String) eventos.get(0).get("DETALLES_JSON");
        assertThat(detallesJson).doesNotContain(creada.claveTemporal());
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idAdmin);
    }

    @Test
    void crearConOrigenRegistroSinActorUsaAlUsuarioCreadoComoActor() {
        String email = correoUnico();
        AltaCuenta alta = new AltaCuenta("Ana", email, null, OrigenCuenta.REGISTRO, null, "Clave-Elegida-123", false,
                usuario -> { });

        CuentaCreada creada = servicioAltaCuentas.crear(alta);

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_CUENTA'",
                String.valueOf(creada.usuario().getIdUsuario()));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue())
                .isEqualTo(creada.usuario().getIdUsuario());
    }

    @Test
    void altaCuentaSinIdActorYOrigenDistintoDeRegistroLanza() {
        assertThatThrownBy(() -> new AltaCuenta("Ana", correoUnico(), null, OrigenCuenta.ADMINISTRADOR, null, null,
                false, usuario -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noAdministradorDa403() {
        String uid = FixturaGrupos.uidUnico();
        fixtura.crearUsuario(uid, false);
        autenticar(uid);

        assertThatThrownBy(() -> servicioAltaCuentas.crearCuenta("Ana", correoUnico(), null))
                .isInstanceOf(ExcepcionAccesoDenegado.class)
                .hasMessage("Solo un administrador puede realizar esta acción");
    }

    @Test
    void sinCredencialDa503() {
        autenticarComoAdministrador();
        proveedorFalso.forzarErrorAlBuscar(new ExcepcionCuentasNoConfiguradas());

        assertThatThrownBy(() -> servicioAltaCuentas.crearCuenta("Ana", correoUnico(), null))
                .isInstanceOf(ExcepcionCuentasNoConfiguradas.class);
    }

    @Test
    void reenviarAccesoFijaClaveNuevaYEnvia() {
        long idAdmin = autenticarComoAdministrador();
        String uidUsuario = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uidUsuario, false);
        proveedorFalso.fijarClave(uidUsuario, "Clave-Inicial-Unica1");
        Usuario usuario = repositorioUsuario.findById(idUsuario).orElseThrow();
        when(clienteServicios.enviarCorreoCuentaNueva(any(), any(), any())).thenReturn(true);

        CuentaCreada resultado = servicioAltaCuentas.reenviarAcceso(idUsuario);

        assertThat(resultado.claveTemporal()).isNotEqualTo("Clave-Inicial-Unica1");
        assertThat(proveedorFalso.claveActualDe(uidUsuario)).isEqualTo(resultado.claveTemporal());
        assertThat(resultado.correoEnviado()).isTrue();
        verify(clienteServicios).enviarCorreoCuentaNueva(usuario.getNombre(), usuario.getEmail(),
                resultado.claveTemporal());

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR, DETALLES_JSON FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'REENVIAR_ACCESO'",
                String.valueOf(idUsuario));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idAdmin);
    }

    @Test
    void reenviarAccesoUsuarioInexistenteDa404() {
        autenticarComoAdministrador();
        long idInexistente = idInexistente();

        assertThatThrownBy(() -> servicioAltaCuentas.reenviarAcceso(idInexistente))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El usuario no existe");
    }

    @Test
    void reenviarAccesoACuentaInactivaDa422YNoFijaClave() {
        autenticarComoAdministrador();
        String uidUsuario = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uidUsuario, false);
        proveedorFalso.fijarClave(uidUsuario, "Clave-Inicial-Unica2");
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?",
                idEstado("USUARIO", "INACTIVO"), idUsuario);

        assertThatThrownBy(() -> servicioAltaCuentas.reenviarAcceso(idUsuario))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La cuenta está inactiva");

        assertThat(proveedorFalso.claveActualDe(uidUsuario)).isEqualTo("Clave-Inicial-Unica2");
    }

    @Test
    void reenviarAccesoConFallaAlAuditarNoCambiaLaClave() {
        autenticarComoAdministrador();
        String uidUsuario = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uidUsuario, false);
        proveedorFalso.fijarClave(uidUsuario, "Clave-Inicial-Unica3");
        doThrow(new RuntimeException("Falla simulada al auditar")).when(servicioAuditoria)
                .registrar(argThat(evento -> esReenvioDe(evento, idUsuario)));

        assertThatThrownBy(() -> servicioAltaCuentas.reenviarAcceso(idUsuario))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falla simulada al auditar");

        assertThat(proveedorFalso.claveActualDe(uidUsuario)).isEqualTo("Clave-Inicial-Unica3");
        verify(clienteServicios, never()).enviarCorreoCuentaNueva(any(), any(), any());
    }

    @Test
    void reenviarAccesoConFallaAlFijarClaveNoDejaAuditoria() {
        autenticarComoAdministrador();
        String uidUsuario = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uidUsuario, false);
        proveedorFalso.fijarClave(uidUsuario, "Clave-Inicial-Unica4");
        proveedorFalso.forzarErrorAlFijarClave(new RuntimeException("Falla simulada al fijar la clave"));

        assertThatThrownBy(() -> servicioAltaCuentas.reenviarAcceso(idUsuario))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falla simulada al fijar la clave");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'REENVIAR_ACCESO'",
                Long.class, String.valueOf(idUsuario))).isZero();
        assertThat(proveedorFalso.claveActualDe(uidUsuario)).isEqualTo("Clave-Inicial-Unica4");
        verify(clienteServicios, never()).enviarCorreoCuentaNueva(any(), any(), any());
    }

    private static boolean esReenvioDe(EventoARegistrar evento, long idUsuario) {
        return evento != null && "REENVIAR_ACCESO".equals(evento.codigoAccion())
                && String.valueOf(idUsuario).equals(evento.idEntidad());
    }

    /** Autentica como un administrador nuevo y devuelve su {@code idUsuario}. */
    private long autenticarComoAdministrador() {
        String uid = FixturaGrupos.uidUnico();
        long idAdmin = fixtura.crearUsuario(uid, true);
        autenticar(uid, true);
        return idAdmin;
    }

    private void autenticar(String uid) {
        autenticar(uid, false);
    }

    private void autenticar(String uid, boolean admin) {
        if (repositorioUsuario.findByUidFirebase(uid).isEmpty()) {
            fixtura.crearUsuario(uid, admin);
        }
        Jwt jwt = Jwt.withTokenValue("token-de-prueba")
                .header("alg", "RS256")
                .subject(uid)
                .claim("email", uid + "@ejemplo.cl")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private long idInexistente() {
        Long maximo = jdbcTemplate.queryForObject("SELECT MAX(ID_USUARIO) FROM USUARIO", Long.class);
        return (maximo == null ? 0L : maximo) + 1_000_000L;
    }

    private String correoUnico() {
        return "cuenta-" + UUID.randomUUID() + "@mejorvejez.test";
    }
}
