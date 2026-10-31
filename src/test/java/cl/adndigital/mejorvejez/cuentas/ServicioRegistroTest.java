package cl.adndigital.mejorvejez.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionSolicitudInvalida;
import cl.adndigital.mejorvejez.interno.ClienteServicios;

/**
 * Prueba {@link ServicioRegistro} contra Oracle real (Testcontainers) con
 * {@link ProveedorIdentidadFalso} (nunca Firebase real).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioRegistroTest {

    @Autowired
    private ServicioRegistro servicioRegistro;

    @Autowired
    private ProveedorIdentidadFalso proveedorFalso;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ClienteServicios clienteServicios;

    @BeforeEach
    void limpiarProveedor() {
        proveedorFalso.limpiar();
    }

    @AfterEach
    void limpiarProveedorDespues() {
        proveedorFalso.limpiar();
    }

    @Test
    void registroCreaCuentaGrupoYCoordinador() {
        String email = correoUnico();

        CuentaCreada creada = servicioRegistro.registrar("Ana Pérez", email, "ClaveElegida1", "Mi grupo");

        assertThat(creada.usuario().getEmail()).isEqualTo(email);
        assertThat(creada.claveTemporal()).isNull();
        assertThat(creada.correoEnviado()).isFalse();
        assertThat(proveedorFalso.claveActualDe(creada.usuario().getUidFirebase())).isEqualTo("ClaveElegida1");

        Long idGrupo = jdbcTemplate.queryForObject(
                "SELECT ID_GRUPO FROM GRUPO_FAMILIAR WHERE ID_USUARIO_CREADOR = ?", Long.class,
                creada.usuario().getIdUsuario());
        assertThat(idGrupo).isNotNull();

        Map<String, Object> integrante = jdbcTemplate.queryForMap(
                "SELECT rg.NOMBRE_ROL FROM INTEGRANTE_GRUPO ig JOIN ROL_GRUPO rg ON rg.ID_ROL = ig.ID_ROL "
                        + "WHERE ig.ID_GRUPO = ? AND ig.ID_USUARIO = ?",
                idGrupo, creada.usuario().getIdUsuario());
        assertThat(integrante.get("NOMBRE_ROL")).isEqualTo("COORDINADOR");

        List<Map<String, Object>> eventosCrearCuenta = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA "
                        + "WHERE TIPO_ENTIDAD = 'USUARIO' AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_CUENTA'",
                String.valueOf(creada.usuario().getIdUsuario()));
        assertThat(eventosCrearCuenta).hasSize(1);
        assertThat(((Number) eventosCrearCuenta.get(0).get("ID_USUARIO_ACTOR")).longValue())
                .isEqualTo(creada.usuario().getIdUsuario());
    }

    @Test
    void registroNoDevuelveLaClaveElegida() {
        CuentaCreada creada = servicioRegistro.registrar("Ana Pérez", correoUnico(), "OtraClaveElegida1", "Mi grupo");

        assertThat(creada.claveTemporal()).isNull();
        assertThat(proveedorFalso.claveActualDe(creada.usuario().getUidFirebase())).isEqualTo("OtraClaveElegida1");
    }

    @Test
    void registroClaveCortaDa400() {
        String email = correoUnico();

        assertThatThrownBy(() -> servicioRegistro.registrar("Ana", email, "corta12", "Mi grupo"))
                .isInstanceOf(ExcepcionSolicitudInvalida.class);

        assertThat(proveedorFalso.buscarPorCorreo(email)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM USUARIO WHERE EMAIL = ?", Long.class, email))
                .isZero();
    }

    private String correoUnico() {
        return "registro-" + UUID.randomUUID() + "@mejorvejez.test";
    }
}
