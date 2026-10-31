package cl.adndigital.mejorvejez.cuentas;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Envoltorio sobre el {@link Executor} acotado que usa {@link
 * ServicioRecuperarClave} para la parte lenta del pedido (generar el
 * enlace en Firebase y pedir a Alertas que lo envíe). A propósito NO
 * implementa {@code Executor}: Spring Boot crea su propio executor de
 * tareas ({@code applicationTaskExecutor}, el que usa cualquier {@code
 * @Async} de la aplicación) solo si NINGÚN bean del contexto es de tipo
 * {@code Executor}; si esta clase implementara esa interfaz, el bean de
 * {@code ConfiguracionRecuperarClave} apagaría esa creación para toda la
 * aplicación, no solo para esta ruta. Al envolver el {@code Executor} en
 * vez de serlo, el bean de este tipo queda invisible para esa condición.
 */
public class EjecutorRecuperarClave {

    private final Executor delegado;

    public EjecutorRecuperarClave(Executor delegado) {
        this.delegado = delegado;
    }

    /** @throws RejectedExecutionException la cola del executor delegado está llena. */
    public void ejecutar(Runnable tarea) {
        delegado.execute(tarea);
    }

    /** Si el delegado es un {@link ExecutorService}, lo detiene; si no (p. ej. uno síncrono de prueba), no hace nada. */
    void cerrar() {
        if (delegado instanceof ExecutorService servicio) {
            servicio.shutdown();
        }
    }
}
