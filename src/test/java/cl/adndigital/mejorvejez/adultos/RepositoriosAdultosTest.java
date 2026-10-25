package cl.adndigital.mejorvejez.adultos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;

/**
 * Prueba las entidades y repositorios del módulo {@code adultos} contra
 * Oracle real (Testcontainers). Los datos los crea {@link FixturaAdultos} /
 * {@link FixturaGrupos} con uids e IDs únicos; quedan confirmados en la base
 * compartida, así que cada prueba filtra siempre por sus propios IDs y nunca
 * cuenta tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class RepositoriosAdultosTest {

    @Autowired
    private RepositorioAdultoMayor repositorioAdultoMayor;

    @Autowired
    private RepositorioContactoEmergencia repositorioContactoEmergencia;

    @Autowired
    private RepositorioHistorialAdulto repositorioHistorialAdulto;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaModelo fixturaModelo;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
    }

    @Test
    void indiceImpideDosPrincipalesActivos() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");

        fixturaAdultos.crearContacto(idAdulto, idCreador, true, "ACTIVO");

        assertThrows(DataIntegrityViolationException.class,
                () -> fixturaAdultos.crearContacto(idAdulto, idCreador, true, "ACTIVO"));

        // Un principal ACTIVO más uno INACTIVO sí se guardan: el índice solo
        // restringe la combinación ES_PRINCIPAL='S' + ID_ESTADO=ACTIVO.
        long idContactoInactivoPrincipal = fixturaAdultos.crearContacto(idAdulto, idCreador, true, "INACTIVO");
        assertThat(idContactoInactivoPrincipal).isPositive();
    }

    @Test
    void bloquearPorIdDevuelveElAdulto() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");

        TransactionTemplate plantillaTransaccion = new TransactionTemplate(gestorTransacciones);

        Optional<AdultoMayor> encontrado =
                plantillaTransaccion.execute(estado -> repositorioAdultoMayor.bloquearPorId(idAdulto));

        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().getIdAdultoMayor()).isEqualTo(idAdulto);
    }

    @Test
    void adultoSinNadaNoTieneReferencias() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");

        assertThat(repositorioHistorialAdulto.adultoTieneReferencias(idAdulto)).isFalse();
    }

    @Test
    void adultoConContactoTieneReferencias() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");
        fixturaAdultos.crearContacto(idAdulto, idCreador, false, "ACTIVO");

        assertThat(repositorioHistorialAdulto.adultoTieneReferencias(idAdulto)).isTrue();
    }

    @Test
    void adultoConEventoDeDesactivacionTieneReferencias() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");

        insertarEventoAuditoria("ADULTO_MAYOR", idAdulto, idGrupo, idCreador, "DESACTIVAR_ADULTO_MAYOR");

        assertThat(repositorioHistorialAdulto.adultoTieneReferencias(idAdulto)).isTrue();
    }

    @Test
    void contactoSinEventosDeEstadoNoTieneHistorial() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCreador, false, "ACTIVO");

        assertThat(repositorioHistorialAdulto.contactoTieneHistorial(idContacto)).isFalse();
    }

    @Test
    void contactoDesactivadoAlgunaVezTieneHistorial() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");
        long idContacto = fixturaAdultos.crearContacto(idAdulto, idCreador, false, "ACTIVO");

        insertarEventoAuditoria("CONTACTO_EMERGENCIA", idContacto, idGrupo, idCreador, "DESACTIVAR_CONTACTO");

        assertThat(repositorioHistorialAdulto.contactoTieneHistorial(idContacto)).isTrue();
    }

    /**
     * Refuerza la decisión de la Tarea 1 (ver ruling del brief): la palabra
     * clave derivada {@code True} no combina con {@code ES_PRINCIPAL}
     * (convertido por {@code ConvertidorSiNo}), así que el método se implementó
     * con {@code @Query} JPQL explícito. Esta prueba confirma que ese JPQL
     * filtra de verdad solo los principales ACTIVOS.
     */
    @Test
    void consultaDePrincipalesActivosFiltraSoloPrincipalesActivos() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCreador, "ACTIVO");
        long idContactoPrincipalActivo = fixturaAdultos.crearContacto(idAdulto, idCreador, true, "ACTIVO");
        fixturaAdultos.crearContacto(idAdulto, idCreador, false, "ACTIVO");
        fixturaAdultos.crearContacto(idAdulto, idCreador, true, "INACTIVO");

        long idEstadoActivo = fixturaModelo.idEstado("CONTACTO_EMERGENCIA", "ESTADO", "ACTIVO");

        List<ContactoEmergencia> principales = repositorioContactoEmergencia
                .findByIdAdultoMayorInAndEsPrincipalTrueAndIdEstado(List.of(idAdulto), idEstadoActivo);

        assertThat(principales).extracting(ContactoEmergencia::getIdContacto)
                .containsExactly(idContactoPrincipalActivo);
    }

    private void insertarEventoAuditoria(String tipoEntidad, long idEntidad, long idGrupo, long idActor,
            String codigoAccion) {
        long idResultadoExito = fixturaModelo.idEstado("EVENTO_AUDITORIA", "RESULTADO", "EXITO");
        jdbcTemplate.update(
                "INSERT INTO EVENTO_AUDITORIA (NOMBRE_SERVICIO, TIPO_ENTIDAD, ID_ENTIDAD, ID_GRUPO, "
                        + "ID_USUARIO_ACTOR, CODIGO_ACCION, ID_RESULTADO) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "adultos", tipoEntidad, String.valueOf(idEntidad), idGrupo, idActor, codigoAccion, idResultadoExito);
    }
}
