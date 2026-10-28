package cl.adndigital.mejorvejez.configuracion;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import cl.adndigital.mejorvejez.comun.ClaveServicio;

import tools.jackson.databind.json.JsonMapper;

/**
 * Único filtro de la cadena interna ({@code securityMatcher("/api/interno/**")},
 * ver {@link ConfiguracionSeguridad}): exige la cabecera {@value
 * #CABECERA_CLAVE_SERVICIO} y la compara con {@link ClaveServicio}. Si falta
 * o no coincide, corta la cadena con 401 {@link ProblemDetail}
 * "Clave de servicio inválida", sin más datos (ni {@code idCorrelacion}:
 * estas rutas las consume un servicio, no un usuario con sesión).
 *
 * <p>Esta cadena NO configura {@code oauth2ResourceServer}: un {@code
 * Authorization: Bearer <jwt>} no se valida ni se mira, así que una llamada
 * con un JWT de usuario también cae acá y recibe el mismo 401.
 */
public class FiltroClaveServicio extends OncePerRequestFilter {

    public static final String CABECERA_CLAVE_SERVICIO = "X-Clave-Servicio";

    private static final String DETALLE_CLAVE_INVALIDA = "Clave de servicio inválida";

    private final ClaveServicio claveServicio;
    private final JsonMapper jsonMapper;

    public FiltroClaveServicio(ClaveServicio claveServicio, JsonMapper jsonMapper) {
        this.claveServicio = claveServicio;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest solicitud, HttpServletResponse respuesta,
            FilterChain cadena) throws ServletException, IOException {
        String claveRecibida = solicitud.getHeader(CABECERA_CLAVE_SERVICIO);
        if (!claveServicio.coincideCon(claveRecibida)) {
            escribirRechazo(respuesta);
            return;
        }
        cadena.doFilter(solicitud, respuesta);
    }

    private void escribirRechazo(HttpServletResponse respuesta) throws IOException {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, DETALLE_CLAVE_INVALIDA);
        problema.setTitle(DETALLE_CLAVE_INVALIDA);

        respuesta.setStatus(HttpStatus.UNAUTHORIZED.value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(respuesta.getWriter(), problema);
    }
}
