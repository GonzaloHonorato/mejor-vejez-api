package cl.adndigital.mejorvejez.adultos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import cl.adndigital.mejorvejez.comun.TelefonoChileno;
import cl.adndigital.mejorvejez.comun.TelefonoChilenoValido;

/**
 * Datos para crear un contacto de emergencia ({@code POST
 * .../adultos/{idAdulto}/contactos}).
 *
 * <p>{@code idParentesco} es una opción del catálogo {@code PARENTESCO}
 * ({@code GET /api/catalogos/opciones?tipo=PARENTESCO}); {@link
 * ServicioContactos} verifica que pertenezca a ese catálogo.
 *
 * <p>El constructor compacto recorta el nombre y normaliza el teléfono
 * ({@link TelefonoChileno}) antes de que corra Bean Validation, igual que
 * {@link SolicitudGuardarAdultoMayor}.
 */
public record SolicitudCrearContacto(
        @NotBlank(message = "El nombre del contacto es obligatorio.")
        @Size(max = 160, message = "El nombre del contacto no puede superar los 160 caracteres.")
        String nombreContacto,
        @NotNull(message = "El parentesco es obligatorio.")
        Long idParentesco,
        @NotBlank(message = "El teléfono es obligatorio.")
        @TelefonoChilenoValido
        String telefono,
        Boolean esPrincipal) {

    public SolicitudCrearContacto {
        nombreContacto = nombreContacto == null ? null : nombreContacto.trim();
        telefono = TelefonoChileno.normalizarSiEsValido(telefono);
    }
}
