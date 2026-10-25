package cl.adndigital.mejorvejez.grupos;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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

@WebMvcTest(ControladorGrupos.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorGruposTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioGrupos servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void crearConNombreVacioDevuelve400() throws Exception {
        mockMvc.perform(post("/api/grupos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreGrupo\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nombreGrupo"));
    }

    @Test
    void crearDevuelve201ConLocation() throws Exception {
        RespuestaGrupo creado = new RespuestaGrupo(42L, "Mi familia", true, 1L, "COORDINADOR", OffsetDateTime.now());
        when(servicio.crear("Mi familia")).thenReturn(creado);

        mockMvc.perform(post("/api/grupos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreGrupo\":\"Mi familia\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/grupos/42"))
                .andExpect(jsonPath("$.idGrupo").value(42))
                .andExpect(jsonPath("$.nombreGrupo").value("Mi familia"))
                .andExpect(jsonPath("$.miRol").value("COORDINADOR"));
    }

    @Test
    void crearExigeToken() throws Exception {
        mockMvc.perform(post("/api/grupos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreGrupo\":\"Mi familia\"}"))
                .andExpect(status().isUnauthorized());
    }
}
