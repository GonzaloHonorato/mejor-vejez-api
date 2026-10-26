package cl.adndigital.mejorvejez.notificaciones;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Enviador de WhatsApp (spike TSA-46, regla 15 de la Fase 5): queda fuera
 * del MVP mientras torcaza no confirme el canal. {@code
 * mejorvejez.notificaciones.whatsapp-habilitado} es fijo en {@code false}
 * (no se lee de una variable de entorno); {@link #disponible()} devuelve ese
 * valor y {@link #enviar} lanza si alguien lo llama estando apagado.
 */
@Component
public class EnviadorWhatsApp implements EnviadorNotificacion {

    private final boolean habilitado;

    public EnviadorWhatsApp(@Value("${mejorvejez.notificaciones.whatsapp-habilitado:false}") boolean habilitado) {
        this.habilitado = habilitado;
    }

    @Override
    public String canal() {
        return CodigosNotificaciones.CANAL_WHATSAPP;
    }

    @Override
    public boolean disponible() {
        return habilitado;
    }

    @Override
    public ResultadoEnvio enviar(MensajeNotificacion mensaje) {
        if (!habilitado) {
            throw new UnsupportedOperationException("El envío de WhatsApp no está disponible (spike fuera del MVP).");
        }
        throw new UnsupportedOperationException("El envío de WhatsApp no está implementado.");
    }
}
