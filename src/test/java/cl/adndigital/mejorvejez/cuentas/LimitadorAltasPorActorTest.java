package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Prueba {@link LimitadorAltasPorActor} con un reloj controlable, sin
 * contexto de Spring: solo la lógica de la ventana deslizante por actor.
 */
class LimitadorAltasPorActorTest {

    @Test
    void permiteHastaElMaximoPorActorYLuegoRechaza() {
        LimitadorAltasPorActor limitador = new LimitadorAltasPorActor(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        for (int i = 0; i < LimitadorAltasPorActor.MAXIMO_ALTAS_POR_ACTOR; i++) {
            assertThat(limitador.permitirAlta(1L)).isTrue();
        }
        assertThat(limitador.permitirAlta(1L)).isFalse();
    }

    @Test
    void elLimiteEsPorActor() {
        LimitadorAltasPorActor limitador = new LimitadorAltasPorActor(
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < LimitadorAltasPorActor.MAXIMO_ALTAS_POR_ACTOR; i++) {
            limitador.permitirAlta(1L);
        }

        assertThat(limitador.permitirAlta(2L)).isTrue();
    }

    @Test
    void pasadaLaVentanaVuelveAPermitir() {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-01-01T00:00:00Z"));
        LimitadorAltasPorActor limitador = new LimitadorAltasPorActor(reloj);
        for (int i = 0; i < LimitadorAltasPorActor.MAXIMO_ALTAS_POR_ACTOR; i++) {
            limitador.permitirAlta(1L);
        }
        assertThat(limitador.permitirAlta(1L)).isFalse();

        reloj.avanzar(Duration.ofMinutes(LimitadorAltasPorActor.MINUTOS_VENTANA + 1));

        assertThat(limitador.permitirAlta(1L)).isTrue();
    }

    @Test
    void entradasViejasSeEliminan() {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-01-01T00:00:00Z"));
        LimitadorAltasPorActor limitador = new LimitadorAltasPorActor(reloj);
        limitador.permitirAlta(1L);
        assertThat(limitador.totalDeClavesRegistradas()).isEqualTo(1);

        reloj.avanzar(Duration.ofMinutes(LimitadorAltasPorActor.MINUTOS_VENTANA + 1));
        limitador.purgarEntradasVencidas();

        assertThat(limitador.totalDeClavesRegistradas()).isZero();
    }

    /** Reloj que se puede avanzar manualmente, para probar la ventana deslizante sin dormir el hilo. */
    private static final class RelojMutable extends Clock {
        private Instant instante;

        RelojMutable(Instant instanteInicial) {
            this.instante = instanteInicial;
        }

        void avanzar(Duration duracion) {
            instante = instante.plus(duracion);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return instante;
        }
    }
}
