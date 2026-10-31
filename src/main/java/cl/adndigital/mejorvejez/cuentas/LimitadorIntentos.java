package cl.adndigital.mejorvejez.cuentas;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limita en memoria los pedidos de recuperación de contraseña dentro de una
 * ventana deslizante de {@value #MINUTOS_VENTANA} minutos: como máximo
 * {@value #MAXIMO_POR_CORREO} pedidos por correo y {@value
 * #MAXIMO_POR_IP} por IP. El reloj se inyecta para que las pruebas puedan
 * fijar la hora.
 *
 * <p>Cada pedido queda registrado (permitido o no) en ambos contadores, así
 * que alternar entre correos o IPs distintos no evita el límite del lado
 * que sí se repite. Esto tiene una contrapartida aceptada: un tercero que
 * conozca el correo de otra persona puede agotarle su cupo de {@value
 * #MAXIMO_POR_CORREO} pedidos por hora (desde varias IPs) y así demorarle
 * la recuperación de su propia contraseña; no hay forma de evitarlo sin
 * dejar de contar los pedidos rechazados, lo que a su vez permitiría evadir
 * el límite reintentando sin parar.
 *
 * <p>La ventana de cada lado (correo e IP) es una {@link
 * VentanaDeslizante}: el registro y la decisión son atómicos por clave, la
 * cola de cada clave queda acotada a {@code máximo + 1} instantes y cada
 * mapa tiene su propio tope de {@value #MAXIMO_CLAVES} claves; con un mapa
 * lleno (aun después de purgar las vencidas en el acto), una clave nueva
 * pasa sin registrarse en vez de rechazarse, para que llenar el mapa no
 * bloquee la recuperación de contraseña de todo el mundo. Las claves que
 * quedan vacías se liberan también en {@link #purgarEntradasVencidas}, una
 * barrida periódica; si no se hiciera, una clave usada una sola vez
 * quedaría ocupando memoria hasta que el mapa se llenara.
 *
 * <p>El correo se recorta a {@value #LONGITUD_MAXIMA_CORREO} caracteres
 * antes de usarlo como clave (la misma longitud de {@code USUARIO.EMAIL}):
 * ningún correo real puede ser más largo, así que recortarlo no afecta el
 * límite de una cuenta existente; evita que un correo arbitrariamente largo
 * (enviado por quien pide la recuperación, antes de cualquier validación)
 * quede como clave del mapa.
 */
@Component
public class LimitadorIntentos {

    static final int MAXIMO_POR_CORREO = 3;
    static final int MAXIMO_POR_IP = 10;
    static final int MINUTOS_VENTANA = 60;
    static final int MAXIMO_CLAVES = 100_000;
    static final int LONGITUD_MAXIMA_CORREO = 254;

    private final VentanaDeslizante<String> intentosPorCorreo;
    private final VentanaDeslizante<String> intentosPorIp;

    public LimitadorIntentos(Clock reloj) {
        Duration ventana = Duration.ofMinutes(MINUTOS_VENTANA);
        this.intentosPorCorreo = new VentanaDeslizante<>("recuperación de contraseña por correo", reloj, ventana,
                MAXIMO_POR_CORREO, MAXIMO_CLAVES);
        this.intentosPorIp = new VentanaDeslizante<>("recuperación de contraseña por IP", reloj, ventana,
                MAXIMO_POR_IP, MAXIMO_CLAVES);
    }

    /**
     * Registra un pedido de {@code correo} desde {@code ip} y dice si está
     * dentro de ambos límites. La IP se revisa PRIMERO: si ya está al tope,
     * se rechaza sin tocar el mapa de correos. De lo contrario (la IP
     * registró su pedido y sigue dentro de su propio límite, aunque sea el
     * último que le queda), el correo se registra igual, aunque termine
     * rechazado por su propio límite. Si se revisara el correo primero, una
     * sola IP probando correos al azar llenaría el mapa de correos (hasta su
     * tope de {@value #MAXIMO_CLAVES} claves) antes de que su propio límite
     * por IP la frenara, dejando sin límite por correo a cualquier correo
     * nuevo mientras el mapa siga lleno.
     */
    public boolean permitirIntento(String correo, String ip) {
        if (!intentosPorIp.registrarYVerificar(ip)) {
            return false;
        }
        String claveCorreo = recortar(correo, LONGITUD_MAXIMA_CORREO);
        return intentosPorCorreo.registrarYVerificar(claveCorreo);
    }

    /** Quita de ambos mapas las claves cuya ventana quedó completamente vencida. */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void purgarEntradasVencidas() {
        intentosPorCorreo.purgarVencidas();
        intentosPorIp.purgarVencidas();
    }

    /** Cantidad total de claves vivas en ambos mapas (correo + IP); visible para pruebas. */
    int totalDeClavesRegistradas() {
        return intentosPorCorreo.totalDeClaves() + intentosPorIp.totalDeClaves();
    }

    /** Pedidos guardados para {@code ip}; visible para pruebas. */
    int intentosRegistradosDeIp(String ip) {
        return intentosPorIp.pedidosRegistrados(ip);
    }

    /** Pedidos guardados para {@code correo}; visible para pruebas. */
    int intentosRegistradosDeCorreo(String correo) {
        return intentosPorCorreo.pedidosRegistrados(recortar(correo, LONGITUD_MAXIMA_CORREO));
    }

    private static String recortar(String valor, int maximo) {
        return valor != null && valor.length() > maximo ? valor.substring(0, maximo) : valor;
    }
}
