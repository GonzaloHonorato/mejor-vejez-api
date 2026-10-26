package cl.adndigital.mejorvejez.comun;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Zona horaria de negocio de Mejor Vejez. Todo cálculo de fechas de la
 * aplicación debe usar esta zona (vía {@code ConfiguracionTiempo.reloj()}),
 * nunca la zona por defecto del servidor donde corre el proceso.
 */
public final class ZonaNegocio {

    public static final ZoneId ZONA = ZoneId.of("America/Santiago");

    /**
     * El mismo instante expresado con el offset que tiene {@link #ZONA} en
     * ese momento (−03:00 o −04:00). Lo usan las respuestas para que todos
     * los instantes salgan con el offset de Santiago, sin importar el offset
     * con que llegaron o se leyeron. {@code null} queda {@code null}.
     */
    public static OffsetDateTime enZona(OffsetDateTime instante) {
        return instante == null ? null : instante.atZoneSameInstant(ZONA).toOffsetDateTime();
    }

    private ZonaNegocio() {
    }
}
