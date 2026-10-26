package cl.adndigital.mejorvejez.planificacion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos para dar de alta un medicamento en el catálogo global ({@code POST
 * /api/grupos/{idGrupo}/medicamentos}).
 *
 * <p>El constructor compacto recorta espacios de los textos y convierte
 * {@code principioActivo} y {@code concentracion} en blanco a {@code null},
 * antes de que corra Bean Validation, igual que {@code
 * SolicitudCrearContacto}.
 */
public record SolicitudCrearMedicamento(
        @NotBlank(message = "El nombre del medicamento es obligatorio.")
        @Size(max = 180, message = "El nombre del medicamento no puede superar los 180 caracteres.")
        String nombre,
        @Size(max = 180, message = "El principio activo no puede superar los 180 caracteres.")
        String principioActivo,
        @NotNull(message = "La presentación es obligatoria.")
        Long idPresentacion,
        @Size(max = 80, message = "La concentración no puede superar los 80 caracteres.")
        String concentracion) {

    public SolicitudCrearMedicamento {
        nombre = nombre == null ? null : nombre.trim();
        principioActivo = normalizarOpcional(principioActivo);
        concentracion = normalizarOpcional(concentracion);
    }

    private static String normalizarOpcional(String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
