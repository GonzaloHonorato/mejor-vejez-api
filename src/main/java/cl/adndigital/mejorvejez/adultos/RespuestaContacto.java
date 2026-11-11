package cl.adndigital.mejorvejez.adultos;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Datos de un contacto de emergencia para quien los consulta (CRUD de
 * contactos y contacto principal dentro de {@link RespuestaAdultoMayor}).
 *
 * @param idParentesco opción del catálogo {@code PARENTESCO}, o {@code null}
 *                     si el contacto no tiene parentesco.
 * @param parentesco   descripción de {@code idParentesco} en el catálogo, o
 *                     {@code null} si el contacto no tiene parentesco.
 */
public record RespuestaContacto(long idContacto, long idAdultoMayor, String nombreContacto, Long idParentesco,
        String parentesco, String telefono, boolean esPrincipal, long idEstado, OffsetDateTime fechaCreacion,
        OffsetDateTime fechaActualizacion) {

    /**
     * @param parentesco descripción de {@code contacto.getIdParentesco()} ya
     *                   resuelta desde el catálogo; se ignora si el contacto
     *                   no tiene parentesco.
     */
    public static RespuestaContacto desde(ContactoEmergencia contacto, String parentesco) {
        return new RespuestaContacto(contacto.getIdContacto(), contacto.getIdAdultoMayor(),
                contacto.getNombreContacto(), contacto.getIdParentesco(),
                contacto.getIdParentesco() == null ? null : parentesco,
                contacto.getTelefono(), contacto.isEsPrincipal(), contacto.getIdEstado(), contacto.getFechaCreacion(),
                contacto.getFechaActualizacion());
    }

    /** Igual que {@link #desde(ContactoEmergencia, String)}, buscando la descripción en {@code descripciones}. */
    public static RespuestaContacto desde(ContactoEmergencia contacto, Map<Long, String> descripciones) {
        Long idParentesco = contacto.getIdParentesco();
        return desde(contacto, idParentesco == null ? null : descripciones.get(idParentesco));
    }
}
