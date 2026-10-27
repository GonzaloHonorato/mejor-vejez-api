package cl.adndigital.mejorvejez.usuarios;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

@WebMvcTest(ControladorUsuarios.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorUsuariosTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServicioAdministracionUsuarios servicio;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void listarExigeToken() throws Exception {
        mockMvc.perform(get("/api/usuarios"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamanoMayorA100SeLimitaA100() throws Exception {
        when(servicio.listar(isNull(), eq(0), eq(100))).thenReturn(new PageImpl<>(List.<Usuario>of()));

        mockMvc.perform(get("/api/usuarios").param("tamano", "500").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamano").value(100));

        verify(servicio).listar(null, 0, 100);
    }

    @Test
    void estadoNuloDevuelve400() throws Exception {
        mockMvc.perform(put("/api/usuarios/1/estado")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("idEstado"));
    }

    @Test
    void paginaNegativaDevuelve400() throws Exception {
        mockMvc.perform(get("/api/usuarios").param("pagina", "-1").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("La página no puede ser negativa."))
                .andExpect(content().string(not(containsString("must be"))));
    }

    @Test
    void tamanoCeroDevuelve400() throws Exception {
        mockMvc.perform(get("/api/usuarios").param("tamano", "0").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"));
    }

    @Test
    void fechasSalenConElOffsetDeSantiagoNoEnZ() throws Exception {
        OffsetDateTime fechaCreacionUtc = OffsetDateTime.parse("2026-10-27T00:23:04.066576Z");
        OffsetDateTime fechaActualizacionUtc = OffsetDateTime.parse("2026-10-27T03:10:05.123456Z");
        Usuario usuario = new Usuario("uid-1", "ana@correo.cl", "Ana", 1L, fechaCreacionUtc);
        ReflectionTestUtils.setField(usuario, "idUsuario", 1L);
        usuario.actualizarPerfil("Ana", null, fechaActualizacionUtc);

        when(servicio.listar(isNull(), eq(0), eq(20))).thenReturn(new PageImpl<>(List.of(usuario)));

        mockMvc.perform(get("/api/usuarios").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].fechaCreacion")
                        .value(ZonaNegocio.enZona(fechaCreacionUtc).toString()))
                .andExpect(jsonPath("$.elementos[0].fechaActualizacion")
                        .value(ZonaNegocio.enZona(fechaActualizacionUtc).toString()))
                .andExpect(jsonPath("$.elementos[0].fechaCreacion", not(containsString("Z"))))
                .andExpect(jsonPath("$.elementos[0].fechaActualizacion", not(containsString("Z"))));
    }
}
