package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cl.adndigital.mejorvejez.notificaciones.MensajeNotificacion;

/**
 * Prueba {@link RedactorCorreoAlertas} como lógica pura (sin Spring ni BD):
 * asunto con la etiqueta del tipo y cuerpo con el enlace a la alerta, regla
 * 16 de la Fase 5.
 */
class RedactorCorreoAlertasTest {

    private final RedactorCorreoAlertas redactor = new RedactorCorreoAlertas("http://localhost:4200");

    @Test
    void redactorUsaEtiquetaYEnlaceConGrupo() {
        AlertaCuidado alerta = AlertaCuidado.deTarea("ATR-500", CodigosAlertas.TIPO_ATRASO_TAREA, 500L,
                OffsetDateTime.parse("2026-10-26T09:00:00-03:00"), 10L, 20L,
                "«Tomar colchicina» de José Pérez, programada para el 26-10-2026 a las 09:00, sigue sin "
                        + "confirmación final.",
                1L, OffsetDateTime.parse("2026-10-26T09:10:00-03:00"));
        ReflectionTestUtils.setField(alerta, "idAlerta", 999L);

        MensajeNotificacion mensaje = redactor.redactar(alerta, "Ana Pérez", "ana@correo.cl");

        assertThat(mensaje.destino()).isEqualTo("ana@correo.cl");
        assertThat(mensaje.nombreDestinatario()).isEqualTo("Ana Pérez");
        assertThat(mensaje.asunto()).isEqualTo("Mejor Vejez · Tarea sin confirmar");
        assertThat(mensaje.cuerpo()).isEqualTo("Hola Ana Pérez,\n\n«Tomar colchicina» de José Pérez, programada "
                + "para el 26-10-2026 a las 09:00, sigue sin confirmación final.\n\nRevisa la alerta en: "
                + "http://localhost:4200/alertas/999?grupo=10\n\nMejor Vejez");
    }

    @Test
    void urlAppConBarraFinalNoDuplicaLaBarra() {
        RedactorCorreoAlertas conBarra = new RedactorCorreoAlertas("http://x/");
        AlertaCuidado alerta = AlertaCuidado.deTarea("ATR-501", CodigosAlertas.TIPO_ATRASO_TAREA, 501L,
                OffsetDateTime.parse("2026-10-26T09:00:00-03:00"), 10L, 20L, "Mensaje", 1L,
                OffsetDateTime.parse("2026-10-26T09:10:00-03:00"));
        ReflectionTestUtils.setField(alerta, "idAlerta", 7L);

        MensajeNotificacion mensaje = conBarra.redactar(alerta, "Ana", "ana@correo.cl");

        assertThat(mensaje.cuerpo()).contains("Revisa la alerta en: http://x/alertas/7?grupo=10")
                .doesNotContain("//alertas");
    }
}
