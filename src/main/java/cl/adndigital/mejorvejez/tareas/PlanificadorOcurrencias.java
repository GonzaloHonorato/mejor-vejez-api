package cl.adndigital.mejorvejez.tareas;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Convierte la {@link Programacion} de un detalle de plan de medicamentos
 * (días, horas y vigencia) en los instantes concretos en que corresponde
 * cada dosis, respetando el horario de verano de America/Santiago (regla 2
 * de la Fase 4: salto de primavera avanza la hora inexistente lo que dura el
 * salto, retroceso de otoño usa el primer offset), y calcula la clave de
 * ocurrencia (regla 3). Lógica pura: sin acceso a base de datos ni a otros
 * servicios. La usan el generador (Tarea 3) y el recálculo (Tarea 4).
 */
@Component
public class PlanificadorOcurrencias {

    private static final DateTimeFormatter FORMATO_CLAVE_UTC = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    /**
     * Los instantes {@code inicio <= h < fin} que corresponden a {@code p},
     * ordenados y sin repetir el mismo instante: si dos horas programadas
     * colapsan en el mismo instante por un salto de horario de verano, se
     * devuelve uno solo. Recorre las fechas locales (America/Santiago) entre
     * la de {@code inicio} y la de {@code fin}; incluye una fecha si está en
     * {@code [desde, hasta]} (o {@code hasta} es {@code null}) y su día de la
     * semana está en {@code dias}. Cada hora programada de una fecha incluida
     * se convierte con {@code ZonedDateTime.of(fecha, hora,
     * ZonaNegocio.ZONA)} (regla 2).
     *
     * @param p      programación a expandir; sin días u horas no genera nada.
     * @param inicio instante de inicio de la ventana, inclusive.
     * @param fin    instante de fin de la ventana, exclusivo.
     */
    public List<OffsetDateTime> horarios(Programacion p, OffsetDateTime inicio, OffsetDateTime fin) {
        if (p.dias() == null || p.dias().isEmpty() || p.horas() == null || p.horas().isEmpty()) {
            return List.of();
        }

        LocalDate fechaInicio = inicio.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();
        LocalDate fechaFin = fin.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();

        // TreeSet con comparador por instante: ordena y deduplica a la vez
        // (dos representaciones del mismo instante, por ejemplo por un
        // salto de horario de verano, cuentan como una sola).
        TreeSet<OffsetDateTime> instantes = new TreeSet<>(Comparator.comparing(OffsetDateTime::toInstant));

        for (LocalDate fecha = fechaInicio; !fecha.isAfter(fechaFin); fecha = fecha.plusDays(1)) {
            if (!dentroDeVigencia(p, fecha) || !p.dias().contains(fecha.getDayOfWeek())) {
                continue;
            }
            for (LocalTime hora : p.horas()) {
                OffsetDateTime instante = ZonedDateTime.of(fecha, hora, ZonaNegocio.ZONA).toOffsetDateTime();
                if (!instante.isBefore(inicio) && instante.isBefore(fin)) {
                    instantes.add(instante);
                }
            }
        }
        return new ArrayList<>(instantes);
    }

    /**
     * {@code true} si {@code horario} (comparado por instante, no por
     * offset) es uno de los instantes que {@code p} genera para su día
     * local: equivalente a que esté en {@code horarios(p, inicio del día
     * local de horario, inicio del día siguiente)}.
     */
    public boolean esperado(Programacion p, OffsetDateTime horario) {
        LocalDate fecha = horario.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();
        OffsetDateTime inicioDia = ZonedDateTime.of(fecha, LocalTime.MIDNIGHT, ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime inicioDiaSiguiente =
                ZonedDateTime.of(fecha.plusDays(1), LocalTime.MIDNIGHT, ZonaNegocio.ZONA).toOffsetDateTime();

        return horarios(p, inicioDia, inicioDiaSiguiente).stream().anyMatch(horario::isEqual);
    }

    /**
     * Clave de ocurrencia (regla 3 de la Fase 4): {@code "DET-" + idDetalle +
     * "-" + horario en UTC con formato yyyyMMddHHmm + "-" + revision}.
     * Ejemplo: detalle 15 a las 2026-11-02T08:00-03:00, revisión 1 →
     * {@code "DET-15-202611021100-1"}. La misma clave va en {@code
     * OCURRENCIA_PLANIFICADA.CLAVE_OCURRENCIA} y en {@code
     * TAREA_CUIDADO.CLAVE_OCURRENCIA}.
     */
    public static String clave(long idDetalle, OffsetDateTime horario, int revision) {
        String horarioUtc = horario.withOffsetSameInstant(ZoneOffset.UTC).format(FORMATO_CLAVE_UTC);
        return "DET-" + idDetalle + "-" + horarioUtc + "-" + revision;
    }

    private boolean dentroDeVigencia(Programacion p, LocalDate fecha) {
        if (fecha.isBefore(p.desde())) {
            return false;
        }
        return p.hasta() == null || !fecha.isAfter(p.hasta());
    }
}
