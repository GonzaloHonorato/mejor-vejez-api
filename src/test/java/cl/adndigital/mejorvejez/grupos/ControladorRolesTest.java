package cl.adndigital.mejorvejez.grupos;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import cl.adndigital.mejorvejez.comun.ManejadorErrores;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionCors;
import cl.adndigital.mejorvejez.configuracion.ConfiguracionSeguridad;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

@WebMvcTest(ControladorRoles.class)
@Import({ConfiguracionSeguridad.class, ConfiguracionCors.class, ManejadorErrores.class, ManejadorErroresSeguridad.class})
class ControladorRolesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RepositorioRolGrupo repositorioRolGrupo;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rolesDevuelveCoordinadorYColaborador() throws Exception {
        when(repositorioRolGrupo.findAllByOrderByIdRolAsc()).thenReturn(List.of(
                rol(1L, "COORDINADOR", "Administra el grupo"),
                rol(2L, "COLABORADOR", "Participa en el cuidado")));

        mockMvc.perform(get("/api/grupos/roles").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].idRol").value(1))
                .andExpect(jsonPath("$[0].nombreRol").value("COORDINADOR"))
                .andExpect(jsonPath("$[0].descripcion").value("Administra el grupo"))
                .andExpect(jsonPath("$[1].idRol").value(2))
                .andExpect(jsonPath("$[1].nombreRol").value("COLABORADOR"));
    }

    @Test
    void rolesExigeToken() throws Exception {
        mockMvc.perform(get("/api/grupos/roles"))
                .andExpect(status().isUnauthorized());
    }

    private RolGrupo rol(long idRol, String nombreRol, String descripcion) {
        RolGrupo rol = new RolGrupo();
        ReflectionTestUtils.setField(rol, "idRol", idRol);
        ReflectionTestUtils.setField(rol, "nombreRol", nombreRol);
        ReflectionTestUtils.setField(rol, "descripcion", descripcion);
        return rol;
    }
}
