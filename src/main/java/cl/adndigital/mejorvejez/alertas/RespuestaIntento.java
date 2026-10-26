package cl.adndigital.mejorvejez.alertas;

import java.time.OffsetDateTime;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Un intento de notificación de una {@link AlertaCuidado} (TSA-45), tal como
 * lo ve el detalle de la bandeja ({@code GET .../alertas/{idAlerta}}).
 * {@code detalleError} viaja {@code null} cuando quien consulta no es
 * coordinador ni administrador (regla 14 de la Fase 5): lo decide {@link
 * EnsambladorRespuestasAlerta#detallar}, no este record. Los destinos
 * (correo, teléfono) nunca salen en esta respuesta.
 */
public record RespuestaIntento(long idIntento, long idUsuarioDestinatario, String nombreDestinatario, String canal,
        int numeroIntento, long idEstadoEnvio, OffsetDateTime fechaIntento, String detalleError) {

    /** Ruling final de F4 (reutilizado en F5): los instantes salen siempre con el offset de America/Santiago. */
    public RespuestaIntento {
        fechaIntento = ZonaNegocio.enZona(fechaIntento);
    }
}
