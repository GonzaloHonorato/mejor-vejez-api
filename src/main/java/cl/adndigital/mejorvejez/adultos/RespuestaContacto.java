package cl.adndigital.mejorvejez.adultos;

import java.time.OffsetDateTime;

/**
 * Datos de un contacto de emergencia para quien los consulta. Esta tarea lo
 * usa solo para el contacto principal dentro de {@link RespuestaAdultoMayor};
 * la Tarea 3 lo reutiliza para el CRUD de contactos.
 */
public record RespuestaContacto(long idContacto, long idAdultoMayor, String nombreContacto, String parentesco,
        String telefono, boolean esPrincipal, long idEstado, OffsetDateTime fechaCreacion,
        OffsetDateTime fechaActualizacion) {

    public static RespuestaContacto desde(ContactoEmergencia contacto) {
        return new RespuestaContacto(contacto.getIdContacto(), contacto.getIdAdultoMayor(),
                contacto.getNombreContacto(), contacto.getParentesco(), contacto.getTelefono(),
                contacto.isEsPrincipal(), contacto.getIdEstado(), contacto.getFechaCreacion(),
                contacto.getFechaActualizacion());
    }
}
