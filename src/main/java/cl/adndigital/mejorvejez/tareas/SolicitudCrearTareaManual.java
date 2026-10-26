package cl.adndigital.mejorvejez.tareas;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Datos para crear una tarea manual del coordinador ({@code POST
 * .../grupos/{idGrupo}/tareas}, HU-05, TSA-41): compra/retiro de
 * medicamentos ({@code tipoTarea = COMPRA_RETIRO_MEDICAMENTOS}, con {@code
 * compra}) o cita/control médico ({@code tipoTarea =
 * CITAS_CONTROLES_MEDICOS}, con {@code cita}). Ninguna lleva {@code
 * CLAVE_OCURRENCIA}; esta tarea nunca toca {@code CANTIDAD_DISPONIBLE} de
 * ningún detalle del plan.
 *
 * <p>{@code tipoTarea} se valida contra los dos códigos admitidos en {@link
 * ServicioTareasManuales} (no con {@code @Pattern}, para responder con el
 * mensaje de negocio exacto en vez de un 400 genérico). {@code compra} y
 * {@code cita} son mutuamente excluyentes según {@code tipoTarea}; esa regla
 * también la exige el servicio.
 *
 * <p>El constructor compacto recorta {@code tipoTarea} y {@code titulo} y
 * deja {@code descripcion} en {@code null} si queda en blanco, antes de que
 * corra Bean Validation (mismo patrón que {@code
 * planificacion.SolicitudGuardarDetalle}).
 */
public record SolicitudCrearTareaManual(
        @NotBlank(message = "El tipo de tarea es obligatorio.")
        String tipoTarea,
        @NotNull(message = "El adulto mayor es obligatorio.")
        Long idAdultoMayor,
        @NotBlank(message = "El título es obligatorio.")
        @Size(max = 200, message = "El título no puede superar los 200 caracteres.")
        String titulo,
        @Size(max = 1000, message = "La descripción no puede superar los 1000 caracteres.")
        String descripcion,
        @NotNull(message = "La fecha y hora son obligatorias.")
        OffsetDateTime fechaHorario,
        Long idResponsable,
        @Valid
        List<SolicitudLineaCompra> compra,
        @Valid
        SolicitudCita cita) {

    public SolicitudCrearTareaManual {
        tipoTarea = tipoTarea == null ? null : tipoTarea.trim();
        titulo = titulo == null ? null : titulo.trim();
        descripcion = recortarOpcional(descripcion);
    }

    private static String recortarOpcional(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
