package cl.adndigital.mejorvejez.comun;

/**
 * Se lanza cuando la solicitud no cumple una condición de forma exigida por
 * el contrato de la ruta (p. ej. un parámetro que debe tener un valor
 * específico), distinta de una violación de {@code @Valid}/{@code @Min} que
 * ya maneja Spring. {@code ManejadorErrores} la traduce en una respuesta 400
 * con título "Datos inválidos" (el mismo que usan las demás respuestas 400
 * del Core).
 */
public class ExcepcionSolicitudInvalida extends RuntimeException {

    public ExcepcionSolicitudInvalida(String mensaje) {
        super(mensaje);
    }
}
