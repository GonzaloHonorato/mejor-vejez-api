package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Prueba {@link PlanificadorOcurrencias} como lógica pura (sin Spring ni
 * BD), incluido el horario de verano de America/Santiago (regla 2 de la
 * Fase 4) y la clave de ocurrencia (regla 3).
 */
class PlanificadorOcurrenciasTest {

    private final PlanificadorOcurrencias planificador = new PlanificadorOcurrencias();

    @Test
    void generaHorasDeLosDiasProgramadosDentroDeLaVentana() {
        // 2026-11-02 es lunes (mismo supuesto que CalculadoraTerminoTest).
        Programacion p = new Programacion(LocalDate.of(2026, 11, 2), null,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                List.of(LocalTime.of(8, 0), LocalTime.of(20, 0)));

        List<OffsetDateTime> resultado = planificador.horarios(p,
                OffsetDateTime.parse("2026-11-02T00:00:00-03:00"),
                OffsetDateTime.parse("2026-11-09T00:00:00-03:00"));

        assertThat(resultado).containsExactly(
                OffsetDateTime.parse("2026-11-02T08:00:00-03:00"),
                OffsetDateTime.parse("2026-11-02T20:00:00-03:00"),
                OffsetDateTime.parse("2026-11-04T08:00:00-03:00"),
                OffsetDateTime.parse("2026-11-04T20:00:00-03:00"));
    }

