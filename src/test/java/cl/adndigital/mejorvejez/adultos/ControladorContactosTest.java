package cl.adndigital.mejorvejez.adultos;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                        .content("{\"nombreContacto\":\"Juana Pérez\",\"idParentesco\":3,\"telefono\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("telefono"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("Ingresa un teléfono chileno: +56 y 9 dígitos."));
    }

    @Test
    void sinParentescoNiTelefonoDevuelve400PorCampo() throws Exception {
        mockMvc.perform(post("/api/grupos/1/adultos/2/contactos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreContacto\":\"Juana Pérez\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[?(@.campo == 'idParentesco')].mensaje")
                        .value("El parentesco es obligatorio."))
                .andExpect(jsonPath("$.errores[?(@.campo == 'telefono')].mensaje")
                        .value("El teléfono es obligatorio."));
    }

    @Test
    void actualizarSinParentescoDevuelve400() throws Exception {
        mockMvc.perform(put("/api/grupos/1/adultos/2/contactos/5")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreContacto\":\"Juana Pérez\",\"telefono\":\"912345678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("idParentesco"));
    }

    @Test
    void crearNormalizaElTelefonoAntesDeLlamarAlServicio() throws Exception {
        when(servicio.crear(1L, 2L, new SolicitudCrearContacto("Juana Pérez", 3L, "+56912345678", null)))
                .thenReturn(respuesta(9L, true));

        mockMvc.perform(post("/api/grupos/1/adultos/2/contactos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreContacto\":\"Juana Pérez\",\"idParentesco\":3,"
                                + "\"telefono\":\"9 1234-5678\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idContacto").value(9))
                .andExpect(jsonPath("$.idParentesco").value(3))
                .andExpect(jsonPath("$.parentesco").value("Hijo"));
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
        return new RespuestaContacto(idContacto, 2L, "Contacto de prueba", 3L, "Hijo", "+56912345678", esPrincipal,
                1L, OffsetDateTime.now(), null);
    }
}
