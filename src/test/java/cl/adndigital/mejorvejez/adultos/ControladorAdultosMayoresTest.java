package cl.adndigital.mejorvejez.adultos;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(ControladorAdultosMayores.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorAdultosMayoresTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioAdultosMayores servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void crearSinNombreDevuelve400ConErrorDeCampo() throws Exception {
        mockMvc.perform(post("/api/grupos/1/adultos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nombreCompleto"));
    }

    @Test
    void crearDevuelve201ConLocation() throws Exception {
        RespuestaAdultoMayor creado = respuesta(42L);
        when(servicio.crear(1L, new SolicitudGuardarAdultoMayor(null, "Juana Pérez", null, null, null)))
                .thenReturn(creado);

        mockMvc.perform(post("/api/grupos/1/adultos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreCompleto\":\"Juana Pérez\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/grupos/1/adultos/42"))
                .andExpect(jsonPath("$.idAdultoMayor").value(42));
    }

    @Test
    void listarExigeToken() throws Exception {
        mockMvc.perform(get("/api/grupos/1/adultos"))
                .andExpect(status().isUnauthorized());
    }

    private RespuestaAdultoMayor respuesta(long idAdultoMayor) {
        return new RespuestaAdultoMayor(idAdultoMayor, 1L, "COD-1", "Juana Pérez", null, null, null, 1L,
                OffsetDateTime.now(), null, null);
    }
}
