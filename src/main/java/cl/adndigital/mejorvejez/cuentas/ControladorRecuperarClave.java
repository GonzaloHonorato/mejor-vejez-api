package cl.adndigital.mejorvejez.cuentas;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ruta pública (sin token) de recuperación de contraseña: {@code
 * ConfiguracionSeguridad} abre {@code POST /api/publico/**} sin exigir
 * autenticación. Responde 202 sin cuerpo siempre, exista o no una cuenta
 * con el correo recibido; {@link ServicioRecuperarClave} decide el resto.
 *
 * <p>La IP del cliente es {@code request.getRemoteAddr()} sin más: nunca
 * se lee {@code X-Forwarded-For} a mano (ese valor lo escribe el cliente, y
 * leerlo tal cual anularía el límite por IP). {@code
 * server.forward-headers-strategy=native} delega esa reescritura al propio
 * servidor (en Tomcat, su válvula de IP remota), que solo confía en el
 * encabezado cuando la conexión directa viene de una red interna conocida.
 */
@RestController
@RequestMapping("/api/publico")
public class ControladorRecuperarClave {

    private final ServicioRecuperarClave servicioRecuperarClave;

    public ControladorRecuperarClave(ServicioRecuperarClave servicioRecuperarClave) {
        this.servicioRecuperarClave = servicioRecuperarClave;
    }

    @PostMapping("/recuperar-clave")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void recuperarClave(@RequestBody SolicitudRecuperarClave solicitud, HttpServletRequest request) {
        servicioRecuperarClave.solicitar(solicitud.email(), request.getRemoteAddr());
    }
}
