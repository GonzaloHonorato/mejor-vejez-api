package cl.adndigital.mejorvejez.notificaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Prueba {@link EnviadorCorreo} como lógica pura (sin Spring, {@link
 * JavaMailSender} de Mockito y {@link MimeMessage} real sobre {@code
 * Session.getInstance(new Properties())}, regla 16 de la Fase 5).
 */
class EnviadorCorreoTest {

    private static final Session SESION = Session.getInstance(new Properties());

    private final MensajeNotificacion mensaje = new MensajeNotificacion("ana@correo.cl", "Ana Pérez",
            "Mejor Vejez · Tarea sin confirmar",
            "Hola Ana Pérez,\n\n«Tomar colchicina» de José, atención médica.\n\nRevisa la alerta en: "
                    + "http://localhost:4200/alertas/1?grupo=1\n\nMejor Vejez");

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> proveedor = mock(ObjectProvider.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    @Test
    void enviaTextoPlanoUtf8ConAsuntoYDestino() throws Exception {
        when(proveedor.getObject()).thenReturn(mailSender);
        MimeMessage mime = new MimeMessage(SESION);
        when(mailSender.createMimeMessage()).thenReturn(mime);

        EnviadorCorreo enviador = new EnviadorCorreo(proveedor, "localhost", "alertas@mejorvejez.local");
        ResultadoEnvio resultado = enviador.enviar(mensaje);

        assertThat(resultado.exito()).isTrue();
        assertThat(mime.getFrom()).hasSize(1);
        assertThat(mime.getFrom()[0].toString()).isEqualTo("alertas@mejorvejez.local");
        assertThat(mime.getAllRecipients()).hasSize(1);
        assertThat(mime.getAllRecipients()[0].toString()).isEqualTo("ana@correo.cl");
        assertThat(mime.getSubject()).isEqualTo(mensaje.asunto());
        assertThat((String) mime.getContent()).isEqualTo(mensaje.cuerpo());
        verify(mailSender).send(mime);
    }

    @Test
    void sinConfiguracionDevuelveFallo() {
        EnviadorCorreo enviador = new EnviadorCorreo(proveedor, "", "");

        ResultadoEnvio resultado = enviador.enviar(mensaje);

        assertThat(resultado.exito()).isFalse();
        assertThat(resultado.detalleError()).isEqualTo("El envío de correo no está configurado.");
        assertThat(resultado.referenciaProveedor()).isNull();
    }

    @Test
    void excepcionDeEnvioSeDevuelveComoFallo() throws Exception {
        when(proveedor.getObject()).thenReturn(mailSender);
        MimeMessage mime = new MimeMessage(SESION);
        when(mailSender.createMimeMessage()).thenReturn(mime);
        doThrow(new MailSendException("fallo SMTP")).when(mailSender).send(any(MimeMessage.class));

        EnviadorCorreo enviador = new EnviadorCorreo(proveedor, "localhost", "alertas@mejorvejez.local");
        ResultadoEnvio resultado = enviador.enviar(mensaje);

        assertThat(resultado.exito()).isFalse();
        assertThat(resultado.detalleError()).isEqualTo("MailSendException: fallo SMTP");
    }

    @Test
    void fallaConDireccionEnElMensajeNoExponeElDestino() throws Exception {
        when(proveedor.getObject()).thenReturn(mailSender);
        MimeMessage mime = new MimeMessage(SESION);
        when(mailSender.createMimeMessage()).thenReturn(mime);
        doThrow(new MailSendException("550 rechazado: persona@ejemplo.cl (destino <ana@correo.cl>)"))
                .when(mailSender).send(any(MimeMessage.class));

        EnviadorCorreo enviador = new EnviadorCorreo(proveedor, "localhost", "alertas@mejorvejez.local");
        ResultadoEnvio resultado = enviador.enviar(mensaje);

        assertThat(resultado.exito()).isFalse();
        assertThat(resultado.detalleError())
                .doesNotContain("@")
                .doesNotContain("persona")
                .doesNotContain("ana@correo.cl")
                .isEqualTo("MailSendException: 550 rechazado: [destino] (destino <[destino]>)");
    }
}
