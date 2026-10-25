package cl.adndigital.mejorvejez.seguridad;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionNoAutenticado;
import cl.adndigital.mejorvejez.comun.FiltroCorrelacion;

import tools.jackson.databind.json.JsonMapper;

/**
 * Traduce los rechazos que levanta Spring Security ANTES de llegar a un
 * controlador —sin token, token inválido (401) o autenticado sin permiso
 * (403)— al mismo formato {@link ProblemDetail} en español que usa {@code
 * ManejadorErrores} para el resto de la API. Sin esto, esos casos
 * responderían con el cuerpo por defecto de Spring Security (o vacío), no
 * con un {@code ProblemDetail}.
 *
 * <p>El 401 reutiliza el mismo título/detalle que {@code ManejadorErrores}
 * usa para {@link ExcepcionNoAutenticado} (constantes compartidas ahí, para
 * no duplicar el literal). El 403 reutiliza el mismo título que {@code
 * ManejadorErrores} usa para {@link ExcepcionAccesoDenegado} (constante
 * compartida ahí, por el mismo motivo). El {@code idCorrelacion} se toma del
 * MDC igual que en {@code ManejadorErrores} (clave {@link
 * FiltroCorrelacion#CLAVE_MDC_ID_CORRELACION}).
 *
 * <p>Serializa el {@link ProblemDetail} con el {@link JsonMapper} del
 * contexto de Spring (el mismo que usan los conversores HTTP de MVC), para
 * que el JSON salga con el mismo formato que el de {@code ManejadorErrores}
 * (idCorrelacion al nivel raíz, no anidado).
 */
@Component
public class ManejadorErroresSeguridad implements AuthenticationEntryPoint, AccessDeniedHandler {

    public static final String DETALLE_ACCESO_DENEGADO = "No tienes permiso para realizar esta acción.";

    private final JsonMapper jsonMapper;

    public ManejadorErroresSeguridad(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest solicitud, HttpServletResponse respuesta,
            AuthenticationException excepcion) throws IOException {
        escribir(respuesta, HttpStatus.UNAUTHORIZED, ExcepcionNoAutenticado.TITULO,
                ExcepcionNoAutenticado.MENSAJE_SESION_INVALIDA);
    }

    @Override
    public void handle(HttpServletRequest solicitud, HttpServletResponse respuesta,
            AccessDeniedException excepcion) throws IOException {
        escribir(respuesta, HttpStatus.FORBIDDEN, ExcepcionAccesoDenegado.TITULO, DETALLE_ACCESO_DENEGADO);
    }

    private void escribir(HttpServletResponse respuesta, HttpStatus estado, String titulo, String detalle)
            throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        problema.setProperty("idCorrelacion", MDC.get(FiltroCorrelacion.CLAVE_MDC_ID_CORRELACION));

        respuesta.setStatus(estado.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(respuesta.getWriter(), problema);
    }
}
