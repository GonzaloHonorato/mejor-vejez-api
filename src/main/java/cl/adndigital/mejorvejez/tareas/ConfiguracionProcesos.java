package cl.adndigital.mejorvejez.tareas;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Habilita {@code @Scheduled} para los procesos programados ({@link ProgramadorProcesos}). */
@Configuration
@EnableScheduling
public class ConfiguracionProcesos {
}
