package cl.adndigital.mejorvejez.planificacion;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;

/**
 * Calcula la fecha estimada de término de un detalle de plan (HU-04): el
 * primer día programado, desde {@code desde} inclusive, en que la cantidad
 * restante ya no alcanza para cubrir todas las dosis de ese día.
 *
 * <p><b>Unidad.</b> {@code cantidadDisponible} se expresa en la misma unidad
 * que {@code dosisCantidad} (p. ej. ambas en comprimidos, o ambas en mg):
 * esa es la equivalencia explícita que exige la HU, y esta clase no hace
 * ninguna conversión entre unidades.
 */
@Component
public class CalculadoraTermino {

    /** Más allá de esta cantidad de días calendario desde {@code desde}, se considera que no se agota. */
    private static final int LIMITE_DIAS = 3650;

    /**
     * La fecha estimada de término, o {@code null} si no hay datos
     * suficientes para calcularla, o si el agotamiento cae después de
     * {@code fechaFin} (cuando no es nula) o más allá de {@link #LIMITE_DIAS}
     * días.
     *
     * @param cantidadDisponible cantidad restante al día {@code desde}, en la unidad de la dosis; {@code null} → sin dato.
     * @param dosisCantidad      cantidad de cada dosis, en la misma unidad que {@code cantidadDisponible}.
     * @param dosisPorDia        dosis por cada día programado.
     * @param dias               días de la semana programados; vacío → sin dato.
     * @param desde              primer día a evaluar, inclusive.
     * @param fechaFin           fin de la vigencia del detalle, o {@code null} si no tiene.
     */
    public LocalDate estimar(BigDecimal cantidadDisponible, BigDecimal dosisCantidad, int dosisPorDia,
            Set<DayOfWeek> dias, LocalDate desde, LocalDate fechaFin) {
        if (cantidadDisponible == null || dosisCantidad == null || dias == null || dias.isEmpty()
                || dosisPorDia == 0) {
            return null;
        }

        BigDecimal necesarioPorDia = dosisCantidad.multiply(BigDecimal.valueOf(dosisPorDia));
        BigDecimal restante = cantidadDisponible;
        LocalDate fecha = desde;

        for (int diasRevisados = 0; diasRevisados < LIMITE_DIAS; diasRevisados++) {
            if (fechaFin != null && fecha.isAfter(fechaFin)) {
                // El agotamiento, si ocurriera, caería después de la vigencia:
                // cortar aquí evita seguir caminando hasta LIMITE_DIAS.
                return null;
            }
            if (dias.contains(fecha.getDayOfWeek())) {
                if (restante.compareTo(necesarioPorDia) < 0) {
                    return fecha;
                }
                restante = restante.subtract(necesarioPorDia);
            }
            fecha = fecha.plusDays(1);
        }
        return null;
    }

    /** Traduce el código de {@code DIA_SEMANA} (catálogo) a {@link DayOfWeek}. {@code LUNES} → {@code MONDAY}, etc. */
    public DayOfWeek diaDeSemana(String codigoDiaSemana) {
        return switch (codigoDiaSemana) {
            case "LUNES" -> DayOfWeek.MONDAY;
            case "MARTES" -> DayOfWeek.TUESDAY;
            case "MIERCOLES" -> DayOfWeek.WEDNESDAY;
            case "JUEVES" -> DayOfWeek.THURSDAY;
            case "VIERNES" -> DayOfWeek.FRIDAY;
            case "SABADO" -> DayOfWeek.SATURDAY;
            case "DOMINGO" -> DayOfWeek.SUNDAY;
            default -> throw new ExcepcionReglaNegocio("Código de día de semana no válido: " + codigoDiaSemana);
        };
    }
}
