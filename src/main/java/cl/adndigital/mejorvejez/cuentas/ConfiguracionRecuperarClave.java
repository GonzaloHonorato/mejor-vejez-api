package cl.adndigital.mejorvejez.cuentas;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link EjecutorRecuperarClave} de producción para la parte lenta de
 * {@link ServicioRecuperarClave} (generar el enlace en Firebase y pedir a
 * Alertas que lo envíe), para que esa espera no retrase el 202 de {@code
 * POST /api/publico/recuperar-clave}.
 *
 * <p>Pocos hilos y una cola chica a propósito: es una ruta pública y sin
 * límite de tamaño se podría agotar memoria con suficientes pedidos; con la
 * cola llena, {@link ThreadPoolExecutor} rechaza la tarea nueva ({@code
 * RejectedExecutionException}, política por omisión) y {@link
 * ServicioRecuperarClave} la descarta con un log fijo, sin afectar el 202
 * ya decidido.
 *
 * <p>El {@link ThreadPoolExecutor} se construye DENTRO de este método, sin
 * exponerlo nunca como bean por sí mismo: solo {@link EjecutorRecuperarClave}
 * (que no es un {@code Executor}) llega al contexto de Spring, por la razón
 * que explica su Javadoc.
 */
@Configuration
public class ConfiguracionRecuperarClave {

    static final int HILOS = 2;
    static final int CAPACIDAD_COLA = 100;

    @Bean(destroyMethod = "cerrar")
    public EjecutorRecuperarClave ejecutorRecuperarClave() {
        ThreadPoolExecutor delegado = new ThreadPoolExecutor(HILOS, HILOS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(CAPACIDAD_COLA), hilosNombrados());
        return new EjecutorRecuperarClave(delegado);
    }

    private static ThreadFactory hilosNombrados() {
        AtomicInteger contador = new AtomicInteger();
        return tarea -> new Thread(tarea, "recuperar-clave-" + contador.incrementAndGet());
    }
}
