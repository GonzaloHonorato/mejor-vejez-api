package cl.adndigital.mejorvejez.comun;

import org.springframework.http.HttpStatus;

/**
 * Se lanza cuando una llamada interna a uno de los microservicios (Alertas o
 * Reportes) falla: {@code estado} es el código a propagar en la respuesta
 * ({@code HttpStatus.SERVICE_UNAVAILABLE} si el servicio no respondió, o el
 * mismo 4xx que devolvió) y el mensaje es el detalle en español que verá el
 * administrador. {@code ManejadorErrores} la traduce directamente a un
 * {@code ProblemDetail} con ese código y ese detalle, sin generar uno
 * propio.
 */
public class ExcepcionServicioExterno extends RuntimeException {

    private final HttpStatus estado;

    public ExcepcionServicioExterno(HttpStatus estado, String mensaje) {
        super(mensaje);
        this.estado = estado;
    }

    public HttpStatus getEstado() {
        return estado;
    }
}
