package cl.adndigital.mejorvejez.catalogos;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;

@WebMvcTest(ControladorCatalogos.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class})
class ControladorCatalogosTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioCatalogos servicioCatalogos;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void opcionesExigenToken() throws Exception {
        mockMvc.perform(get("/api/catalogos/opciones").param("tipo", "UNIDAD_DOSIS"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void opcionesConTokenDevuelvenLista() throws Exception {
        when(servicioCatalogos.listarOpciones(eq(TipoCatalogo.UNIDAD_DOSIS))).thenReturn(List.of(
                new RespuestaOpcion(1L, "COMPRIMIDO", "Comprimido", 1)));

        mockMvc.perform(get("/api/catalogos/opciones").param("tipo", "UNIDAD_DOSIS").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo").value("COMPRIMIDO"));
    }

    @Test
    void tipoInvalidoDevuelve400() throws Exception {
        mockMvc.perform(get("/api/catalogos/opciones").param("tipo", "NO_EXISTE").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(content().string(not(containsString("TipoCatalogo"))));
    }

    @Test
    void estadosExigenToken() throws Exception {
        mockMvc.perform(get("/api/catalogos/estados").param("tabla", "TAREA_CUIDADO").param("campo", "ESTADO"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void estadosConTokenDevuelvenLista() throws Exception {
        when(servicioCatalogos.listarEstados("TAREA_CUIDADO", "ESTADO")).thenReturn(List.of(
                new RespuestaEstado(1L, "PENDIENTE", "Pendiente", 1)));

        mockMvc.perform(get("/api/catalogos/estados")
                        .param("tabla", "TAREA_CUIDADO")
                        .param("campo", "ESTADO")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codigo").value("PENDIENTE"));
    }
}
