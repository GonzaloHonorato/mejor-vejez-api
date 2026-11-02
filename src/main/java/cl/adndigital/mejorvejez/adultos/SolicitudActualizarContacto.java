package cl.adndigital.mejorvejez.adultos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import cl.adndigital.mejorvejez.comun.TelefonoChileno;
import cl.adndigital.mejorvejez.comun.TelefonoChilenoValido;

/**
 * Datos para actualizar un contacto de emergencia ({@code PUT
 * .../contactos/{idContacto}}), con las mismas validaciones y normalización
 * que {@link SolicitudCrearContacto}. Un contacto antiguo con el parentesco
 * en texto libre pasa al catálogo al editarlo: {@code idParentesco} es
 * obligatorio.
 */
public record SolicitudActualizarContacto(
        @NotBlank(message = "El nombre del contacto es obligatorio.")
        @Size(max = 160, message = "El nombre del contacto no puede superar los 160 caracteres.")
        String nombreContacto,
        @NotNull(message = "El parentesco es obligatorio.")
        Long idParentesco,
        @NotBlank(message = "El teléfono es obligatorio.")
        @TelefonoChilenoValido
        String telefono) {

    public SolicitudActualizarContacto {
        nombreContacto = nombreContacto == null ? null : nombreContacto.trim();
        telefono = TelefonoChileno.normalizarSiEsValido(telefono);
    }
}
