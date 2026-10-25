package cl.adndigital.mejorvejez.salud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class ServicioSaludTest {

    @Test
    void fechaHoraSaleDelRelojInyectado() throws Exception {
        Instant instanteFijo = Instant.parse("2026-01-15T12:00:00Z");
        DataSource dataSource = mock(DataSource.class);
        Connection conexion = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conexion);
        when(conexion.isValid(2)).thenReturn(true);
        ServicioSalud servicio = new ServicioSalud(dataSource, "mejor-vejez-api", "test",
                Clock.fixed(instanteFijo, ZoneOffset.UTC));

        RespuestaSalud respuesta = servicio.verificar();

        assertThat(respuesta.fechaHora()).isEqualTo(instanteFijo);
        assertThat(respuesta.estado()).isEqualTo("DISPONIBLE");
    }
}
