package cl.adndigital.mejorvejez.grupos;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

@WebMvcTest(ControladorIntegrantes.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorIntegrantesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioIntegrantes servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void incorporarConCorreoInvalidoDevuelve400() throws Exception {
        mockMvc.perform(post("/api/grupos/1/integrantes")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-es-correo\",\"idRol\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("email"));
    }

    @Test
    void incorporarConEspaciosEnElCorreoSeAcepta() throws Exception {
        RespuestaIntegrante creado = respuesta(42L, 2L);
        when(servicio.incorporar(1L, "ana@ejemplo.cl", 2L)).thenReturn(creado);

        mockMvc.perform(post("/api/grupos/1/integrantes")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" ana@ejemplo.cl \",\"idRol\":2}"))
                .andExpect(status().isCreated());
    }

    @Test
    void listarExigeToken() throws Exception {
        mockMvc.perform(get("/api/grupos/1/integrantes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void incorporarDevuelve201ConLocation() throws Exception {
        RespuestaIntegrante creado = respuesta(42L, 1L);
        when(servicio.incorporar(1L, "persona@ejemplo.cl", 2L)).thenReturn(creado);

        mockMvc.perform(post("/api/grupos/1/integrantes")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"persona@ejemplo.cl\",\"idRol\":2}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/grupos/1/integrantes/42"))
                .andExpect(jsonPath("$.idIntegrante").value(42));
    }

    @Test
    void cambiarRolDevuelve200() throws Exception {
        RespuestaIntegrante actualizado = respuesta(42L, 3L);
        when(servicio.cambiarRol(1L, 42L, 3L)).thenReturn(actualizado);

        mockMvc.perform(put("/api/grupos/1/integrantes/42/rol")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idRol\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idIntegrante").value(42));
    }

    @Test
    void desactivarDevuelve200() throws Exception {
        when(servicio.desactivar(1L, 42L)).thenReturn(respuesta(42L, 1L));

        mockMvc.perform(post("/api/grupos/1/integrantes/42/desactivar").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idIntegrante").value(42));
    }

    @Test
    void reactivarDevuelve200() throws Exception {
        when(servicio.reactivar(1L, 42L)).thenReturn(respuesta(42L, 1L));

        mockMvc.perform(post("/api/grupos/1/integrantes/42/reactivar").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idIntegrante").value(42));
    }

    @Test
    void salirDevuelve204() throws Exception {
        doNothing().when(servicio).salir(1L);

        mockMvc.perform(post("/api/grupos/1/salir").with(jwt()))
                .andExpect(status().isNoContent());
    }

    @Test
    void eliminarDevuelve204() throws Exception {
        doNothing().when(servicio).eliminar(anyLong(), anyLong());

        mockMvc.perform(delete("/api/grupos/1/integrantes/42").with(jwt()))
                .andExpect(status().isNoContent());
    }

    private RespuestaIntegrante respuesta(long idIntegrante, long idRol) {
        return new RespuestaIntegrante(idIntegrante, 7L, "Nombre Prueba", "persona@ejemplo.cl", idRol, "COORDINADOR",
                1L, OffsetDateTime.now(), null);
    }
}
