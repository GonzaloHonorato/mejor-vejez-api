package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionCuentasNoConfiguradas;
import cl.adndigital.mejorvejez.comun.ExcepcionDemasiadosIntentos;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.interno.ClienteServicios;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;

/**
 * Prueba {@link ServicioRecuperarClave} contra Oracle real (Testcontainers)
 * con {@link ProveedorIdentidadFalso} (nunca Firebase real), {@link
 * ClienteServicios} simulado con Mockito y el executor de envío
 * reemplazado por uno síncrono ({@code ConfiguracionTestcontainers}), para
 * poder verificar el envío sin esperar.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioRecuperarClaveTest {

    @Autowired
    private ServicioRecuperarClave servicioRecuperarClave;

    @Autowired
    private ProveedorIdentidadFalso proveedorFalso;

    @Autowired
    private RepositorioUsuario repositorioUsuario;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ClienteServicios clienteServicios;

    private FixturaGrupos fixtura;

    @BeforeEach
    void prepararFixtura() {
        fixtura = new FixturaGrupos(jdbcTemplate);
        proveedorFalso.limpiar();
        when(clienteServicios.enviarCorreoRecuperarClave(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void limpiarFixtura() {
        proveedorFalso.limpiar();
    }

    @Test
    void cuentaActivaRecibeEnlaceConCodigo() {
        UsuarioDePrueba usuario = crearUsuarioActivo();

        servicioRecuperarClave.solicitar(usuario.correo(), ipUnica());

        String oobCode = proveedorFalso.ultimoOobCodeGenerado();
        assertThat(oobCode).isNotBlank();
        verify(clienteServicios).enviarCorreoRecuperarClave(eq("Usuario fixtura"), eq(usuario.correo()),
                eq("http://localhost:4200/restablecer-clave#codigo=" + oobCode));
    }

    @Test
    void correoInexistenteRespondeIgualYNoEnvia() {
        servicioRecuperarClave.solicitar(correoUnico(), ipUnica());

        verify(clienteServicios, never()).enviarCorreoRecuperarClave(any(), any(), any());
    }

    @Test
    void cuentaInactivaRespondeIgualYNoEnvia() {
        UsuarioDePrueba usuario = crearUsuarioActivo();
        jdbcTemplate.update("UPDATE USUARIO SET ID_ESTADO = ? WHERE ID_USUARIO = ?",
                idEstado("USUARIO", "INACTIVO"), usuario.idUsuario());

        servicioRecuperarClave.solicitar(usuario.correo(), ipUnica());

        verify(clienteServicios, never()).enviarCorreoRecuperarClave(any(), any(), any());
    }

    @Test
    void cuartoPedidoDelMismoCorreoDa429() {
        String correo = correoUnico();

        servicioRecuperarClave.solicitar(correo, ipUnica());
        servicioRecuperarClave.solicitar(correo, ipUnica());
        servicioRecuperarClave.solicitar(correo, ipUnica());

        assertThatThrownBy(() -> servicioRecuperarClave.solicitar(correo, ipUnica()))
                .isInstanceOf(ExcepcionDemasiadosIntentos.class)
                .hasMessage("Demasiados intentos. Prueba más tarde.");
    }

    @Test
    void undecimoPedidoDeLaMismaIpDa429() {
        String ip = ipUnica();
        for (int i = 0; i < 10; i++) {
            servicioRecuperarClave.solicitar(correoUnico(), ip);
        }

        assertThatThrownBy(() -> servicioRecuperarClave.solicitar(correoUnico(), ip))
                .isInstanceOf(ExcepcionDemasiadosIntentos.class)
                .hasMessage("Demasiados intentos. Prueba más tarde.");
    }

    @Test
    void enlaceNoQuedaEnLogNiAuditoria() {
        UsuarioDePrueba usuario = crearUsuarioActivo();

        Logger logger = (Logger) LoggerFactory.getLogger(ServicioRecuperarClave.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            servicioRecuperarClave.solicitar(usuario.correo(), ipUnica());
            String oobCode = proveedorFalso.ultimoOobCodeGenerado();

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(mensaje -> assertThat(mensaje).doesNotContain(oobCode));

            List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                    "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'USUARIO' "
                            + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'SOLICITAR_RECUPERAR_CLAVE'",
                    String.valueOf(usuario.idUsuario()));
            assertThat(eventos).hasSize(1);
            String detallesJson = (String) eventos.get(0).get("DETALLES_JSON");
            assertThat(detallesJson == null || !detallesJson.contains(oobCode)).isTrue();
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void correoInexistenteNoQuedaEnAuditoria() {
        String correoInexistente = correoUnico();

        servicioRecuperarClave.solicitar(correoInexistente, ipUnica());

        List<Map<String, Object>> eventosConElCorreo = jdbcTemplate.queryForList(
                "SELECT ID_ENTIDAD FROM EVENTO_AUDITORIA WHERE CODIGO_ACCION = 'SOLICITAR_RECUPERAR_CLAVE' "
                        + "AND (ID_ENTIDAD = ? OR DETALLES_JSON LIKE ?)",
                correoInexistente, "%" + correoInexistente + "%");
        assertThat(eventosConElCorreo).isEmpty();

        Long eventosComoDesconocido = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA WHERE CODIGO_ACCION = 'SOLICITAR_RECUPERAR_CLAVE' "
                        + "AND ID_ENTIDAD = ? AND ID_USUARIO_ACTOR IS NULL",
                Long.class, ServicioRecuperarClave.ID_ENTIDAD_SIN_CUENTA);
        assertThat(eventosComoDesconocido).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void sinCredencialDeFirebaseRespondeIgualYDejaLogDeAdvertenciaSinElCorreo() {
        UsuarioDePrueba usuario = crearUsuarioActivo();
        proveedorFalso.forzarErrorAlGenerarEnlace(new ExcepcionCuentasNoConfiguradas());

        Logger logger = (Logger) LoggerFactory.getLogger(ServicioRecuperarClave.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            servicioRecuperarClave.solicitar(usuario.correo(), ipUnica());

            verify(clienteServicios, never()).enviarCorreoRecuperarClave(any(), any(), any());
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(evento -> {
                assertThat(evento.getLevel()).isEqualTo(Level.WARN);
                assertThat(evento.getFormattedMessage()).doesNotContain(usuario.correo());
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void correoConMayusculasYEspaciosEncuentraLaCuentaActiva() {
        UsuarioDePrueba usuario = crearUsuarioActivo();
        String conEspaciosYMayusculas = "  " + usuario.correo().toUpperCase() + "  ";

        servicioRecuperarClave.solicitar(conEspaciosYMayusculas, ipUnica());

        verify(clienteServicios).enviarCorreoRecuperarClave(eq("Usuario fixtura"), eq(usuario.correo()), any());
    }

    @Test
    void enlaceDeFirebaseSinOobCodeNoEnviaCorreo() {
        UsuarioDePrueba usuario = crearUsuarioActivo();
        proveedorFalso.forzarEnlace("https://proveedor-falso.test/restablecer?otraCosa=1");

        servicioRecuperarClave.solicitar(usuario.correo(), ipUnica());

        verify(clienteServicios, never()).enviarCorreoRecuperarClave(any(), any(), any());
    }

    @Test
    void oobCodeConParametrosEnOtroOrden() {
        UsuarioDePrueba usuario = crearUsuarioActivo();
        proveedorFalso.forzarEnlace(
                "https://proveedor-falso.test/restablecer?mode=resetPassword&oobCode=a%2Bb&apiKey=x");

        servicioRecuperarClave.solicitar(usuario.correo(), ipUnica());

        String codigoReCodificado = URLEncoder.encode("a+b", StandardCharsets.UTF_8);
        verify(clienteServicios).enviarCorreoRecuperarClave(eq("Usuario fixtura"), eq(usuario.correo()),
                eq("http://localhost:4200/restablecer-clave#codigo=" + codigoReCodificado));
    }

    private UsuarioDePrueba crearUsuarioActivo() {
        String uid = FixturaGrupos.uidUnico();
        long idUsuario = fixtura.crearUsuario(uid, false);
        String correo = repositorioUsuario.findById(idUsuario).orElseThrow().getEmail();
        return new UsuarioDePrueba(idUsuario, correo);
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private String correoUnico() {
        return "recuperar-" + UUID.randomUUID() + "@mejorvejez.test";
    }

    private String ipUnica() {
        return "ip-" + UUID.randomUUID();
    }

    /** Usuario creado por la fixtura, con su id (para la auditoría) y su correo (para solicitar). */
    private record UsuarioDePrueba(long idUsuario, String correo) {
    }
}