    @Test
    void respetaVigenciaDesdeYHasta() {
        // Lunes 11-02 queda fuera por "desde"; viernes 11-06 queda fuera por "hasta".
        Programacion p = new Programacion(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4),
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), List.of(LocalTime.of(8, 0)));

        List<OffsetDateTime> resultado = planificador.horarios(p,
                OffsetDateTime.parse("2026-11-02T00:00:00-03:00"),
                OffsetDateTime.parse("2026-11-09T00:00:00-03:00"));

        assertThat(resultado).containsExactly(OffsetDateTime.parse("2026-11-04T08:00:00-03:00"));
    }

    @Test
    void finEsExclusivoEInicioInclusivo() {
        Programacion p = new Programacion(LocalDate.of(2026, 11, 2), null, Set.of(DayOfWeek.MONDAY),
                List.of(LocalTime.of(8, 0)));
        OffsetDateTime instante = OffsetDateTime.parse("2026-11-02T08:00:00-03:00");

        // inicio inclusive: la ventana que empieza justo en el instante lo incluye.
        assertThat(planificador.horarios(p, instante, instante.plusMinutes(1))).containsExactly(instante);

        // fin exclusivo: la ventana que termina justo en el instante no lo incluye.
        assertThat(planificador.horarios(p, instante.minusMinutes(1), instante)).isEmpty();
    }

    @Test
    void sinDiasOSinHorasNoGeneraNada() {
        OffsetDateTime inicio = OffsetDateTime.parse("2026-11-02T00:00:00-03:00");
        OffsetDateTime fin = OffsetDateTime.parse("2026-11-09T00:00:00-03:00");

        Programacion sinDias = new Programacion(LocalDate.of(2026, 11, 2), null, Set.of(), List.of(LocalTime.of(8, 0)));
        Programacion sinHoras = new Programacion(LocalDate.of(2026, 11, 2), null, Set.of(DayOfWeek.MONDAY), List.of());

        assertThat(planificador.horarios(sinDias, inicio, fin)).isEmpty();
        assertThat(planificador.horarios(sinHoras, inicio, fin)).isEmpty();
    }

    @Test
    void retrocesoDeOtonoNoCorreLaHora() {
        // Guarda contra un tzdata distinto: 2026-04-04 23:00-23:59 debe
        // seguir siendo la hora repetida (Overlap -03:00 -> -04:00).
        ZoneOffsetTransition transicion =
                ZonaNegocio.ZONA.getRules().getTransition(LocalDateTime.of(2026, 4, 4, 23, 30));
        assertThat(transicion).isNotNull();
        assertThat(transicion.isOverlap()).isTrue();
        assertThat(transicion.getOffsetBefore()).isEqualTo(ZoneOffset.of("-03:00"));
        assertThat(transicion.getOffsetAfter()).isEqualTo(ZoneOffset.of("-04:00"));

        Programacion p = new Programacion(LocalDate.of(2026, 4, 4), null, Set.of(DayOfWeek.values()),
                List.of(LocalTime.of(8, 0)));

        List<OffsetDateTime> resultado = planificador.horarios(p,
                OffsetDateTime.parse("2026-04-04T00:00:00-03:00"),
                OffsetDateTime.parse("2026-04-06T00:00:00-04:00"));

        assertThat(resultado).containsExactly(
                OffsetDateTime.parse("2026-04-04T08:00:00-03:00"),
                OffsetDateTime.parse("2026-04-05T08:00:00-04:00"));
    }

    @Test
    void saltoDePrimaveraAvanzaLaHoraInexistente() {
        // Guarda contra un tzdata distinto: 2026-09-06 00:00-00:59 no debe existir (Gap -04:00 -> -03:00).
        ZoneOffsetTransition transicion =
                ZonaNegocio.ZONA.getRules().getTransition(LocalDateTime.of(2026, 9, 6, 0, 30));
        assertThat(transicion).isNotNull();
        assertThat(transicion.isGap()).isTrue();
        assertThat(transicion.getOffsetBefore()).isEqualTo(ZoneOffset.of("-04:00"));
        assertThat(transicion.getOffsetAfter()).isEqualTo(ZoneOffset.of("-03:00"));

        Programacion p = new Programacion(LocalDate.of(2026, 9, 6), null, Set.of(DayOfWeek.values()),
                List.of(LocalTime.of(0, 30)));

        List<OffsetDateTime> resultado = planificador.horarios(p,
                OffsetDateTime.parse("2026-09-06T00:00:00-04:00"),
                OffsetDateTime.parse("2026-09-07T00:00:00-03:00"));

        assertThat(resultado).containsExactly(OffsetDateTime.parse("2026-09-06T01:30:00-03:00"));
    }

    @Test
    void horaRepetidaEnOtonoGeneraUnaSola() {
        Programacion p = new Programacion(LocalDate.of(2026, 4, 4), null, Set.of(DayOfWeek.values()),
                List.of(LocalTime.of(23, 30)));

        // Ventana acotada al día de la transición (2026-04-04); su noche
        // repetida va de 23:00-03:00 a 00:00-04:00 del día siguiente.
        List<OffsetDateTime> resultado = planificador.horarios(p,
                OffsetDateTime.parse("2026-04-04T00:00:00-03:00"),
                OffsetDateTime.parse("2026-04-05T00:00:00-04:00"));

        assertThat(resultado).containsExactly(OffsetDateTime.parse("2026-04-04T23:30:00-03:00"));
    }

    @Test
    void horasQueColapsanEnElSaltoSeDeduplican() {
        Programacion p = new Programacion(LocalDate.of(2026, 9, 6), null, Set.of(DayOfWeek.values()),
                List.of(LocalTime.of(0, 30), LocalTime.of(1, 30)));

        List<OffsetDateTime> resultado = planificador.horarios(p,
                OffsetDateTime.parse("2026-09-06T00:00:00-04:00"),
                OffsetDateTime.parse("2026-09-07T00:00:00-03:00"));

        assertThat(resultado).containsExactly(OffsetDateTime.parse("2026-09-06T01:30:00-03:00"));
    }

    @Test
    void esperadoVerdaderoYFalso() {
        Programacion p = new Programacion(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4),
                Set.of(DayOfWeek.MONDAY), List.of(LocalTime.of(8, 0)));

        OffsetDateTime horarioEsperado = OffsetDateTime.parse("2026-11-02T08:00:00-03:00");
        assertThat(planificador.esperado(p, horarioEsperado)).isTrue();

        // mismo instante representado con otro offset -> sigue siendo true (comparación por instante)
        OffsetDateTime mismoInstanteOtroOffset = horarioEsperado.withOffsetSameInstant(ZoneOffset.of("-04:00"));
        assertThat(planificador.esperado(p, mismoInstanteOtroOffset)).isTrue();

        // otra hora el mismo día
        assertThat(planificador.esperado(p, OffsetDateTime.parse("2026-11-02T20:00:00-03:00"))).isFalse();

        // otro día (no programado)
        assertThat(planificador.esperado(p, OffsetDateTime.parse("2026-11-03T08:00:00-03:00"))).isFalse();

        // fuera de vigencia (lunes 11-09, después de "hasta" 11-04)
        assertThat(planificador.esperado(p, OffsetDateTime.parse("2026-11-09T08:00:00-03:00"))).isFalse();
    }

    @Test
    void claveTieneElFormatoAcordado() {
        String clave = PlanificadorOcurrencias.clave(15, OffsetDateTime.parse("2026-11-02T08:00:00-03:00"), 1);

        assertThat(clave).isEqualTo("DET-15-202611021100-1");
    }
}
