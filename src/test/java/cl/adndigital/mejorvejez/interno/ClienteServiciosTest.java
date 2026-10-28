package cl.adndigital.mejorvejez.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

import cl.adndigital.mejorvejez.comun.ExcepcionServicioExterno;
import cl.adndigital.mejorvejez.configuracion.FiltroClaveServicio;

import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ClienteServicios} sin Docker, con Alertas y Reportes
 * simulados (Fase 9, TSA-62): la propagación de un 4xx del servicio remoto
 * con el mismo código y su {@code detail}, la traducción a 503 de cualquier
 * otra falla (5xx, cuerpo inesperado, conexión rechazada) y el reenvío de
 * {@code inicio} como parámetro de consulta.
 */
class ClienteServiciosTest {

    private static final String CLAVE_SERVICIO = "clave-de-prueba";
    private static final int TIMEOUT_LECTURA_NORMAL_SEGUNDOS = 30;

    private MockWebServer alertas;
    private MockWebServer reportes;
    private ClienteServicios cliente;

    @BeforeEach
    void iniciarServiciosSimulados() throws Exception {
        alertas = new MockWebServer();
        alertas.start();
        reportes = new MockWebServer();
        reportes.start();
        cliente = new ClienteServicios(alertas.url("/").toString(), reportes.url("/").toString(), CLAVE_SERVICIO,
                TIMEOUT_LECTURA_NORMAL_SEGUNDOS, TIMEOUT_LECTURA_NORMAL_SEGUNDOS, JsonMapper.builder().build());
    }

    @AfterEach
    void cerrarServiciosSimulados() {
        cerrarSilenciosamente(alertas);
        cerrarSilenciosamente(reportes);
    }

    /** Una prueba (la de conexión rechazada) ya cierra su propio servidor antes de este método. */
    private void cerrarSilenciosamente(MockWebServer servidor) {
        try {
            servidor.close();
        } catch (Exception yaCerrado) {
            // Ignorado: el propósito es dejar el puerto liberado, sin importar si ya lo estaba.
        }
    }

    @Test
    void timeoutDeConexionNoCuentaComoTimeoutDeLectura() {
        ResourceAccessException conexion = new ResourceAccessException("I/O error",
                new SocketTimeoutException("Connect timed out"));
        ResourceAccessException lectura = new ResourceAccessException("I/O error",
                new SocketTimeoutException("Read timed out"));

        assertThat(ClienteServicios.tieneCausaDeTimeoutDeLectura(conexion)).isFalse();
        assertThat(ClienteServicios.tieneCausaDeTimeoutDeLectura(lectura)).isTrue();
    }

