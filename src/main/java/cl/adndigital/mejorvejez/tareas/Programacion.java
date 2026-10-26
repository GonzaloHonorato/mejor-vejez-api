package cl.adndigital.mejorvejez.tareas;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Programación de dosis de un detalle de plan de medicamentos: los días de
 * la semana y las horas locales (America/Santiago) en que corresponde cada
 * dosis, dentro de su vigencia. La usa {@link PlanificadorOcurrencias} para
 * expandirla a instantes concretos.
 *
 * @param desde primer día vigente, inclusive.
 * @param hasta último día vigente, inclusive, o {@code null} si no tiene término.
 * @param dias  días de la semana en que corresponde la dosis.
 * @param horas horas locales en que corresponde la dosis cada día programado.
 */
public record Programacion(LocalDate desde, LocalDate hasta, Set<DayOfWeek> dias, List<LocalTime> horas) {
}
