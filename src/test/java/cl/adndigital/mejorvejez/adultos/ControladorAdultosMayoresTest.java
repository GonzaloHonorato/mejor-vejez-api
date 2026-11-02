package cl.adndigital.mejorvejez.adultos;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                .andExpect(jsonPath("$.errores[?(@.campo == 'nombreCompleto')]").exists())
                .andExpect(jsonPath("$.errores[?(@.campo == 'fechaNacimiento')].mensaje")
                        .value("La fecha de nacimiento es obligatoria."))
                .andExpect(jsonPath("$.errores[?(@.campo == 'contactoPrincipal')].mensaje")
                        .value("El contacto principal es obligatorio."));
    }

    @Test
    void crearConTelefonoDelContactoInvalidoDevuelve400ConCampoAnidado() throws Exception {
        mockMvc.perform(post("/api/grupos/1/adultos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreCompleto\":\"Juana Pérez\",\"fechaNacimiento\":\"1940-01-01\","
                                + "\"contactoPrincipal\":{\"nombre\":\"Pedro\",\"idParentesco\":3,"
                                + "\"telefono\":\"+1 555 123 4567\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("contactoPrincipal.telefono"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("Ingresa un teléfono chileno: +56 y 9 dígitos."));
    }

    @Test
    void actualizarSinFechaDevuelve400() throws Exception {
        mockMvc.perform(put("/api/grupos/1/adultos/42")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreCompleto\":\"Juana Pérez\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("fechaNacimiento"));
    }

    @Test
    void crearDevuelve201ConLocation() throws Exception {
        RespuestaAdultoMayor creado = respuesta(42L);
        when(servicio.crear(1L, new SolicitudCrearAdultoMayor(null, "Juana Pérez", LocalDate.of(1940, 1, 1), null,
                null, new SolicitudContactoPrincipal("Pedro", 3L, "+56912345678"))))
                .thenReturn(creado);

        mockMvc.perform(post("/api/grupos/1/adultos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreCompleto\":\"Juana Pérez\",\"fechaNacimiento\":\"1940-01-01\","
                                + "\"contactoPrincipal\":{\"nombre\":\"Pedro\",\"idParentesco\":3,"
                                + "\"telefono\":\"912345678\"}}"))
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