    @Test
    void deteccionAtrasosExitoDevuelveElResumenYEnviaLaClave() throws Exception {
        alertas.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"idEjecucion\":10,\"creadas\":2,\"errores\":0,\"resumen\":null}").build());

        ResumenCorrida resumen = cliente.deteccionAtrasos();

        assertThat(resumen).isEqualTo(new ResumenCorrida(10L, 2, 0, null));
        RecordedRequest solicitud = alertas.takeRequest(5, TimeUnit.SECONDS);
        assertThat(solicitud).isNotNull();
        assertThat(solicitud.getMethod()).isEqualTo("POST");
        assertThat(solicitud.getTarget()).isEqualTo("/api/interno/procesos/deteccion-atrasos");
        assertThat(solicitud.getHeaders().get(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO)).isEqualTo(CLAVE_SERVICIO);
    }

    @Test
    void errorDeAlertasSePropagaConElMismoCodigoYSuDetail() {
        alertas.enqueue(new MockResponse.Builder().code(409).addHeader("Content-Type", "application/problem+json")
                .body("{\"detail\":\"Ya hay una corrida de alertas en curso.\"}").build());

        assertThatThrownBy(cliente::deteccionAtrasos)
                .isInstanceOf(ExcepcionServicioExterno.class)
                .hasMessage("Ya hay una corrida de alertas en curso.")
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void errorSinDetailUsaUnMensajeGenerico() {
        alertas.enqueue(new MockResponse.Builder().code(400).addHeader("Content-Type", "application/json")
                .body("{\"otraCosa\":true}").build());

        assertThatThrownBy(cliente::deteccionAtrasos)
                .isInstanceOf(ExcepcionServicioExterno.class)
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void errorDelServidorSePropagaComo503() {
        alertas.enqueue(new MockResponse.Builder().code(500).build());

        assertThatThrownBy(cliente::alertasPreventivas)
                .isInstanceOf(ExcepcionServicioExterno.class)
                .hasMessage("El servicio de alertas no está disponible.")
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void conexionRechazadaSePropagaComo503() throws Exception {
        // El servidor simulado ya está cerrado: ninguna conexión puede completarse.
        alertas.close();

        assertThatThrownBy(cliente::deteccionAtrasos)
                .isInstanceOf(ExcepcionServicioExterno.class)
                .hasMessage("El servicio de alertas no está disponible.")
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void reporteSemanalConInicioLoEnviaComoParametroDeConsulta() throws Exception {
        reportes.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"idEjecucion\":5,\"gruposProcesados\":1,\"enviados\":1,\"fallidos\":0,\"omitidos\":0}")
                .build());

        ResumenReportes resumen = cliente.reporteSemanal(LocalDate.of(2026, 11, 2));

        assertThat(resumen).isEqualTo(new ResumenReportes(5L, 1, 1, 0, 0));
        RecordedRequest solicitud = reportes.takeRequest(5, TimeUnit.SECONDS);
        assertThat(solicitud).isNotNull();
        assertThat(solicitud.getTarget()).isEqualTo("/api/interno/procesos/reporte-semanal?inicio=2026-11-02");
    }

    @Test
    void reporteSemanalSinInicioNoEnviaElParametro() throws Exception {
        reportes.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"idEjecucion\":6,\"gruposProcesados\":0,\"enviados\":0,\"fallidos\":0,\"omitidos\":0}")
                .build());

        cliente.reporteSemanal(null);

        RecordedRequest solicitud = reportes.takeRequest(5, TimeUnit.SECONDS);
        assertThat(solicitud).isNotNull();
        assertThat(solicitud.getTarget()).isEqualTo("/api/interno/procesos/reporte-semanal");
    }

    @Test
    void conflictoDeReportesSePropagaComo409() {
        reportes.enqueue(new MockResponse.Builder().code(409).addHeader("Content-Type", "application/problem+json")
                .body("{\"detail\":\"Ya hay un reporte semanal en ejecución.\"}").build());

        assertThatThrownBy(() -> cliente.reporteSemanal(null))
                .isInstanceOf(ExcepcionServicioExterno.class)
                .hasMessage("Ya hay un reporte semanal en ejecución.")
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------
    // Cuerpo ilegible y clave de servicio rechazada
    // ------------------------------------------------------------------

    @Test
    void cuerpoDeExitoIlegibleSePropagaComo503() {
        alertas.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "text/html")
                .body("<html>no soy json</html>").build());

        assertThatThrownBy(cliente::deteccionAtrasos)
                .isInstanceOf(ExcepcionServicioExterno.class)
                .hasMessage("El servicio de alertas no está disponible.")
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void claveRechazadaPor401SePropagaComo503YSeLogeaSinMostrarLaClave() {
        alertas.enqueue(new MockResponse.Builder().code(401).addHeader("Content-Type", "application/problem+json")
                .body("{\"detail\":\"Clave de servicio inválida\"}").build());

        Logger logger = (Logger) LoggerFactory.getLogger(ClienteServicios.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(cliente::deteccionAtrasos)
                    .isInstanceOf(ExcepcionServicioExterno.class)
                    .hasMessage("El servicio de alertas no está disponible.")
                    .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).anySatisfy(mensaje -> {
                assertThat(mensaje.toLowerCase()).contains("clave de servicio").contains("rechazada");
                assertThat(mensaje).doesNotContain(CLAVE_SERVICIO);
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void claveRechazadaPor403SePropagaComo503() {
        alertas.enqueue(new MockResponse.Builder().code(403).build());

        assertThatThrownBy(cliente::alertasPreventivas)
                .isInstanceOf(ExcepcionServicioExterno.class)
                .hasMessage("El servicio de alertas no está disponible.")
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ------------------------------------------------------------------
    // Timeout de lectura por servicio, distinto del de conexión
    // ------------------------------------------------------------------

    /**
     * Con un timeout de lectura corto, una respuesta que demora más que ese
     * plazo corta la conexión por {@code SocketTimeoutException}: se
     * propaga como 504 (no 503), con el mensaje que deja ver que la corrida
     * remota puede seguir en curso.
     */
    @Test
    void timeoutDeLecturaDeAlertasSePropagaComo504() throws Exception {
        alertas.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"idEjecucion\":1,\"creadas\":0,\"errores\":0,\"resumen\":null}")
                .bodyDelay(2, TimeUnit.SECONDS).build());

        ClienteServicios clienteConTimeoutCorto = new ClienteServicios(alertas.url("/").toString(),
                reportes.url("/").toString(), CLAVE_SERVICIO, 1, TIMEOUT_LECTURA_NORMAL_SEGUNDOS,
                JsonMapper.builder().build());

        assertThatThrownBy(clienteConTimeoutCorto::deteccionAtrasos)
                .isInstanceOf(ExcepcionServicioExterno.class)
                .hasMessage("El servicio de alertas no respondió a tiempo; la corrida puede seguir en curso.")
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    /** Mismo caso que el anterior, pero para Reportes y con su propio mensaje. */
    @Test
    void timeoutDeLecturaDeReportesSePropagaComo504() throws Exception {
        reportes.enqueue(new MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body("{\"idEjecucion\":1,\"gruposProcesados\":0,\"enviados\":0,\"fallidos\":0,\"omitidos\":0}")
                .bodyDelay(2, TimeUnit.SECONDS).build());

        ClienteServicios clienteConTimeoutCorto = new ClienteServicios(alertas.url("/").toString(),
                reportes.url("/").toString(), CLAVE_SERVICIO, TIMEOUT_LECTURA_NORMAL_SEGUNDOS, 1,
                JsonMapper.builder().build());

        assertThatThrownBy(() -> clienteConTimeoutCorto.reporteSemanal(null))
                .isInstanceOf(ExcepcionServicioExterno.class)
                .hasMessage("El servicio de reportes no respondió a tiempo; la corrida puede seguir en curso.")
                .extracting(ex -> ((ExcepcionServicioExterno) ex).getEstado())
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }
}
