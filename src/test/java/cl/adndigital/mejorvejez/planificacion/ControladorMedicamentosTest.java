package cl.adndigital.mejorvejez.planificacion;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(ControladorMedicamentos.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorMedicamentosTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioMedicamentos servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void crearSinNombreDevuelve400() throws Exception {
        mockMvc.perform(post("/api/grupos/1/medicamentos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idPresentacion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("nombre"));
    }

    @Test
    void buscarExigeToken() throws Exception {
        mockMvc.perform(get("/api/grupos/1/medicamentos"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Sin {@code Location}: no existe un {@code GET} de un medicamento
     * individual (ver findings F3, item 4).
     */
    @Test
    void crearDevuelve201SinLocation() throws Exception {
        RespuestaMedicamento creado = new RespuestaMedicamento(42L, "Paracetamol", null, 1L, null, false);
        when(servicio.crear(1L, new SolicitudCrearMedicamento("Paracetamol", null, 1L, null)))
                .thenReturn(new ServicioMedicamentos.ResultadoMedicamento(creado, true));

        mockMvc.perform(post("/api/grupos/1/medicamentos")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Paracetamol\",\"idPresentacion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.idMedicamento").value(42));
    }
}
