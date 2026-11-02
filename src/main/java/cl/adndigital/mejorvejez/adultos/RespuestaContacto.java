package cl.adndigital.mejorvejez.adultos;

import java.time.OffsetDateTime;

/**
 * Datos de un contacto de emergencia para quien los consulta (CRUD de
 * contactos y contacto principal dentro de {@link RespuestaAdultoMayor}).
 *
 * @param idParentesco opción del catálogo {@code PARENTESCO}, o {@code null}
 *                     en los contactos anteriores al catálogo.
 * @param parentesco   descripción del parentesco; en los contactos
 *                     anteriores al catálogo, el texto libre que tenían.
 */
public record RespuestaContacto(long idContacto, long idAdultoMayor, String nombreContacto, Long idParentesco,
        String parentesco, String telefono, boolean esPrincipal, long idEstado, OffsetDateTime fechaCreacion,
        OffsetDateTime fechaActualizacion) {

    public static RespuestaContacto desde(ContactoEmergencia contacto) {
        return new RespuestaContacto(contacto.getIdContacto(), contacto.getIdAdultoMayor(),
                contacto.getNombreContacto(), contacto.getIdParentesco(), contacto.getParentesco(),
                contacto.getTelefono(), contacto.isEsPrincipal(), contacto.getIdEstado(), contacto.getFechaCreacion(),
                contacto.getFechaActualizacion());
    }
}
