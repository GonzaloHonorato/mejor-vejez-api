package cl.adndigital.mejorvejez.adultos;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Datos para crear o actualizar un adulto mayor ({@code POST .../adultos},
 * {@code PUT .../adultos/{idAdulto}}).
 *
 * <p>El constructor compacto recorta espacios de todos los textos y convierte
 * los opcionales en blanco ({@code codigoInterno}, {@code notasEmergencia},
 * {@code observacionesGenerales}) a {@code null}, antes de que corra Bean
 * Validation: así {@code @NotBlank} y {@code @Size} validan el valor ya
 * normalizado.
 */
public record SolicitudGuardarAdultoMayor(
        @Size(max = 50, message = "El código interno no puede superar los 50 caracteres.")
        String codigoInterno,
        @NotBlank(message = "El nombre completo es obligatorio.")
        @Size(max = 180, message = "El nombre completo no puede superar los 180 caracteres.")
        String nombreCompleto,
        LocalDate fechaNacimiento,
        @Size(max = 1000, message = "Las notas de emergencia no pueden superar los 1000 caracteres.")
        String notasEmergencia,
        @Size(max = 2000, message = "Las observaciones generales no pueden superar los 2000 caracteres.")
        String observacionesGenerales) {

    public SolicitudGuardarAdultoMayor {
        nombreCompleto = nombreCompleto == null ? null : nombreCompleto.trim();
        codigoInterno = normalizarOpcional(codigoInterno);
        notasEmergencia = normalizarOpcional(notasEmergencia);
        observacionesGenerales = normalizarOpcional(observacionesGenerales);
    }

    private static String normalizarOpcional(String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
