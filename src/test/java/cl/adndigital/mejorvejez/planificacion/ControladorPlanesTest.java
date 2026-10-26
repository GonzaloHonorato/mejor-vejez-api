package cl.adndigital.mejorvejez.planificacion;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

@WebMvcTest(ControladorPlanes.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorPlanesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioPlanes servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void crearSinNombreDevuelve400() throws Exception {
        mockMvc.perform(post("/api/grupos/1/planes")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idAdultoMayor\":5,\"vigenteDesde\":\"2024-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nombrePlan"));
    }

    @Test
    void crearDevuelve201ConLocation() throws Exception {
        RespuestaPlan creado = respuesta(42L);
        when(servicio.crear(1L, new SolicitudCrearPlan(5L, "Plan de la mañana", LocalDate.parse("2024-01-01"), null)))
                .thenReturn(creado);

        mockMvc.perform(post("/api/grupos/1/planes")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idAdultoMayor\":5,\"nombrePlan\":\"Plan de la mañana\","
                                + "\"vigenteDesde\":\"2024-01-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/grupos/1/planes/42"))
                .andExpect(jsonPath("$.idPlan").value(42));
    }

    @Test
    void listarExigeToken() throws Exception {
        mockMvc.perform(get("/api/grupos/1/planes"))
                .andExpect(status().isUnauthorized());
    }

    private RespuestaPlan respuesta(long idPlan) {
        return new RespuestaPlan(idPlan, 1L, 5L, "Juana Pérez", "Plan de la mañana", LocalDate.parse("2024-01-01"),
                null, 1L, 0L, OffsetDateTime.now(), null);
    }
}
