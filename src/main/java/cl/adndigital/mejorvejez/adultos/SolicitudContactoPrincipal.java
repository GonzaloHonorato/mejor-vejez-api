package cl.adndigital.mejorvejez.adultos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import cl.adndigital.mejorvejez.comun.TelefonoChileno;
import cl.adndigital.mejorvejez.comun.TelefonoChilenoValido;

/**
 * Contacto principal que se crea junto con el adulto mayor ({@code
 * contactoPrincipal} de {@link SolicitudCrearAdultoMayor}), con las mismas
 * reglas que {@link SolicitudCrearContacto}: nombre, parentesco del catálogo
 * {@code PARENTESCO} y teléfono chileno, los tres obligatorios.
 */
public record SolicitudContactoPrincipal(
        @NotBlank(message = "El nombre del contacto es obligatorio.")
        @Size(max = 160, message = "El nombre del contacto no puede superar los 160 caracteres.")
        String nombre,
        @NotNull(message = "El parentesco es obligatorio.")
        Long idParentesco,
        @NotBlank(message = "El teléfono es obligatorio.")
        @TelefonoChilenoValido
        String telefono) {

    public SolicitudContactoPrincipal {
        nombre = nombre == null ? null : nombre.trim();
        telefono = TelefonoChileno.normalizarSiEsValido(telefono);
    }
}
