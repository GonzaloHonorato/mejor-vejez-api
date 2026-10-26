package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import cl.adndigital.mejorvejez.tareas.CodigosTareas;

/** Regla 11 de la Fase 5: estado final de una corrida de alertas. */
class EstadoCorridaTest {

    @Test
    void sinErroresEsExitosa() {
        assertThat(EstadoCorrida.codigo(0, 0, 0)).isEqualTo(CodigosTareas.ESTADO_EJECUCION_EXITOSA);
        assertThat(EstadoCorrida.codigo(3, 5, 0)).isEqualTo(CodigosTareas.ESTADO_EJECUCION_EXITOSA);
    }

    @Test
    void conErroresYAlgoCreadoOEnviadoEsParcial() {
        assertThat(EstadoCorrida.codigo(1, 0, 2)).isEqualTo(CodigosTareas.ESTADO_EJECUCION_PARCIAL);
        assertThat(EstadoCorrida.codigo(0, 1, 2)).isEqualTo(CodigosTareas.ESTADO_EJECUCION_PARCIAL);
    }

    @Test
    void conErroresYNadaCreadoNiEnviadoEsFallida() {
        assertThat(EstadoCorrida.codigo(0, 0, 1)).isEqualTo(CodigosTareas.ESTADO_EJECUCION_FALLIDA);
    }
}
