package cl.adndigital.mejorvejez.cuentas;

import org.springframework.stereotype.Service;

import cl.adndigital.mejorvejez.comun.ExcepcionSolicitudInvalida;
import cl.adndigital.mejorvejez.grupos.ServicioGrupos;

/**
 * Registro propio (HU-01): crea una cuenta nueva con la contraseña elegida
 * por la persona y, en la misma transacción del alta de la cuenta ({@link
 * ServicioAltaCuentas}), un grupo nuevo con ella como su coordinador. No
 * pide a Alertas el correo de cuenta nueva (la persona ya elige y conoce su
 * propia contraseña). Todavía sin ruta pública.
 */
@Service
public class ServicioRegistro {

    static final int LARGO_MINIMO_CLAVE = 8;
    static final String MENSAJE_CLAVE_CORTA = "La contraseña debe tener al menos 8 caracteres";

    private final ServicioAltaCuentas servicioAltaCuentas;
    private final ServicioGrupos servicioGrupos;

    public ServicioRegistro(ServicioAltaCuentas servicioAltaCuentas, ServicioGrupos servicioGrupos) {
        this.servicioAltaCuentas = servicioAltaCuentas;
        this.servicioGrupos = servicioGrupos;
    }

    /**
     * Crea la cuenta (origen {@link OrigenCuenta#REGISTRO}, sin correo de
     * cuenta nueva, sin actor distinto del propio usuario recién creado) y,
     * en la misma transacción, el grupo {@code nombreGrupo} con ella como
     * integrante COORDINADOR ACTIVO. El resultado no devuelve la
     * contraseña elegida ({@link CuentaCreada#claveTemporal()} es nulo).
     *
     * @throws ExcepcionSolicitudInvalida {@code clave} tiene menos de
     *                                    {@value #LARGO_MINIMO_CLAVE}
     *                                    caracteres.
     */
    public CuentaCreada registrar(String nombre, String email, String clave, String nombreGrupo) {
        if (clave == null || clave.length() < LARGO_MINIMO_CLAVE) {
            throw new ExcepcionSolicitudInvalida(MENSAJE_CLAVE_CORTA);
        }
        AltaCuenta alta = new AltaCuenta(nombre, email, null, OrigenCuenta.REGISTRO, null, clave, false,
                usuarioNuevo -> servicioGrupos.crearConCreador(nombreGrupo, usuarioNuevo));
        return servicioAltaCuentas.crear(alta);
    }
}
