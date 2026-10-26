package cl.adndigital.mejorvejez.tareas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.tareas.FixturaGeneracion.Detalle;
import cl.adndigital.mejorvejez.tareas.FixturaGeneracion.Escenario;

/**
 * Generador de tareas cruzando el retroceso de otoño de America/Santiago
 * (la noche del sábado 2026-04-04, de −03:00 a −04:00), con el reloj fijo en
 * 2026-04-04 00:05. Vive en su propia clase porque necesita otro {@code
 * Clock} {@code @Primary} y, por lo tanto, otro contexto Spring.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioGeneracionTareasHorarioVeranoTest {

    private static final LocalDate HOY = LocalDate.of(2026, 4, 4);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(0, 5).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioGeneracionTareas servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void generacionCruzaCambioDeHorarioSinCorrerLaHora() {
        FixturaGeneracion fixtura = new FixturaGeneracion(jdbcTemplate);
        Escenario e = fixtura.escenario(LocalDate.of(2026, 4, 1), null);
        long idDetalle = fixtura.crearDetalle(e, new Detalle(HOY));
        OffsetDateTime inicio = ZonedDateTime.of(HOY, LocalTime.MIDNIGHT, ZonaNegocio.ZONA).toOffsetDateTime();
        OffsetDateTime fin = ZonedDateTime.of(HOY.plusDays(7), LocalTime.MIDNIGHT, ZonaNegocio.ZONA)
                .toOffsetDateTime();

        RespuestaEjecucion respuesta = servicio.ejecutar(inicio, fin, e.idGrupo());

        assertThat(respuesta.cantidadCreada()).isEqualTo(7);
        List<OffsetDateTime> horarios = fixtura.horariosTareas(idDetalle);
        assertThat(horarios).hasSize(7);
        assertThat(horarios.get(0).toInstant()).isEqualTo(OffsetDateTime.parse("2026-04-04T08:00-03:00").toInstant());
        assertThat(horarios.get(1).toInstant()).isEqualTo(OffsetDateTime.parse("2026-04-05T08:00-04:00").toInstant());
        assertThat(enSantiago(horarios.get(0)).getOffset()).isEqualTo(ZoneOffset.ofHours(-3));
        assertThat(enSantiago(horarios.get(1)).getOffset()).isEqualTo(ZoneOffset.ofHours(-4));
        assertThat(horarios).allSatisfy(h -> assertThat(enSantiago(h).toLocalTime()).isEqualTo(LocalTime.of(8, 0)));
    }

    private static ZonedDateTime enSantiago(OffsetDateTime horario) {
        return horario.atZoneSameInstant(ZonaNegocio.ZONA);
    }
}
