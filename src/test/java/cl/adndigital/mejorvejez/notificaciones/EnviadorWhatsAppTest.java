package cl.adndigital.mejorvejez.notificaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Prueba {@link EnviadorWhatsApp} como lógica pura (spike TSA-46, regla 15
 * de la Fase 5): apagado y fijo, nunca disponible en este MVP.
 */
class EnviadorWhatsAppTest {

    @Test
    void whatsappNoDisponibleYEnviarLanza() {
        EnviadorWhatsApp enviador = new EnviadorWhatsApp(false);

        assertThat(enviador.canal()).isEqualTo(CodigosNotificaciones.CANAL_WHATSAPP);
        assertThat(enviador.disponible()).isFalse();
        assertThatThrownBy(() -> enviador.enviar(new MensajeNotificacion("+56912345678", "Ana", "asunto", "cuerpo")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
