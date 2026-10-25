package cl.adndigital.mejorvejez.comun;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Asigna un ID de correlación a cada petición (incluida la pública
 * {@code /api/salud}): respeta la cabecera {@code X-Id-Correlacion} entrante
 * si es válida (hasta {@value #LONGITUD_MAXIMA} caracteres, solo letras,
 * números, guion y guion bajo — el límite de {@code EVENTO_AUDITORIA.ID_CORRELACION}),
 * o genera un UUID nuevo si no viene o no lo es. Lo deja en el MDC (clave
 * {@link #CLAVE_MDC_ID_CORRELACION}) para que los logs, {@code
 * ManejadorErrores} y {@code ServicioAuditoria} lo usen, y siempre lo
 * devuelve en la respuesta.
 *
 * <p>{@link #getOrder()} lo ubica antes de la cadena de seguridad, para que
 * el ID de correlación exista incluso en peticiones rechazadas por
 * autenticación.
 */
@Component
public class FiltroCorrelacion extends OncePerRequestFilter implements Ordered {

    public static final String CABECERA_ID_CORRELACION = "X-Id-Correlacion";
    public static final String CLAVE_MDC_ID_CORRELACION = "idCorrelacion";

    private static final int LONGITUD_MAXIMA = 100;
    private static final Pattern PATRON_ID_CORRELACION_SEGURO =
            Pattern.compile("^[A-Za-z0-9_-]{1," + LONGITUD_MAXIMA + "}$");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest solicitud, HttpServletResponse respuesta,
            FilterChain cadena) throws ServletException, IOException {
        String idCorrelacion = resolverIdCorrelacion(solicitud.getHeader(CABECERA_ID_CORRELACION));
        MDC.put(CLAVE_MDC_ID_CORRELACION, idCorrelacion);
        respuesta.setHeader(CABECERA_ID_CORRELACION, idCorrelacion);
        try {
            cadena.doFilter(solicitud, respuesta);
        } finally {
            MDC.remove(CLAVE_MDC_ID_CORRELACION);
        }
    }

    private String resolverIdCorrelacion(String cabeceraEntrante) {
        if (cabeceraEntrante != null && PATRON_ID_CORRELACION_SEGURO.matcher(cabeceraEntrante).matches()) {
            return cabeceraEntrante;
        }
        return UUID.randomUUID().toString();
    }
}
