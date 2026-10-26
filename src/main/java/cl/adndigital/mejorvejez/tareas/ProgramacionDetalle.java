package cl.adndigital.mejorvejez.tareas;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import cl.adndigital.mejorvejez.planificacion.CatalogoDiasSemana;
import cl.adndigital.mejorvejez.planificacion.DetallePlan;
import cl.adndigital.mejorvejez.planificacion.PlanMedicamentos;

/**
 * Arma la {@link Programacion} de un {@link DetallePlan} para {@link
 * PlanificadorOcurrencias}: sus días (traducidos con {@link
 * CatalogoDiasSemana}, que ignora los ids desconocidos o desactivados) y sus
 * horas, dentro de la intersección de la vigencia del detalle con la del
 * plan. La comparten el generador (fase A y B) y el recálculo (Tarea 4).
 * Debe llamarse dentro de la transacción que cargó el detalle (sus días y
 * horas son colecciones perezosas).
 */
public final class ProgramacionDetalle {

    private ProgramacionDetalle() {
    }

    /**
     * La programación del detalle, o vacío si no le queda ningún día
     * conocido o no tiene horas (el generador lo registra como error del
     * detalle; el recálculo lo trata como "ya no corresponde").
     */
    public static Optional<Programacion> de(DetallePlan d, PlanMedicamentos p, CatalogoDiasSemana c) {
        Set<DayOfWeek> dias = c.diasDeSemanaConocidos(d.diasSemana());
        List<LocalTime> horas = d.horas();
        if (dias.isEmpty() || horas.isEmpty()) {
            return Optional.empty();
        }
        LocalDate desde = mayor(d.getFechaInicio(), p.getVigenteDesde());
        LocalDate hasta = menorNoNulo(d.getFechaFin(), p.getVigenteHasta());
        return Optional.of(new Programacion(desde, hasta, dias, horas));
    }

    private static LocalDate mayor(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /** El menor de los dos, ignorando el que sea {@code null} ("sin término"); {@code null} si ambos lo son. */
    private static LocalDate menorNoNulo(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
