package cl.adndigital.mejorvejez.cuentas;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cuenta en memoria los pedidos de cada clave dentro de una ventana
 * deslizante y dice si el pedido recién registrado queda dentro de {@code
 * maximo}. Es la base de {@link LimitadorIntentos} y {@link
 * LimitadorAltasPorActor}.
 *
 * <p><b>Memoria acotada por clave.</b> La cola de instantes de una clave
 * nunca guarda más de {@code maximo + 1} entradas: antes de agregar el
 * pedido nuevo se descarta el más antiguo si ya hay más de {@code maximo}.
 * Esto no cambia el resultado: un pedido se permite si, contándolo a él,
 * hay como mucho {@code maximo} pedidos dentro de la ventana, y para saberlo
 * basta con conservar los {@code maximo} más recientes; las entradas
 * descartadas son siempre más viejas que ellos, así que tampoco cambian
 * cuándo la clave vuelve a quedar habilitada.
 *
 * <p><b>Atomicidad.</b> La poda, el descarte, el registro y la decisión
 * ocurren dentro de {@link Map#compute} (y la purga dentro de {@link
 * Map#computeIfPresent}), que en {@link ConcurrentHashMap} serializan las
 * llamadas para la misma clave; por eso cada cola puede ser un {@link
 * ArrayDeque} (tamaño en tiempo constante) sin sincronización propia.
 *
 * <p><b>Tope de claves.</b> El mapa admite como mucho {@code maximoClaves}
 * claves distintas. Cuando una clave nueva llegaría con el mapa lleno, se
 * purgan en el acto las claves vencidas; si aun así sigue lleno, el pedido
 * se deja pasar SIN registrarlo, con un log fijo (sin la clave). Rechazarlo
 * dejaría a toda clave nueva sin servicio mientras el mapa esté lleno, y
 * llenarlo bastaría para bloquear la ruta a todo el mundo; la contrapartida
 * aceptada es que, con el mapa lleno, las claves nuevas no quedan limitadas.
 * El tope es aproximado bajo concurrencia (la verificación y el registro no
 * son una sola operación atómica), lo que solo permite excederlo por unas
 * pocas claves.
 *
 * @param <K> tipo de la clave (correo, IP, id de usuario).
 */
final class VentanaDeslizante<K> {

    private static final Logger LOG = LoggerFactory.getLogger(VentanaDeslizante.class);

    private final String nombre;
    private final Clock reloj;
    private final Duration ventana;
    private final int maximo;
    private final int maximoClaves;
    private final Map<K, ArrayDeque<Instant>> pedidosPorClave = new ConcurrentHashMap<>();

    /**
     * @param nombre       descripción fija de lo que se limita, solo para el
     *                     log del mapa lleno (nunca una clave).
     * @param maximo       pedidos permitidos por clave dentro de la ventana.
     * @param maximoClaves claves distintas que se registran como mucho.
     */
    VentanaDeslizante(String nombre, Clock reloj, Duration ventana, int maximo, int maximoClaves) {
        this.nombre = nombre;
        this.reloj = reloj;
        this.ventana = ventana;
        this.maximo = maximo;
        this.maximoClaves = maximoClaves;
    }

    /**
     * Registra un pedido de {@code clave} y dice si queda dentro del
     * máximo; con el mapa lleno (aun después de purgar), lo deja pasar sin
     * registrarlo.
     */
    boolean registrarYVerificar(K clave) {
        if (!pedidosPorClave.containsKey(clave) && pedidosPorClave.size() >= maximoClaves) {
            purgarVencidas();
            if (pedidosPorClave.size() >= maximoClaves) {
                LOG.warn("El limitador de {} llegó a su tope de claves; el pedido pasa sin registrarse.", nombre);
                return true;
            }
        }
        Instant ahora = Instant.now(reloj);
        Instant limiteVentana = ahora.minus(ventana);
        AtomicBoolean permitido = new AtomicBoolean();
        pedidosPorClave.compute(clave, (ignorada, existente) -> {
            ArrayDeque<Instant> instantes = existente != null ? existente : new ArrayDeque<>();
            podar(instantes, limiteVentana);
            if (instantes.size() > maximo) {
                instantes.pollFirst();
            }
            instantes.addLast(ahora);
            permitido.set(instantes.size() <= maximo);
            return instantes;
        });
        return permitido.get();
    }

    /** Quita las claves cuya ventana quedó completamente vencida. */
    void purgarVencidas() {
        Instant limiteVentana = Instant.now(reloj).minus(ventana);
        for (K clave : pedidosPorClave.keySet()) {
            pedidosPorClave.computeIfPresent(clave, (ignorada, instantes) -> {
                podar(instantes, limiteVentana);
                return instantes.isEmpty() ? null : instantes;
            });
        }
    }

    /** Cantidad de claves registradas. */
    int totalDeClaves() {
        return pedidosPorClave.size();
    }

    /** Cantidad de pedidos guardados para {@code clave} (0 si no está); visible para pruebas. */
    int pedidosRegistrados(K clave) {
        AtomicInteger tamanio = new AtomicInteger();
        pedidosPorClave.computeIfPresent(clave, (ignorada, instantes) -> {
            tamanio.set(instantes.size());
            return instantes;
        });
        return tamanio.get();
    }

    private static void podar(ArrayDeque<Instant> instantes, Instant limiteVentana) {
        while (!instantes.isEmpty() && instantes.peekFirst().isBefore(limiteVentana)) {
            instantes.pollFirst();
        }
    }
}
