package cl.adndigital.mejorvejez.adultos;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@WebMvcTest(ControladorContactos.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorContactosTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioContactos servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void telefonoInvalidoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/grupos/1/adultos/2/contactos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreContacto\":\"Juana Pérez\",\"telefono\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("telefono"));
    }

    @Test
    void desactivarSinCuerpoFunciona() throws Exception {
        RespuestaContacto respuesta = respuesta(5L, false);
        when(servicio.desactivar(eq(1L), eq(2L), eq(5L), isNull())).thenReturn(respuesta);

        mockMvc.perform(post("/api/grupos/1/adultos/2/contactos/5/desactivar").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idContacto").value(5));
    }

    @Test
    void listarExigeToken() throws Exception {
        mockMvc.perform(get("/api/grupos/1/adultos/2/contactos"))
                .andExpect(status().isUnauthorized());
    }

    private RespuestaContacto respuesta(long idContacto, boolean esPrincipal) {
        return new RespuestaContacto(idContacto, 2L, "Contacto de prueba", null, "+56912345678", esPrincipal, 1L,
                OffsetDateTime.now(), null);
    }
}
