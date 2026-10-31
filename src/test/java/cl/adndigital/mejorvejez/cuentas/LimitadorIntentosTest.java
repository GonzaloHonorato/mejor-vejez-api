package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * Prueba {@link LimitadorIntentos} con un reloj controlable, sin contexto
 * de Spring: solo la lógica de la ventana deslizante.
 */
class LimitadorIntentosTest {

    @Test
    void permiteHastaElMaximoPorCorreoYLuegoRechaza() {
        LimitadorIntentos limitador = new LimitadorIntentos(relojFijo());
        String correo = "ana@correo.cl";

        assertThat(limitador.permitirIntento(correo, "1.1.1.1")).isTrue();
        assertThat(limitador.permitirIntento(correo, "2.2.2.2")).isTrue();
        assertThat(limitador.permitirIntento(correo, "3.3.3.3")).isTrue();
        assertThat(limitador.permitirIntento(correo, "4.4.4.4")).isFalse();
    }

    @Test
    void permiteHastaElMaximoPorIpYLuegoRechaza() {
        LimitadorIntentos limitador = new LimitadorIntentos(relojFijo());
        String ip = "9.9.9.9";

        for (int i = 0; i < 10; i++) {
            assertThat(limitador.permitirIntento("correo" + i + "@correo.cl", ip)).isTrue();
        }
        assertThat(limitador.permitirIntento("otro@correo.cl", ip)).isFalse();
    }

    @Test
    void pasadaLaVentanaVuelveAPermitir() {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-01-01T00:00:00Z"));
        LimitadorIntentos limitador = new LimitadorIntentos(reloj);
        String correo = "ana@correo.cl";
        limitador.permitirIntento(correo, "1.1.1.1");
        limitador.permitirIntento(correo, "2.2.2.2");
        limitador.permitirIntento(correo, "3.3.3.3");
        assertThat(limitador.permitirIntento(correo, "4.4.4.4")).isFalse();

        reloj.avanzar(Duration.ofMinutes(61));

        assertThat(limitador.permitirIntento(correo, "5.5.5.5")).isTrue();
    }

    @Test
    void ipAlTopeNoRegistraCorreosNuevos() {
        LimitadorIntentos limitador = new LimitadorIntentos(relojFijo());
        String ip = "5.5.5.5";

        for (int i = 0; i < 50; i++) {
            limitador.permitirIntento("correo-" + i + "@t.cl", ip);
        }

        // Solo los primeros 10 pedidos (los que la IP todavía permitía) registraron un correo
        // nuevo; los 40 restantes, con la IP ya al tope, no tocaron el mapa de correos.
        assertThat(limitador.totalDeClavesRegistradas()).isEqualTo(11);
    }

    @Test
    void entradasViejasSeEliminan() {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-01-01T00:00:00Z"));
        LimitadorIntentos limitador = new LimitadorIntentos(reloj);
        limitador.permitirIntento("ana@correo.cl", "1.1.1.1");
        assertThat(limitador.totalDeClavesRegistradas()).isEqualTo(2);

        reloj.avanzar(Duration.ofMinutes(61));
        limitador.purgarEntradasVencidas();

        assertThat(limitador.totalDeClavesRegistradas()).isZero();
    }

    @Test
    void conElMapaLlenoUnaClaveNuevaPasaSinRegistrarse() {
        LimitadorIntentos limitador = new LimitadorIntentos(relojFijo());
        for (int i = 0; i < LimitadorIntentos.MAXIMO_CLAVES; i++) {
            assertThat(limitador.permitirIntento("correo-" + i + "@t.cl", "ip-" + i)).isTrue();
        }

        assertThat(limitador.permitirIntento("correo-nuevo@t.cl", "ip-nueva")).isTrue();
        // Ninguna de las dos claves nuevas (correo e IP) se registró: el total no cambió.
        assertThat(limitador.totalDeClavesRegistradas()).isEqualTo(2 * LimitadorIntentos.MAXIMO_CLAVES);
    }

    @Test
    void conElMapaLlenoSePurganLasVencidasAntesDeDejarPasarSinRegistrar() {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-01-01T00:00:00Z"));
        LimitadorIntentos limitador = new LimitadorIntentos(reloj);
        for (int i = 0; i < LimitadorIntentos.MAXIMO_CLAVES; i++) {
            limitador.permitirIntento("correo-" + i + "@t.cl", "ip-" + i);
        }

        reloj.avanzar(Duration.ofMinutes(61));

        assertThat(limitador.permitirIntento("correo-nuevo@t.cl", "ip-nueva")).isTrue();
        // La purga en el acto liberó todas las claves vencidas, y las dos nuevas sí quedaron registradas.
        assertThat(limitador.totalDeClavesRegistradas()).isEqualTo(2);
    }

    @Test
    void muchosPedidosDeUnaMismaIpNoHacenCrecerSuColaYSiguenRechazados() {
        LimitadorIntentos limitador = new LimitadorIntentos(relojFijo());
        String ip = "7.7.7.7";

        for (int i = 0; i < 10_000; i++) {
            limitador.permitirIntento("ana@correo.cl", ip);
        }

        assertThat(limitador.intentosRegistradosDeIp(ip)).isLessThanOrEqualTo(LimitadorIntentos.MAXIMO_POR_IP + 1);
        assertThat(limitador.permitirIntento("otro@correo.cl", ip)).isFalse();
    }

    @Test
    void muchosPedidosDeUnMismoCorreoNoHacenCrecerSuColaYSiguenRechazados() {
        LimitadorIntentos limitador = new LimitadorIntentos(relojFijo());
        String correo = "ana@correo.cl";

        for (int i = 0; i < 1_000; i++) {
            limitador.permitirIntento(correo, "ip-" + i);
        }

        assertThat(limitador.intentosRegistradosDeCorreo(correo))
                .isLessThanOrEqualTo(LimitadorIntentos.MAXIMO_POR_CORREO + 1);
        assertThat(limitador.permitirIntento(correo, "ip-otra")).isFalse();
    }

    @Test
    void laColaAcotadaConservaLaVentanaDeslizante() {
        RelojMutable reloj = new RelojMutable(Instant.parse("2026-01-01T00:00:00Z"));
        LimitadorIntentos limitador = new LimitadorIntentos(reloj);
        String ip = "8.8.8.8";
        for (int i = 0; i < 1_000; i++) {
            limitador.permitirIntento("correo-" + i + "@t.cl", ip);
            reloj.avanzar(Duration.ofSeconds(1));
        }

        // El último pedido fue hace un segundo: dentro de la ventana sigue bloqueada.
        reloj.avanzar(Duration.ofMinutes(30));
        assertThat(limitador.permitirIntento("otro@t.cl", ip)).isFalse();

        // Pasada la ventana desde los pedidos más recientes, vuelve a permitir.
        reloj.avanzar(Duration.ofMinutes(61));
        assertThat(limitador.permitirIntento("otro-mas@t.cl", ip)).isTrue();
    }

    private static Clock relojFijo() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
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
