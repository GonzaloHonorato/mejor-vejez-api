package cl.adndigital.mejorvejez.comun;

import java.time.ZoneId;

/**
 * Zona horaria de negocio de Mejor Vejez. Todo cálculo de fechas de la
 * aplicación debe usar esta zona (vía {@code ConfiguracionTiempo.reloj()}),
 * nunca la zona por defecto del servidor donde corre el proceso.
 */
public final class ZonaNegocio {

    public static final ZoneId ZONA = ZoneId.of("America/Santiago");

    private ZonaNegocio() {
    }
}
