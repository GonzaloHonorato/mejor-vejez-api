package cl.adndigital.mejorvejez.configuracion;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import cl.adndigital.mejorvejez.comun.ZonaNegocio;

/**
 * Expone el reloj de negocio como bean inyectable. Todo cálculo de fechas de
 * la aplicación debe recibir este {@link Clock} por inyección de
 * dependencias y usarlo (p. ej. {@code Instant.now(reloj)}); nunca llamar a
 * {@code now()} sin reloj, para que las pruebas puedan fijar la hora.
 */
@Configuration
public class ConfiguracionTiempo {

    @Bean
    public Clock reloj() {
        return Clock.system(ZonaNegocio.ZONA);
    }
}
