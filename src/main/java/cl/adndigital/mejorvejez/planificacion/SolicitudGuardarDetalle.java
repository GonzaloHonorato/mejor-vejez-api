package cl.adndigital.mejorvejez.planificacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import tools.jackson.databind.JsonNode;

/**
 * Datos para agregar ({@code POST .../planes/{idPlan}/detalles}) o
 * actualizar ({@code PUT .../detalles/{idDetalle}}) un medicamento del plan.
 *
 * <p>El constructor compacto recorta {@code concentracion} e {@code
 * instrucciones} y deja en {@code null} las que quedan en blanco, antes de
 * que corra Bean Validation (mismo patrón que {@link SolicitudCrearPlan}).
 *
 * <p><b>{@code horario}.</b> Es el objeto {@code HORARIO_JSON} literal del
 * contrato (claves {@code version}, {@code tipo}, {@code horas}, {@code
 * hora_inicio}, {@code hora_fin}, {@code frecuencia_horas}); lo valida
 * {@link ValidadorHorario} en el servicio, nunca Bean Validation. Al ser
 * {@link JsonNode}, Jackson liga cualquier valor JSON sin fallar: un texto
 * ({@code "08:00"}) llega como nodo de texto, y un {@code null} explícito
 * llega como nodo nulo (no como {@code null} de Java), así que ambos pasan
 * {@code @NotNull} y el validador responde 422 "El horario es obligatorio".
 * Solo la clave ausente deja el campo en {@code null} y responde 400.
 *
 * <p>{@code fechaEstimadaTermino} solo se usa con {@code
 * modoCalculoTermino = MANUAL}; en {@code AUTOMATICO} se ignora y se
 * calcula.
 */
public record SolicitudGuardarDetalle(
        @NotNull(message = "El medicamento es obligatorio.")
        Long idMedicamento,
        @NotNull(message = "La presentación es obligatoria.")
        Long idPresentacion,
        @Size(max = 80, message = "La concentración no puede superar los 80 caracteres.")
        String concentracion,
        @Size(max = 1000, message = "Las instrucciones no pueden superar los 1000 caracteres.")
        String instrucciones,
        @NotNull(message = "La dosis es obligatoria.")
        @Positive(message = "La dosis debe ser mayor que cero.")
        @Digits(integer = 9, fraction = 3, message = "La dosis admite hasta 9 enteros y 3 decimales.")
        BigDecimal dosisCantidad,
        @NotNull(message = "La unidad de la dosis es obligatoria.")
        Long idUnidadDosis,
        @NotNull(message = "Los días de la semana son obligatorios.")
        List<@NotNull(message = "Los días de la semana no pueden ser nulos.") Long> diasSemana,
        @NotNull(message = "El horario es obligatorio.")
        JsonNode horario,
        @NotNull(message = "La fecha de inicio es obligatoria.")
        LocalDate fechaInicio,
        LocalDate fechaFin,
        @PositiveOrZero(message = "La cantidad inicial no puede ser negativa.")
        @Digits(integer = 9, fraction = 3, message = "La cantidad inicial admite hasta 9 enteros y 3 decimales.")
        BigDecimal cantidadInicial,
        @PositiveOrZero(message = "La cantidad disponible no puede ser negativa.")
        @Digits(integer = 9, fraction = 3, message = "La cantidad disponible admite hasta 9 enteros y 3 decimales.")
        BigDecimal cantidadDisponible,
        @NotNull(message = "El modo de cálculo del término es obligatorio.")
        @Pattern(regexp = "AUTOMATICO|MANUAL", message = "El modo de cálculo del término debe ser AUTOMATICO o MANUAL.")
        String modoCalculoTermino,
        LocalDate fechaEstimadaTermino,
        Long idResponsablePredeterminado) {

    public SolicitudGuardarDetalle {
        concentracion = recortarOpcional(concentracion);
        instrucciones = recortarOpcional(instrucciones);
    }

    private static String recortarOpcional(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }
}
