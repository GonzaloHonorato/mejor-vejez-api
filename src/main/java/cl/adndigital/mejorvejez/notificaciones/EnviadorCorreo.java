package cl.adndigital.mejorvejez.notificaciones;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Enviador de correo (regla 16 de la Fase 5): texto plano UTF-8, remitente y
 * host configurados por variable. {@link #disponible()} siempre es {@code
 * true} (a diferencia de {@link EnviadorWhatsApp}), para que la falta de
 * configuración se registre como un intento FALLIDO, no como un canal
 * inexistente (regla 8/16): así el coordinador ve en la bandeja que el
 * correo no llegó, en vez de que la alerta quede sin ningún rastro de envío.
 *
 * <p>{@code JavaMailSender} llega por {@code ObjectProvider} para no exigir
 * el bean en el arranque cuando el correo no está configurado todavía (host
 * vacío): la configuración se valida recién al enviar.
 */
@Component
public class EnviadorCorreo implements EnviadorNotificacion {

    /**
     * Dirección de correo dentro del mensaje de una excepción SMTP: se
     * reemplaza por {@link #DESTINO_OCULTO} antes de guardarlo como {@code
     * DETALLE_ERROR}, que ven el coordinador y el administrador (regla 14 de
     * la Fase 5: los destinos nunca salen; ronda de corrección 1 de la Tarea 4).
     */
    private static final Pattern PATRON_DIRECCION = Pattern.compile("[^\\s<>@]+@[^\\s<>@]+");
    private static final String DESTINO_OCULTO = "[destino]";

    private final ObjectProvider<JavaMailSender> remitenteCorreo;
    private final String host;
    private final String remitente;

    public EnviadorCorreo(ObjectProvider<JavaMailSender> remitenteCorreo, @Value("${spring.mail.host:}") String host,
            @Value("${mejorvejez.notificaciones.remitente:}") String remitente) {
        this.remitenteCorreo = remitenteCorreo;
        this.host = host;
        this.remitente = remitente;
    }

    @Override
    public String canal() {
        return CodigosNotificaciones.CANAL_CORREO;
    }

    @Override
    public boolean disponible() {
        return true;
    }

    @Override
    public ResultadoEnvio enviar(MensajeNotificacion mensaje) {
        if (host == null || host.isBlank() || remitente == null || remitente.isBlank()) {
            return ResultadoEnvio.fallido("El envío de correo no está configurado.");
        }
        try {
            JavaMailSender mailSender = remitenteCorreo.getObject();
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, StandardCharsets.UTF_8.name());
            helper.setFrom(remitente);
            helper.setTo(mensaje.destino());
            helper.setSubject(mensaje.asunto());
            helper.setText(mensaje.cuerpo(), false);
            mailSender.send(mime);
            return ResultadoEnvio.exitoso(mime.getMessageID());
        } catch (Exception e) {
            return ResultadoEnvio.fallido(
                    e.getClass().getSimpleName() + ": " + ocultarDirecciones(e.getMessage(), mensaje.destino()));
        }
    }

    /** {@code texto} sin el destino exacto ni ninguna otra dirección de correo (ambos pasan a {@code [destino]}). */
    private static String ocultarDirecciones(String texto, String destino) {
        if (texto == null) {
            return null;
        }
        String resultado = texto;
        if (destino != null && !destino.isBlank()) {
            resultado = resultado.replace(destino, DESTINO_OCULTO);
        }
        return PATRON_DIRECCION.matcher(resultado).replaceAll(Matcher.quoteReplacement(DESTINO_OCULTO));
    }
}
