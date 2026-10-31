package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.interno.ClienteServicios;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Prueba el despacho asíncrono de {@link ServicioRecuperarClave} con un
 * {@link Executor} de prueba hecho a mano (sin Spring, sin Testcontainers):
 * que la tarea lenta no corre hasta que el executor la ejecute, y que una
 * cola llena no interrumpe la respuesta.
 */
class ServicioRecuperarClaveAsincronoTest {

    private static final String APP_URL = "http://localhost:4200";
    private static final String CORREO = "ana@correo.cl";

    private final ProveedorIdentidad proveedorIdentidad = mock(ProveedorIdentidad.class);
    private final RepositorioUsuario repositorioUsuario = mock(RepositorioUsuario.class);
    private final ClienteServicios clienteServicios = mock(ClienteServicios.class);
    private final ServicioAuditoria servicioAuditoria = mock(ServicioAuditoria.class);
    private final ServicioCatalogos servicioCatalogos = mock(ServicioCatalogos.class);
    private final LimitadorIntentos limitadorIntentos = mock(LimitadorIntentos.class);

    @Test
    void laTareaNoCorreHastaQueElExecutorLaEjecute() {
        Usuario usuario = prepararUsuarioActivo();
        AtomicReference<Runnable> tareaCapturada = new AtomicReference<>();
        ServicioRecuperarClave servicio = construirServicio(tareaCapturada::set);

        servicio.solicitar(usuario.getEmail(), "1.1.1.1");

        // La tarea quedó encolada, no ejecutada: solicitar() no llamó a Firebase.
        verify(proveedorIdentidad, never()).enlaceRestablecerClave(any());
        assertThat(tareaCapturada.get()).isNotNull();

        tareaCapturada.get().run();

        verify(proveedorIdentidad).enlaceRestablecerClave(usuario.getEmail());
    }

    @Test
    void colaLlenaNoPropagaYDejaUnLogFijoSinElCorreo() {
        Usuario usuario = prepararUsuarioActivo();
        Executor ejecutorQueRechaza = tarea -> {
            throw new RejectedExecutionException("cola llena");
        };
        ServicioRecuperarClave servicio = construirServicio(ejecutorQueRechaza);

        Logger logger = (Logger) LoggerFactory.getLogger(ServicioRecuperarClave.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> servicio.solicitar(usuario.getEmail(), "1.1.1.1")).doesNotThrowAnyException();

            verify(proveedorIdentidad, never()).enlaceRestablecerClave(any());
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.get(0).getFormattedMessage()).doesNotContain(usuario.getEmail());
        } finally {
            logger.detachAppender(appender);
        }
    }

    private ServicioRecuperarClave construirServicio(Executor executor) {
        when(limitadorIntentos.permitirIntento(any(), any())).thenReturn(true);
        when(servicioCatalogos.idEstado(anyString(), anyString(), anyString())).thenReturn(1L);
        return new ServicioRecuperarClave(proveedorIdentidad, repositorioUsuario, clienteServicios,
                servicioAuditoria, servicioCatalogos, limitadorIntentos, new EjecutorRecuperarClave(executor),
                APP_URL);
    }

    private Usuario prepararUsuarioActivo() {
        Usuario usuario = new Usuario("uid-prueba", CORREO, "Ana", 1L, OffsetDateTime.now());
        when(repositorioUsuario.findByEmail(CORREO)).thenReturn(Optional.of(usuario));
        return usuario;
    }
}
