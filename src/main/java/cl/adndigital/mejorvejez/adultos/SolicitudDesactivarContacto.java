package cl.adndigital.mejorvejez.adultos;

/**
 * Cuerpo opcional de {@code POST .../contactos/{idContacto}/desactivar}. Si
 * el contacto a desactivar es el principal y hay otros contactos ACTIVOS del
 * mismo adulto mayor, {@code idNuevoPrincipal} designa cuál lo reemplaza; en
 * cualquier otro caso se ignora. Sin cuerpo en la petición, el controlador
 * recibe {@code null} en vez de esta solicitud.
 */
public record SolicitudDesactivarContacto(Long idNuevoPrincipal) {
}
