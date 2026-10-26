package cl.adndigital.mejorvejez.planificacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Prueba {@link CalculadoraTermino} como lógica pura (sin Spring ni BD).
 * {@code desde} es siempre lunes 2026-11-02, como pide el plan.
 */
class CalculadoraTerminoTest {

    private static final LocalDate DESDE = LocalDate.of(2026, 11, 2);
    private static final Set<DayOfWeek> TODOS_LOS_DIAS = Set.of(DayOfWeek.values());
    private static final Set<DayOfWeek> LUNES_MIERCOLES_VIERNES =
            Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);

    private final CalculadoraTermino calculadora = new CalculadoraTermino();

    @Test
    void todosLosDias() {
        LocalDate resultado = calculadora.estimar(BigDecimal.valueOf(10), BigDecimal.ONE, 2, TODOS_LOS_DIAS, DESDE,
                null);

        assertThat(resultado).isEqualTo(LocalDate.of(2026, 11, 7));
    }

    @Test
    void soloLunesMiercolesViernes() {
        LocalDate resultado = calculadora.estimar(BigDecimal.valueOf(6), BigDecimal.ONE, 2, LUNES_MIERCOLES_VIERNES,
                DESDE, null);

        assertThat(resultado).isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void disponibleCeroAgotaHoySiHoyEsDiaProgramado() {
        LocalDate resultado = calculadora.estimar(BigDecimal.ZERO, BigDecimal.ONE, 2, TODOS_LOS_DIAS, DESDE, null);

        assertThat(resultado).isEqualTo(DESDE);
    }

    @Test
    void noSeAgotaDentroDeLaVigencia() {
        // Mismo caso que todosLosDias (se agota el 2026-11-07), pero la
        // vigencia del detalle termina antes: no se agota dentro de ella.
        LocalDate resultado = calculadora.estimar(BigDecimal.valueOf(10), BigDecimal.ONE, 2, TODOS_LOS_DIAS, DESDE,
                LocalDate.of(2026, 11, 5));

        assertThat(resultado).isNull();
    }

    @Test
    void datosInsuficientesDevuelveNull() {
        assertThat(calculadora.estimar(null, BigDecimal.ONE, 2, TODOS_LOS_DIAS, DESDE, null)).isNull();
        assertThat(calculadora.estimar(BigDecimal.TEN, null, 2, TODOS_LOS_DIAS, DESDE, null)).isNull();
        assertThat(calculadora.estimar(BigDecimal.TEN, BigDecimal.ONE, 2, Set.of(), DESDE, null)).isNull();
        assertThat(calculadora.estimar(BigDecimal.TEN, BigDecimal.ONE, 0, TODOS_LOS_DIAS, DESDE, null)).isNull();
    }

    @Test
    void dosisDecimal() {
        LocalDate resultado = calculadora.estimar(BigDecimal.valueOf(5), new BigDecimal("0.5"), 3, TODOS_LOS_DIAS,
                DESDE, null);

        assertThat(resultado).isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void diaDeSemanaMapeaCodigosDelCatalogo() {
        assertThat(calculadora.diaDeSemana("LUNES")).isEqualTo(DayOfWeek.MONDAY);
        assertThat(calculadora.diaDeSemana("MARTES")).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(calculadora.diaDeSemana("MIERCOLES")).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(calculadora.diaDeSemana("JUEVES")).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(calculadora.diaDeSemana("VIERNES")).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(calculadora.diaDeSemana("SABADO")).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(calculadora.diaDeSemana("DOMINGO")).isEqualTo(DayOfWeek.SUNDAY);
    }
}
