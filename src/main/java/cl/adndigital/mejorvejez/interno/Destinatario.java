package cl.adndigital.mejorvejez.interno;

/**
 * Un destinatario del reporte semanal (Fase 9, TSA-61, contrato "Core → lo
 * consume Reportes"): parte de {@link GrupoReporte#destinatarios} y de
 * {@link RespuestaReservaEnvio#pendientes}. {@code correo} es siempre el de
 * la cuenta ({@code USUARIO.EMAIL}, decisión de Gonzalo).
 */
public record Destinatario(Long idUsuario, String nombre, String correo) {
}
