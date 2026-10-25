package cl.adndigital.mejorvejez;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;

/**
 * Oracle Free real en UN SOLO contenedor, compartido por TODOS los contextos
 * Spring de la JVM (cada {@code @JdbcTest}/{@code @SpringBootTest} con
 * configuración distinta levanta su propio {@code ApplicationContext}; sin
 * compartir el contenedor, cada uno abría su propio Oracle y la VM de
 * Testcontainers se quedaba sin memoria). {@code @ServiceConnection}
 * reemplaza el datasource de application.properties (wallet/ADB), así que
 * ninguna prueba toca la base de producción.
 *
 * <p>El contenedor se guarda en un campo {@code static final} e inicia una
 * sola vez por JVM. {@code @Bean(destroyMethod = "")} evita que Spring Boot
 * lo detenga cuando cierra un contexto (Testcontainers expone {@code close()}
 * como método de destrucción inferido, y pararía el contenedor compartido al
 * terminar el primer contexto, dejando sin base a los siguientes). Ryuk lo
 * elimina igual al terminar la JVM, así que no quedan contenedores
 * huérfanos.
 *
 * <p>Las pruebas comparten esquema. {@code @JdbcTest} (p.ej.
 * {@code MigracionesFlywayTest}, {@code RestriccionesModeloTest}) hace
 * rollback automático de cada método de prueba, y {@code
 * AplicacionArrancaTest} solo lee metadatos (Flyway/Environment). Pero
 * algunas pruebas {@code @SpringBootTest} SÍ confirman datos en la base
 * compartida (p.ej. {@code ServicioUsuarioActualTest}, {@code
 * ServicioAuditoriaTest}): por eso toda prueba debe crear sus datos con
 * identificadores únicos ({@code UUID}) y filtrar sus consultas y
 * aserciones por esos datos propios, nunca contar o leer la tabla completa.
 *
 * <p>Uso: {@code @Import(ConfiguracionTestcontainers.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ConfiguracionTestcontainers {

    private static final OracleContainer ORACLE_COMPARTIDO = new OracleContainer(
            "gvenzl/oracle-free:23-slim-faststart");

    static {
        ORACLE_COMPARTIDO.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection
    OracleContainer oracle() {
        return ORACLE_COMPARTIDO;
    }
}
