package cl.adndigital.mejorvejez.comun;

import lombok.Getter;

/**
 * Un campo puntual de la solicitud no es válido, pero la regla solo puede
 * aplicarse en el servicio (p. ej. un teléfono que se valida solo si cambió
 * respecto del guardado). {@code ManejadorErrores} la traduce en un 400
 * "Datos inválidos" con {@code errores: [{campo, mensaje}]}, igual que una
 * violación de {@code @Valid}.
 */
@Getter
public class ExcepcionCampoInvalido extends RuntimeException {

    private final String campo;

    public ExcepcionCampoInvalido(String campo, String mensaje) {
        super(mensaje);
        this.campo = campo;
    }
}
