package cl.adndigital.mejorvejez.auditoria;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * Contrato HTTP de {@link ControladorAuditoria} (HU-12, TSA-52). El servicio
 * es un mock: estas pruebas no tocan la base de datos, solo el enlace de
 * parámetros/JSON, la traducción de excepciones y que no exista ninguna
 * operación de escritura (405 en {@code DELETE}/{@code PUT}), igual que
 * {@code alertas.ControladorAlertasTest}.
 */
@WebMvcTest(ControladorAuditoria.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class,
        ManejadorErroresSeguridad.class})
class ControladorAuditoriaTest {

    private static final String URL_GRUPO = "/api/grupos/1/auditoria";
    private static final String URL_GLOBAL = "/api/auditoria";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioConsultaAuditoria servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------
    // Grupo (GET /api/grupos/{idGrupo}/auditoria)
    // ------------------------------------------------------------------

    @Test
    void listarDelGrupoPasaLosFiltrosAlServicio() throws Exception {
        FiltroConsultaAuditoria filtroEsperado = new FiltroConsultaAuditoria(null, "TAREA_CUIDADO", "7",
                "REASIGNAR_TAREA", "EXITO", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
        when(servicio.consultarGrupo(1L, filtroEsperado, 0, 20))
                .thenReturn(new RespuestaPagina<>(List.of(), 0, 20, 0));

        mockMvc.perform(get(URL_GRUPO).with(jwt())
                        .param("tipoEntidad", "TAREA_CUIDADO")
                        .param("idEntidad", "7")
                        .param("codigoAccion", "REASIGNAR_TAREA")
                        .param("codigoResultado", "EXITO")
                        .param("desde", "2026-11-01")
                        .param("hasta", "2026-11-02"))
                .andExpect(status().isOk());

        verify(servicio).consultarGrupo(1L, filtroEsperado, 0, 20);
    }

    @Test
    void listarDelGrupoTamanoSuperiorA100SeLimitaA100() throws Exception {
        FiltroConsultaAuditoria filtroVacio = new FiltroConsultaAuditoria(null, null, null, null, null, null, null);
        when(servicio.consultarGrupo(1L, filtroVacio, 0, 100))
                .thenReturn(new RespuestaPagina<>(List.of(), 0, 100, 0));

        mockMvc.perform(get(URL_GRUPO).with(jwt()).param("tamano", "500"))
                .andExpect(status().isOk());

        verify(servicio).consultarGrupo(1L, filtroVacio, 0, 100);
    }

    @Test
    void listarDelGrupoPaginaNegativaDevuelve400() throws Exception {
        mockMvc.perform(get(URL_GRUPO).with(jwt()).param("pagina", "-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void listarDelGrupoTamanoMenorA1Devuelve400() throws Exception {
        mockMvc.perform(get(URL_GRUPO).with(jwt()).param("tamano", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void listarDelGrupoExigeToken() throws Exception {
        mockMvc.perform(get(URL_GRUPO)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void listarDelGrupoSinPermisoDevuelve403() throws Exception {
        FiltroConsultaAuditoria filtroVacio = new FiltroConsultaAuditoria(null, null, null, null, null, null, null);
        when(servicio.consultarGrupo(1L, filtroVacio, 0, 20))
                .thenThrow(new ExcepcionAccesoDenegado("Solo un coordinador puede realizar esta acción"));

        mockMvc.perform(get(URL_GRUPO).with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un coordinador puede realizar esta acción"));
    }

    @Test
    void putAlGrupoDevuelve405() throws Exception {
        mockMvc.perform(put(URL_GRUPO).with(jwt())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(servicio);
    }

    @Test
    void deleteAlGrupoDevuelve405() throws Exception {
        mockMvc.perform(delete(URL_GRUPO).with(jwt())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(servicio);
    }

    // ------------------------------------------------------------------
    // Global (GET /api/auditoria)
    // ------------------------------------------------------------------

    @Test
    void listarGlobalPasaElIdGrupoAlServicio() throws Exception {
        FiltroConsultaAuditoria filtroEsperado = new FiltroConsultaAuditoria(3L, null, null, null, null, null, null);
        when(servicio.consultarGlobal(filtroEsperado, 0, 20))
                .thenReturn(new RespuestaPagina<>(List.of(), 0, 20, 0));

        mockMvc.perform(get(URL_GLOBAL).with(jwt()).param("idGrupo", "3"))
                .andExpect(status().isOk());

        verify(servicio).consultarGlobal(filtroEsperado, 0, 20);
    }

    @Test
    void listarGlobalExigeToken() throws Exception {
        mockMvc.perform(get(URL_GLOBAL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void listarGlobalNoAdministradorDevuelve403() throws Exception {
        FiltroConsultaAuditoria filtroVacio = new FiltroConsultaAuditoria(null, null, null, null, null, null, null);
        when(servicio.consultarGlobal(filtroVacio, 0, 20))
                .thenThrow(new ExcepcionAccesoDenegado("Solo un administrador puede realizar esta acción"));

        mockMvc.perform(get(URL_GLOBAL).with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Solo un administrador puede realizar esta acción"));
    }

    @Test
    void putAlGlobalDevuelve405() throws Exception {
        mockMvc.perform(put(URL_GLOBAL).with(jwt())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(servicio);
    }

    @Test
    void deleteAlGlobalDevuelve405() throws Exception {
        mockMvc.perform(delete(URL_GLOBAL).with(jwt())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(servicio);
    }
}
