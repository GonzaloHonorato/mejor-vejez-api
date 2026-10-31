package cl.adndigital.mejorvejez.cuentas;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limita en memoria las altas de cuentas que hace un mismo actor no
 * administrador (p. ej. un coordinador que crea integrantes con cuenta
 * nueva): como máximo {@value #MAXIMO_ALTAS_POR_ACTOR} dentro de una
 * ventana deslizante de {@value #MINUTOS_VENTANA} minutos por {@code
 * idActor}. Frena que una cuenta comprometida o mal usada cree cuentas en
 * Firebase sin parar. El administrador no pasa por aquí: quien llama decide
 * si el actor está exento.
 *
 * <p>Se cuenta cada intento que llega al alta (después de autorizar y antes
 * de tocar el proveedor de identidad), resulte o no en una cuenta creada,
 * igual que {@link LimitadorIntentos} cuenta los pedidos rechazados: si solo
 * se contaran las altas exitosas, reintentar con correos repetidos
 * consultaría al proveedor sin límite.
 *
 * <p>La ventana es una {@link VentanaDeslizante} (cola por actor acotada,
 * tope de {@value #MAXIMO_CLAVES} actores y, con el mapa lleno, paso sin
 * registrar); las claves vencidas se liberan en {@link
 * #purgarEntradasVencidas}. El reloj se inyecta para que las pruebas puedan
 * fijar la hora.
 */
@Component
public class LimitadorAltasPorActor {

    static final int MAXIMO_ALTAS_POR_ACTOR = 20;
    static final int MINUTOS_VENTANA = 60;
    static final int MAXIMO_CLAVES = 100_000;

    private final VentanaDeslizante<Long> altasPorActor;

    public LimitadorAltasPorActor(Clock reloj) {
        this.altasPorActor = new VentanaDeslizante<>("altas de cuentas por actor", reloj,
                Duration.ofMinutes(MINUTOS_VENTANA), MAXIMO_ALTAS_POR_ACTOR, MAXIMO_CLAVES);
    }

    /** Registra un alta de {@code idActor} y dice si queda dentro del límite. */
    public boolean permitirAlta(long idActor) {
        return altasPorActor.registrarYVerificar(idActor);
    }

    /** Quita los actores cuya ventana quedó completamente vencida. */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void purgarEntradasVencidas() {
        altasPorActor.purgarVencidas();
    }

    /** Cantidad de actores registrados; visible para pruebas. */
    int totalDeClavesRegistradas() {
        return altasPorActor.totalDeClaves();
    }
}
