package cl.adndigital.mejorvejez.sucesion;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Datos para completar la transferencia de la coordinación (HU-13, {@code
 * POST .../sucesion/{id}/completar}, regla 15 de la Fase 7): las
 * reasignaciones de las condiciones pendientes, que se aplican en orden. El
 * cuerpo completo es opcional y la lista puede venir vacía o {@code null}:
 * el constructor compacto la deja como lista vacía. Un elemento
 * {@code null}, o con {@code idTarea}/{@code idUsuario} nulos, da 400.
 */
public record SolicitudCompletarSucesion(
        List<@NotNull(message = "La reasignación no puede ser nula.") @Valid SolicitudReasignacion> reasignaciones) {

    public SolicitudCompletarSucesion {
        // Sin List.copyOf: rechazaría un elemento null con NPE antes de que Bean Validation responda 400.
        reasignaciones = reasignaciones == null ? List.of() : reasignaciones;
    }

    /** Sin reasignaciones: lo que vale un cuerpo ausente o {@code null}. */
    static SolicitudCompletarSucesion sinReasignaciones() {
        return new SolicitudCompletarSucesion(null);
    }
}
