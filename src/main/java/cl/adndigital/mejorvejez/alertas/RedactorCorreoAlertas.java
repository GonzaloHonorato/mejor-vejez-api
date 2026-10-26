package cl.adndigital.mejorvejez.alertas;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.notificaciones.MensajeNotificacion;

/**
 * Redacta el correo de una alerta (regla 16 de la Fase 5): asunto con la
 * etiqueta del tipo y cuerpo con el {@code MENSAJE_ALERTA} ya armado (y
 * truncado) por el detector, más el enlace a la alerta en el front. El
 * enlace usa {@code mejorvejez.notificaciones.url-app}.
 */
@Component
public class RedactorCorreoAlertas {

    private static final Map<String, String> ETIQUETAS_TIPO = Map.of(
            CodigosAlertas.TIPO_ATRASO_TAREA, "Tarea sin confirmar",
            CodigosAlertas.TIPO_CITA_PROXIMA, "Cita médica próxima",
            CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO, "Reposición de medicamento");

    private final String urlApp;

    public RedactorCorreoAlertas(@Value("${mejorvejez.notificaciones.url-app}") String urlApp) {
        // Sin barra final: `APP_URL=https://…cl/` no debe generar `…cl//alertas/…`.
        this.urlApp = urlApp.replaceAll("/+$", "");
    }

    /** Mensaje de correo de {@code alerta} para {@code nombreDestinatario} en {@code destino}. */
    public MensajeNotificacion redactar(AlertaCuidado alerta, String nombreDestinatario, String destino) {
        String etiqueta = ETIQUETAS_TIPO.get(alerta.getTipoAlerta());
        String asunto = "Mejor Vejez · " + etiqueta;
        String cuerpo = "Hola " + nombreDestinatario + ",\n\n" + alerta.getMensajeAlerta()
                + "\n\nRevisa la alerta en: " + urlApp + "/alertas/" + alerta.getIdAlerta() + "?grupo="
                + alerta.getIdGrupo() + "\n\nMejor Vejez";
        return new MensajeNotificacion(destino, nombreDestinatario, asunto, cuerpo);
    }
}
