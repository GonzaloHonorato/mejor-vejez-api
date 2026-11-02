package cl.adndigital.mejorvejez.comun;

import java.util.List;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Traduce las excepciones de la aplicación (y las de Spring) a respuestas
 * {@link ProblemDetail} uniformes, en español, sin filtrar detalles internos
 * (mensajes de Oracle, nombres de tablas/constraints, stack traces).
 *
 * <p>Las excepciones estándar de Spring MVC que ya traen su estado HTTP
 * (las que implementan {@link ErrorResponse}: ruta inexistente, método no
 * permitido, tipo de contenido no soportado...) conservan ese estado 4xx con
 * un título/detalle genérico en español; no se registran como error.
 *
 * <p>La propiedad {@code idCorrelacion} se toma del MDC (clave
 * {@link FiltroCorrelacion#CLAVE_MDC_ID_CORRELACION}), donde {@code
 * FiltroCorrelacion} la deja por cada petición.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger LOG = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(ExcepcionNoEncontrado.class)
    public ProblemDetail manejarNoEncontrado(ExcepcionNoEncontrado excepcion) {
        return construirProblema(HttpStatus.NOT_FOUND, "Recurso no encontrado", excepcion.getMessage());
    }

    @ExceptionHandler(ExcepcionReglaNegocio.class)
    public ProblemDetail manejarReglaNegocio(ExcepcionReglaNegocio excepcion) {
        return construirProblema(HttpStatus.UNPROCESSABLE_ENTITY, "Regla de negocio", excepcion.getMessage());
    }

    @ExceptionHandler(ExcepcionConflicto.class)
    public ProblemDetail manejarConflicto(ExcepcionConflicto excepcion) {
        return construirProblema(HttpStatus.CONFLICT, "Conflicto", excepcion.getMessage());
    }

    @ExceptionHandler(ExcepcionSolicitudInvalida.class)
    public ProblemDetail manejarSolicitudInvalida(ExcepcionSolicitudInvalida excepcion) {
        return construirProblema(HttpStatus.BAD_REQUEST, "Datos inválidos", excepcion.getMessage());
    }

    @ExceptionHandler(ExcepcionCampoInvalido.class)
    public ProblemDetail manejarCampoInvalido(ExcepcionCampoInvalido excepcion) {
        ProblemDetail problema = construirProblema(HttpStatus.BAD_REQUEST, "Datos inválidos",
                "La solicitud tiene uno o más campos inválidos.");
        problema.setProperty("errores", List.of(new ErrorCampo(excepcion.getCampo(), excepcion.getMessage())));
        return problema;
    }

    @ExceptionHandler(ExcepcionNoAutenticado.class)
    public ProblemDetail manejarNoAutenticado(ExcepcionNoAutenticado excepcion) {
        return construirProblema(HttpStatus.UNAUTHORIZED, ExcepcionNoAutenticado.TITULO, excepcion.getMessage());
    }

    @ExceptionHandler(ExcepcionAccesoDenegado.class)
    public ProblemDetail manejarAccesoDenegado(ExcepcionAccesoDenegado excepcion) {
        return construirProblema(HttpStatus.FORBIDDEN, ExcepcionAccesoDenegado.TITULO, excepcion.getMessage());
    }

    @ExceptionHandler(ExcepcionCuentasNoConfiguradas.class)
    public ProblemDetail manejarCuentasNoConfiguradas(ExcepcionCuentasNoConfiguradas excepcion) {
        return construirProblema(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", excepcion.getMessage());
    }

    @ExceptionHandler(ExcepcionDemasiadosIntentos.class)
    public ProblemDetail manejarDemasiadosIntentos(ExcepcionDemasiadosIntentos excepcion) {
        return construirProblema(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos", excepcion.getMessage());
    }

    /**
     * Error de una llamada interna a Alertas o a Reportes ({@code
     * ClienteServicios}): conserva el código y el detalle que trae la
     * excepción (el mismo 4xx del servicio remoto, o 503 si no respondió),
     * sin generar un texto propio.
     */
    @ExceptionHandler(ExcepcionServicioExterno.class)
    public ResponseEntity<ProblemDetail> manejarServicioExterno(ExcepcionServicioExterno excepcion) {
        ProblemDetail problema = construirProblema(excepcion.getEstado(), tituloServicioExterno(excepcion.getEstado()),
                excepcion.getMessage());
        return ResponseEntity.status(excepcion.getEstado()).body(problema);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail manejarValidacion(MethodArgumentNotValidException excepcion) {
        ProblemDetail problema = construirProblema(HttpStatus.BAD_REQUEST, "Datos inválidos",
                "La solicitud tiene uno o más campos inválidos.");
        List<ErrorCampo> errores = excepcion.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorCampo(error.getField(), mensajeDeCampo(error)))
                .toList();
        problema.setProperty("errores", errores);
        return problema;
    }

    /**
     * Violación de una anotación de validación ({@code @Min}, etc.) en un
     * parámetro de método de un {@code @Validated} (p. ej. {@code
     * @RequestParam}), distinta de {@link MethodArgumentNotValidException}
     * (que valida el cuerpo con {@code @Valid}).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail manejarViolacionDeRestriccion(ConstraintViolationException excepcion) {
        ProblemDetail problema = construirProblema(HttpStatus.BAD_REQUEST, "Datos inválidos",
                "La solicitud tiene uno o más parámetros inválidos.");
        List<ErrorCampo> errores = excepcion.getConstraintViolations().stream()
                .map(violacion -> new ErrorCampo(nombreDeParametro(violacion), violacion.getMessage()))
                .toList();
        problema.setProperty("errores", errores);
        return problema;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail manejarTipoInvalido(MethodArgumentTypeMismatchException excepcion) {
        return construirProblema(HttpStatus.BAD_REQUEST, "Datos inválidos",
                "El parámetro '" + excepcion.getName() + "' tiene un valor inválido.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail manejarParametroFaltante(MissingServletRequestParameterException excepcion) {
        LOG.debug("Parámetro obligatorio faltante: {}", excepcion.getParameterName());
        return construirProblema(HttpStatus.BAD_REQUEST, "Datos inválidos",
                "Falta el parámetro obligatorio '" + excepcion.getParameterName() + "'.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail manejarCuerpoIlegible(HttpMessageNotReadableException excepcion) {
        // El mensaje de Jackson nombra clases internas: va solo al log.
        LOG.debug("Cuerpo de solicitud ilegible: {}", excepcion.getMessage());
        return construirProblema(HttpStatus.BAD_REQUEST, "Datos inválidos",
                "El cuerpo de la solicitud no es válido.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail manejarIntegridadDeDatos(DataIntegrityViolationException excepcion) {
        LOG.error("Violación de integridad de datos", excepcion);
        return construirProblema(HttpStatus.CONFLICT, "Conflicto",
                "La operación entra en conflicto con datos existentes.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> manejarErrorInesperado(Exception excepcion) {
        if (excepcion instanceof ErrorResponse respuestaSpring
                && respuestaSpring.getStatusCode().is4xxClientError()) {
            return manejarErrorDeCliente(respuestaSpring);
        }
        LOG.error("Error no controlado", excepcion);
        return ResponseEntity.internalServerError().body(construirProblema(HttpStatus.INTERNAL_SERVER_ERROR,
                "Error interno", "Ocurrió un error inesperado."));
    }

    /**
     * Excepción estándar de Spring con estado 4xx: conserva el estado y sus
     * cabeceras (p. ej. {@code Allow} en un 405) y responde con un texto
     * genérico en español, sin el mensaje original (que puede nombrar clases
     * o rutas internas).
     */
    private ResponseEntity<ProblemDetail> manejarErrorDeCliente(ErrorResponse respuestaSpring) {
        HttpStatusCode codigo = respuestaSpring.getStatusCode();
        LOG.debug("Error de cliente {}: {}", codigo.value(), respuestaSpring.getBody().getDetail());
        HttpStatus estado = HttpStatus.resolve(codigo.value());
        ProblemDetail problema = construirProblema(estado != null ? estado : HttpStatus.BAD_REQUEST,
                tituloDeErrorDeCliente(codigo), detalleDeErrorDeCliente(codigo));
        return ResponseEntity.status(codigo).headers(respuestaSpring.getHeaders()).body(problema);
    }

    private String tituloDeErrorDeCliente(HttpStatusCode codigo) {
        return switch (codigo.value()) {
            case 404 -> "Recurso no encontrado";
            case 405 -> "Método no permitido";
            case 406 -> "Formato de respuesta no disponible";
            case 415 -> "Tipo de contenido no soportado";
            default -> "Datos inválidos";
        };
    }

    private String detalleDeErrorDeCliente(HttpStatusCode codigo) {
        return switch (codigo.value()) {
            case 404 -> "El recurso solicitado no existe.";
            case 405 -> "El método HTTP no está permitido para este recurso.";
            case 406 -> "No se puede responder en el formato solicitado.";
            case 415 -> "El tipo de contenido de la solicitud no está soportado.";
            default -> "La solicitud no es válida.";
        };
    }

    private String tituloServicioExterno(HttpStatus estado) {
        if (estado == HttpStatus.SERVICE_UNAVAILABLE) {
            return "Servicio no disponible";
        }
        if (estado == HttpStatus.GATEWAY_TIMEOUT) {
            return "Tiempo de espera agotado";
        }
        return switch (estado) {
            case BAD_REQUEST -> "Datos inválidos";
            case NOT_FOUND -> "Recurso no encontrado";
            case CONFLICT -> "Conflicto";
            case UNPROCESSABLE_ENTITY -> "Regla de negocio";
            default -> "Error del servicio";
        };
    }

    private ProblemDetail construirProblema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        problema.setProperty("idCorrelacion", MDC.get(FiltroCorrelacion.CLAVE_MDC_ID_CORRELACION));
        return problema;
    }

    private String mensajeDeCampo(FieldError error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "Valor inválido.";
    }

    /** Último tramo de la ruta de la violación (p. ej. {@code "listar.pagina"} → {@code "pagina"}). */
    private String nombreDeParametro(ConstraintViolation<?> violacion) {
        String ruta = violacion.getPropertyPath().toString();
        int ultimoPunto = ruta.lastIndexOf('.');
        return ultimoPunto >= 0 ? ruta.substring(ultimoPunto + 1) : ruta;
    }

    /** Un error de validación de un campo puntual del cuerpo de la solicitud. */
    public record ErrorCampo(String campo, String mensaje) {
    }
}
