package cl.adndigital.mejorvejez.adultos;

import java.time.LocalDate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos para crear un adulto mayor ({@code POST .../adultos}): los mismos
 * del perfil que {@link SolicitudGuardarAdultoMayor}, más el contacto
 * principal obligatorio, que se crea en la misma transacción (D1). La
 * fecha de nacimiento es obligatoria (no futura, lo verifica {@link
 * ServicioAdultosMayores}).
 *
 * <p>El constructor compacto normaliza los textos igual que {@link
 * SolicitudGuardarAdultoMayor}.
 */
public record SolicitudCrearAdultoMayor(
        @Size(max = 50, message = "El código interno no puede superar los 50 caracteres.")
        String codigoInterno,
        @NotBlank(message = "El nombre completo es obligatorio.")
        @Size(max = 180, message = "El nombre completo no puede superar los 180 caracteres.")
        String nombreCompleto,
        @NotNull(message = "La fecha de nacimiento es obligatoria.")
        LocalDate fechaNacimiento,
        @Size(max = 1000, message = "Las notas de emergencia no pueden superar los 1000 caracteres.")
        String notasEmergencia,
        @Size(max = 2000, message = "Las observaciones generales no pueden superar los 2000 caracteres.")
        String observacionesGenerales,
        @NotNull(message = "El contacto principal es obligatorio.")
        @Valid
        SolicitudContactoPrincipal contactoPrincipal) {

    public SolicitudCrearAdultoMayor {
        SolicitudGuardarAdultoMayor perfil = new SolicitudGuardarAdultoMayor(codigoInterno, nombreCompleto,
                fechaNacimiento, notasEmergencia, observacionesGenerales);
        codigoInterno = perfil.codigoInterno();
        nombreCompleto = perfil.nombreCompleto();
        notasEmergencia = perfil.notasEmergencia();
        observacionesGenerales = perfil.observacionesGenerales();
    }

    /** Los datos del perfil, ya normalizados, sin el contacto principal. */
    public SolicitudGuardarAdultoMayor perfil() {
        return new SolicitudGuardarAdultoMayor(codigoInterno, nombreCompleto, fechaNacimiento, notasEmergencia,
                observacionesGenerales);
    }
}
