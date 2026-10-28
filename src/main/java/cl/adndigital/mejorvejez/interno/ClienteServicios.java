package cl.adndigital.mejorvejez.interno;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import cl.adndigital.mejorvejez.comun.ExcepcionServicioExterno;
import cl.adndigital.mejorvejez.configuracion.FiltroClaveServicio;

import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente de las rutas internas de los microservicios de Alertas y de
 * Reportes (Fase 9): lo usan las rutas de administración para reenviar una
 * corrida manual, con la cabecera {@value
 * FiltroClaveServicio#CABECERA_CLAVE_SERVICIO}, un tiempo máximo de {@value
 * #TIMEOUT_CONEXION_SEGUNDOS} segundos para conectar y un tiempo de lectura
 * configurable por servicio ({@code mejorvejez.servicios.alertas-timeout-seg}
 * / {@code ...-reportes-timeout-seg}): las corridas remotas son síncronas
 * dentro del request de Alertas o de Reportes y pueden tardar varios
 * minutos (SMTP real, un reporte semanal con varios grupos).
 *
 * <p>Un 4xx del servicio remoto se propaga con el mismo código y el {@code
 * detail} de su {@code ProblemDetail} (ya en español, porque el servicio
 * remoto también lo redacta así), salvo 401/403: esos dos significan que la
 * clave de servicio configurada en este Core no coincide con la del remoto,
 * no un dato inválido de la solicitud, así que se tratan igual que un 503
 * (con su propio mensaje de log, nunca la clave). Un corte por timeout de
 * LECTURA (un {@code SocketTimeoutException} en cualquier punto de la
 * cadena de causas, que no sea el de conexión) se traduce en 504: el servicio sí respondió a la conexión, solo que no
 * alcanzó a terminar dentro del plazo, así que la corrida puede seguir en
 * curso. Cualquier otra falla al llamar (5xx, un cuerpo de éxito que no se
 * puede leer como el tipo esperado, timeout de conexión o conexión
 * rechazada) se traduce en 503 con un mensaje fijo por servicio: quien
 * llama no necesita distinguir la causa exacta, solo que el servicio no
 * respondió.
 */
@Component
public class ClienteServicios {

    private static final Logger LOG = LoggerFactory.getLogger(ClienteServicios.class);

    static final int TIMEOUT_CONEXION_SEGUNDOS = 5;

    static final String MENSAJE_ALERTAS_NO_DISPONIBLE = "El servicio de alertas no está disponible.";
    static final String MENSAJE_REPORTES_NO_DISPONIBLE = "El servicio de reportes no está disponible.";
    static final String MENSAJE_ALERTAS_TIMEOUT_LECTURA =
            "El servicio de alertas no respondió a tiempo; la corrida puede seguir en curso.";
    static final String MENSAJE_REPORTES_TIMEOUT_LECTURA =
            "El servicio de reportes no respondió a tiempo; la corrida puede seguir en curso.";
    private static final String MENSAJE_RESPUESTA_INVALIDA = "El servicio remoto respondió de forma inesperada.";

    private static final String RUTA_DETECCION_ATRASOS = "/api/interno/procesos/deteccion-atrasos";
    private static final String RUTA_ALERTAS_PREVENTIVAS = "/api/interno/procesos/alertas-preventivas";
    private static final String RUTA_REPORTE_SEMANAL = "/api/interno/procesos/reporte-semanal";

    private final RestClient clienteAlertas;
    private final RestClient clienteReportes;
    private final JsonMapper jsonMapper;

    public ClienteServicios(@Value("${mejorvejez.servicios.alertas-url}") String urlAlertas,
            @Value("${mejorvejez.servicios.reportes-url}") String urlReportes,
            @Value("${mejorvejez.interno.clave-servicio}") String claveServicio,
            @Value("${mejorvejez.servicios.alertas-timeout-seg}") int timeoutLecturaAlertasSegundos,
            @Value("${mejorvejez.servicios.reportes-timeout-seg}") int timeoutLecturaReportesSegundos,
            JsonMapper jsonMapper) {
        this.clienteAlertas = construirCliente(urlAlertas, claveServicio, timeoutLecturaAlertasSegundos);
        this.clienteReportes = construirCliente(urlReportes, claveServicio, timeoutLecturaReportesSegundos);
        this.jsonMapper = jsonMapper;
    }

    /** Reenvía {@code POST /api/interno/procesos/deteccion-atrasos} a Alertas. */
    public ResumenCorrida deteccionAtrasos() {
        return invocar(
                () -> clienteAlertas.post().uri(RUTA_DETECCION_ATRASOS).retrieve().body(ResumenCorrida.class),
                MENSAJE_ALERTAS_NO_DISPONIBLE, MENSAJE_ALERTAS_TIMEOUT_LECTURA);
    }

    /** Reenvía {@code POST /api/interno/procesos/alertas-preventivas} a Alertas. */
    public ResumenCorrida alertasPreventivas() {
        return invocar(
                () -> clienteAlertas.post().uri(RUTA_ALERTAS_PREVENTIVAS).retrieve().body(ResumenCorrida.class),
                MENSAJE_ALERTAS_NO_DISPONIBLE, MENSAJE_ALERTAS_TIMEOUT_LECTURA);
    }

    /** Reenvía {@code POST /api/interno/procesos/reporte-semanal} a Reportes, con {@code inicio} si viene. */
    public ResumenReportes reporteSemanal(LocalDate inicio) {
        return invocar(() -> {
            RestClient.RequestHeadersSpec<?> peticion = inicio == null
                    ? clienteReportes.post().uri(RUTA_REPORTE_SEMANAL)
                    : clienteReportes.post().uri(RUTA_REPORTE_SEMANAL + "?inicio={inicio}", inicio);
            return peticion.retrieve().body(ResumenReportes.class);
        }, MENSAJE_REPORTES_NO_DISPONIBLE, MENSAJE_REPORTES_TIMEOUT_LECTURA);
    }

    private <T> T invocar(Supplier<T> llamada, String mensajeNoDisponible, String mensajeTimeoutLectura) {
        try {
            T respuesta = llamada.get();
            if (respuesta == null) {
                throw new ExcepcionServicioExterno(HttpStatus.SERVICE_UNAVAILABLE, mensajeNoDisponible);
            }
            return respuesta;
        } catch (RestClientResponseException ex) {
            int codigo = ex.getStatusCode().value();
            if (codigo == HttpStatus.UNAUTHORIZED.value() || codigo == HttpStatus.FORBIDDEN.value()) {
                LOG.error("La clave de servicio fue rechazada por el servicio remoto (HTTP {}).", codigo);
                throw new ExcepcionServicioExterno(HttpStatus.SERVICE_UNAVAILABLE, mensajeNoDisponible);
            }
            if (ex.getStatusCode().is4xxClientError()) {
                throw new ExcepcionServicioExterno(HttpStatus.valueOf(codigo), detalleDe(ex));
            }
            throw new ExcepcionServicioExterno(HttpStatus.SERVICE_UNAVAILABLE, mensajeNoDisponible);
        } catch (RestClientException ex) {
            // Un SocketTimeoutException puede llegar envuelto en ResourceAccessException (falla al
            // conectar o al leer, dentro de RestClient#execute) o en un RestClientException genérico
            // (al leer el cuerpo de una respuesta cuyos encabezados sí llegaron, con el conversor de
            // mensajes todavía leyendo el InputStream cuando se agota el timeout de lectura): en
            // ambos casos es el mismo corte de lectura, así que se busca en toda la cadena de causas.
            if (tieneCausaDeTimeoutDeLectura(ex)) {
                throw new ExcepcionServicioExterno(HttpStatus.GATEWAY_TIMEOUT, mensajeTimeoutLectura);
            }
            // Cualquier otra falla del cliente (conexión rechazada, 5xx, un 200 con un cuerpo que no
            // se puede leer como el tipo esperado) se trata igual que si el servicio no hubiera
            // respondido.
            throw new ExcepcionServicioExterno(HttpStatus.SERVICE_UNAVAILABLE, mensajeNoDisponible);
        }
    }

    /**
     * {@code true} si en la cadena de causas hay un {@code SocketTimeoutException} de lectura. El
     * de conexión ("Connect timed out") queda fuera: ahí el servicio nunca recibió la llamada, así
     * que corresponde 503 y no 504.
     */
    static boolean tieneCausaDeTimeoutDeLectura(Throwable ex) {
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            if (causa instanceof SocketTimeoutException timeout) {
                String mensaje = timeout.getMessage();
                return mensaje == null || !mensaje.toLowerCase(Locale.ROOT).contains("connect");
            }
        }
        return false;
    }

    /** El {@code detail} del {@code ProblemDetail} del cuerpo de error, o un mensaje genérico si no se puede leer. */
    private String detalleDe(RestClientResponseException ex) {
        try {
            Map<?, ?> cuerpo = jsonMapper.readValue(ex.getResponseBodyAsString(), Map.class);
            Object detalle = cuerpo.get("detail");
            if (detalle instanceof String texto && !texto.isBlank()) {
                return texto;
            }
        } catch (RuntimeException ignorada) {
            // El cuerpo no es JSON o no tiene 'detail': se usa el mensaje genérico de abajo.
        }
        return MENSAJE_RESPUESTA_INVALIDA;
    }

    private static RestClient construirCliente(String urlBase, String claveServicio, int timeoutLecturaSegundos) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(TIMEOUT_CONEXION_SEGUNDOS));
        fabrica.setReadTimeout(Duration.ofSeconds(timeoutLecturaSegundos));
        return RestClient.builder()
                .baseUrl(urlBase)
                .requestFactory(fabrica)
                .defaultHeader(FiltroClaveServicio.CABECERA_CLAVE_SERVICIO, claveServicio)
                .build();
    }
}
