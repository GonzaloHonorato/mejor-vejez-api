package cl.adndigital.mejorvejez.relevos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos para registrar un relevo del turno de cuidado (HU-11, {@code POST
 * .../relevos}). Las reglas de quién puede registrar para quién, y la
 * pertenencia/estado del adulto y de las personas, las exige {@link
 * ServicioRelevos} (403/404/422): esta clase solo valida la forma.
 *
 * <p>El constructor compacto recorta {@code notas} y la deja en {@code
 * null} si queda en blanco, antes de que corra Bean Validation (mismo
 * patrón que {@code tareas.SolicitudAsignarTarea}).
 */
public record SolicitudRegistrarRelevo(
        @NotNull(message = "El adulto mayor es obligatorio.")
        Long idAdulto,
        @NotNull(message = "Quien entrega el turno es obligatorio.")
        Long idUsuarioSaliente,
        @NotNull(message = "Quien recibe el turno es obligatorio.")
        Long idUsuarioEntrante,
        @Size(max = 2000, message = "Las notas no pueden superar los 2000 caracteres.")
        String notas) {

    public SolicitudRegistrarRelevo {
        notas = recortarOpcional(notas);
    }

    private static String recortarOpcional(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
