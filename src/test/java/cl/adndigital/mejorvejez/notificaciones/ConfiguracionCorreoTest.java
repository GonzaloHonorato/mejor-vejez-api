package cl.adndigital.mejorvejez.notificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;

/**
 * Timeouts SMTP acotados (revisión final de la Fase 5, Important 2): sin
 * ellos JavaMail espera para siempre a un servidor que acepta la conexión y
 * no responde, y el hilo único del planificador queda colgado con la alerta
 * bloqueada. Mismo contexto que {@link ServicioCanalesTest} (se reutiliza).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ConfiguracionCorreoTest {

    @Autowired
    private Environment entorno;

    @Test
    void smtpTieneTimeoutsDeConexionLecturaYEscrituraPorDefecto() {
        assertThat(entorno.getProperty("spring.mail.properties.mail.smtp.connectiontimeout")).isEqualTo("10000");
        assertThat(entorno.getProperty("spring.mail.properties.mail.smtp.timeout")).isEqualTo("10000");
        assertThat(entorno.getProperty("spring.mail.properties.mail.smtp.writetimeout")).isEqualTo("10000");
    }
}
