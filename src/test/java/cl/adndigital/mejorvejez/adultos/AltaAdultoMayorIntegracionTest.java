package cl.adndigital.mejorvejez.adultos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;

/**
 * Alta de un adulto mayor con su contacto principal (D1) y validación del
 * teléfono (D2) y del parentesco (D3), de punta a punta por HTTP contra
 * Oracle real (Testcontainers): Bean Validation, {@code ProblemDetail} con
 * {@code errores} por campo, servicio y transacción.
 *
 * <p>{@link RepositorioContactoEmergencia} es un espía que solo falla al
 * guardar un contacto llamado {@value #NOMBRE_QUE_FALLA}: así se prueba que
 * una falla al crear el contacto revierte también el adulto ya insertado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ConfiguracionTestcontainers.class)
class AltaAdultoMayorIntegracionTest {

    private static final String NOMBRE_QUE_FALLA = "Contacto que falla";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private RepositorioContactoEmergencia repositorioContacto;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private String uid;
    private long idCoordinador;
    private long idGrupo;
    private long idHijo;

    @BeforeEach
    void prepararGrupo() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        uid = FixturaGrupos.uidUnico();
        idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        idHijo = fixturaAdultos.idParentesco("HIJO");
    }

    @Test
    void altaSinFechaDeNacimientoDevuelve400ConErrorDeCampo() throws Exception {
        crear("""
                {"nombreCompleto":"Rosa Díaz",
                 "contactoPrincipal":{"nombre":"Pedro","idParentesco":%d,"telefono":"912345678"}}
                """.formatted(idHijo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errores[0].campo").value("fechaNacimiento"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("La fecha de nacimiento es obligatoria."));

        assertThat(adultosDelGrupo()).isZero();
    }

    @Test
    void altaSinContactoPrincipalDevuelve400ConErrorDeCampo() throws Exception {
        crear("""
                {"nombreCompleto":"Rosa Díaz","fechaNacimiento":"1944-02-01"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("contactoPrincipal"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("El contacto principal es obligatorio."));

        assertThat(adultosDelGrupo()).isZero();
    }

    @Test
    void altaConContactoIncompletoDevuelveUnErrorPorCampo() throws Exception {
        crear("""
                {"nombreCompleto":"Rosa Díaz","fechaNacimiento":"1944-02-01",
                 "contactoPrincipal":{"nombre":" ","telefono":"123"}}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[?(@.campo == 'contactoPrincipal.nombre')].mensaje")
                        .value("El nombre del contacto es obligatorio."))
                .andExpect(jsonPath("$.errores[?(@.campo == 'contactoPrincipal.idParentesco')].mensaje")
                        .value("El parentesco es obligatorio."))
                .andExpect(jsonPath("$.errores[?(@.campo == 'contactoPrincipal.telefono')].mensaje")
                        .value("Ingresa un teléfono chileno: +56 y 9 dígitos."));

        assertThat(adultosDelGrupo()).isZero();
    }

    @Test
    void altaCompletaCreaElAdultoYSuContactoPrincipalConTelefonoNormalizado() throws Exception {
        crear("""
                {"nombreCompleto":"Rosa Díaz","fechaNacimiento":"1944-02-01",
                 "contactoPrincipal":{"nombre":"Pedro Díaz","idParentesco":%d,"telefono":"+56 9 1234-5678"}}
                """.formatted(idHijo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fechaNacimiento").value("1944-02-01"))
                .andExpect(jsonPath("$.contactoPrincipal.nombreContacto").value("Pedro Díaz"))
                .andExpect(jsonPath("$.contactoPrincipal.idParentesco").value(idHijo))
                .andExpect(jsonPath("$.contactoPrincipal.parentesco").value("Hijo"))
                .andExpect(jsonPath("$.contactoPrincipal.telefono").value("+56912345678"))
                .andExpect(jsonPath("$.contactoPrincipal.esPrincipal").value(true));

        assertThat(adultosDelGrupo()).isEqualTo(1);
    }

    @Test
    void altaConParentescoDeOtroCatalogoDevuelve422SinCrearNada() throws Exception {
        long idLunes = jdbcTemplate.queryForObject(
                "SELECT ID_OPCION FROM CATALOGO_NEGOCIO WHERE TIPO_CATALOGO = 'DIA_SEMANA' AND CODIGO = 'LUNES'",
                Long.class);

        crear("""
                {"nombreCompleto":"Rosa Díaz","fechaNacimiento":"1944-02-01",
                 "contactoPrincipal":{"nombre":"Pedro","idParentesco":%d,"telefono":"912345678"}}
                """.formatted(idLunes))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("El parentesco indicado no es válido."));

        assertThat(adultosDelGrupo()).isZero();
    }

    @Test
    void siFallaElContactoNoQuedaElAdulto() throws Exception {
        doThrow(new IllegalStateException("Falla forzada al guardar el contacto"))
                .when(repositorioContacto)
                .save(argThat(contacto -> contacto != null && NOMBRE_QUE_FALLA.equals(contacto.getNombreContacto())));

        crear("""
                {"nombreCompleto":"Adulto revertido","fechaNacimiento":"1944-02-01",
                 "contactoPrincipal":{"nombre":"%s","idParentesco":%d,"telefono":"912345678"}}
                """.formatted(NOMBRE_QUE_FALLA, idHijo))
                .andExpect(status().isInternalServerError());

        assertThat(adultosDelGrupo()).isZero();
        Long eventos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM EVENTO_AUDITORIA WHERE ID_GRUPO = ? AND CODIGO_ACCION = 'CREAR_ADULTO_MAYOR'",
                Long.class, idGrupo);
        assertThat(eventos).isZero();
    }

    @Test
    void edicionSinFechaDeNacimientoDevuelve400() throws Exception {
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");

        mockMvc.perform(put("/api/grupos/{idGrupo}/adultos/{idAdulto}", idGrupo, idAdulto)
                        .with(jwt().jwt(constructor -> constructor.subject(uid)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreCompleto\":\"Adulto fixtura\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("fechaNacimiento"));
    }

    @Test
    void contactoConTelefonoInvalidoDevuelve400YConValidoLoGuardaNormalizado() throws Exception {
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        String ruta = "/api/grupos/{idGrupo}/adultos/{idAdulto}/contactos";

        mockMvc.perform(post(ruta, idGrupo, idAdulto)
                        .with(jwt().jwt(constructor -> constructor.subject(uid)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreContacto\":\"Ana\",\"idParentesco\":%d,\"telefono\":\"+54 9 11 1234 5678\"}"
                                .formatted(idHijo)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("telefono"))
                .andExpect(jsonPath("$.errores[0].mensaje").value("Ingresa un teléfono chileno: +56 y 9 dígitos."));

        mockMvc.perform(post(ruta, idGrupo, idAdulto)
                        .with(jwt().jwt(constructor -> constructor.subject(uid)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombreContacto\":\"Ana\",\"idParentesco\":%d,\"telefono\":\"9 8765 4321\"}"
                                .formatted(idHijo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.telefono").value("+56987654321"))
                .andExpect(jsonPath("$.parentesco").value("Hijo"));
    }

    private ResultActions crear(String cuerpo) throws Exception {
        return mockMvc.perform(post("/api/grupos/{idGrupo}/adultos", idGrupo)
                .with(jwt().jwt(constructor -> constructor.subject(uid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    private long adultosDelGrupo() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ADULTO_MAYOR WHERE ID_GRUPO = ?", Long.class,
                idGrupo);
    }
}
