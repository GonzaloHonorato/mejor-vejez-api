package cl.adndigital.mejorvejez;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * La aplicación completa levanta contra Oracle Free: Flyway aplica las
 * migraciones y Hibernate solo valida el esquema (no lo modifica).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class AplicacionArrancaTest {

    @Autowired
    private Environment entorno;

    @Autowired
    private Flyway flyway;

    @Test
    void contextoLevantaConFlywayYValidate() {
        assertThat(entorno.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
    }
}
