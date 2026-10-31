package cl.adndigital.mejorvejez;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.oracle.OracleContainer;

import cl.adndigital.mejorvejez.cuentas.EjecutorRecuperarClave;
import cl.adndigital.mejorvejez.cuentas.ProveedorIdentidadFalso;

/**
 * Oracle Free real en UN SOLO contenedor, compartido por TODOS los contextos
 * Spring de la JVM (cada {@code @JdbcTest}/{@code @SpringBootTest} con
 * configuración distinta levanta su propio {@code ApplicationContext}; sin
 * compartir el contenedor, cada uno abría su propio Oracle y la VM de
 * Testcontainers se quedaba sin memoria).
 *
 * <p>Ronda de corrección 2 (Fase 6, Tarea 1): el contenedor NO se expone como
 * {@code @Bean} (antes era {@code @Bean(destroyMethod = "") @ServiceConnection}).
 * Cuando la caché de contextos de Spring Test expulsa un contexto para hacer
 * sitio a uno nuevo, lo cierra; al cerrarlo, el {@code
 * TestcontainersLifecycleBeanPostProcessor} de Spring Boot detiene cualquier
 * bean {@code Startable} sin método de destrucción propio — exactamente lo
 * que {@code destroyMethod = ""} dejaba sin método. Es decir: cada expulsión
 * de la caché PARABA el contenedor compartido; el siguiente contexto en
 * necesitarlo lo volvía a levantar en un puerto nuevo, y los contextos que
 * seguían en caché quedaban apuntando al puerto viejo (de ahí {@code
 * ORA-03113}/{@code ORA-12541}, no falta de memoria).
 *
 * <p>La corrección: en vez de un {@code @Bean}, un {@link
 * DynamicPropertyRegistrar} registra directamente {@code
 * spring.datasource.url}/{@code username}/{@code password}/{@code
 * driver-class-name} apuntando al contenedor. Así ningún bean de Spring
 * referencia al contenedor y ningún post-procesador de ciclo de vida lo
 * toca: vive solo en el campo {@code static final} de esta clase y lo
 * detiene Ryuk al terminar la JVM, nunca a mitad de la suite.
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
 * <p>Uso: {@code @Import(ConfiguracionTestcontainers.class)}. Por la misma
 * razón (un solo contexto de pruebas compartido en vez de uno nuevo por
 * configuración), esta clase también registra {@link ProveedorIdentidadFalso}
 * como {@code @Primary}: así todo contexto que importe esta configuración
 * recibe el proveedor de identidad en memoria en vez de intentar levantar
 * {@code ConfiguracionFirebase} (sin credencial en las pruebas, expondría
 * {@code ProveedorIdentidadNoConfigurado}, que no sirve para probar las
 * rutas de cuentas). Cada prueba que lo use debe limpiarlo en su
 * {@code @BeforeEach} con {@link ProveedorIdentidadFalso#limpiar()}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ConfiguracionTestcontainers {

    private static final OracleContainer ORACLE_COMPARTIDO = new OracleContainer(
            "gvenzl/oracle-free:23-slim-faststart");

    static {
        ORACLE_COMPARTIDO.start();
    }

    @Bean
    DynamicPropertyRegistrar propiedadesOracle() {
        return registry -> {
            registry.add("spring.datasource.url", ORACLE_COMPARTIDO::getJdbcUrl);
            registry.add("spring.datasource.username", ORACLE_COMPARTIDO::getUsername);
            registry.add("spring.datasource.password", ORACLE_COMPARTIDO::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
        };
    }

    @Bean
    @Primary
    ProveedorIdentidadFalso proveedorIdentidadFalso() {
        return new ProveedorIdentidadFalso();
    }

    /**
     * Reemplaza (por tipo, el único {@link EjecutorRecuperarClave} del
     * contexto) el executor acotado de {@code ServicioRecuperarClave} por
     * uno síncrono (corre en el mismo hilo del llamador): así las pruebas
     * pueden verificar el envío sin depender de temporización ni de
     * {@code Awaitility}. Al envolver un {@code Runnable::run} y no un
     * {@code Executor} expuesto directamente, tampoco interfiere con el
     * {@code applicationTaskExecutor} que Spring Boot le da a cualquier
     * {@code @Async} de la aplicación (ver Javadoc de {@code
     * EjecutorRecuperarClave}).
     */
    @Bean
    @Primary
    EjecutorRecuperarClave ejecutorRecuperarClaveSincrono() {
        return new EjecutorRecuperarClave(Runnable::run);
    }
}
