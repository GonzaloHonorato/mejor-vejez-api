package cl.adndigital.mejorvejez.planificacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba {@link ServicioPlanes} contra Oracle real (Testcontainers). Los
 * datos los crea {@link FixturaGrupos} / {@link FixturaModelo} / {@link
 * FixturaAdultos} / {@link FixturaPlanes} con ids propios; quedan
 * confirmados en la base compartida, así que cada prueba filtra siempre por
 * sus propios ids y nunca cuenta tablas completas (mismo patrón que
 * {@code adultos.ServicioAdultosMayoresTest}).
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioPlanesTest {

    @Autowired
    private ServicioPlanes servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaAdultos fixturaAdultos;
    private FixturaPlanes fixturaPlanes;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void coordinadorCreaPlanYQuedaAuditado() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        autenticar(uid);

        SolicitudCrearPlan solicitud = new SolicitudCrearPlan(idAdulto, "Plan de la mañana", LocalDate.now(), null);

        RespuestaPlan creado = servicio.crear(idGrupo, solicitud);

        assertThat(creado.idGrupo()).isEqualTo(idGrupo);
        assertThat(creado.idAdultoMayor()).isEqualTo(idAdulto);
        assertThat(creado.nombreAdulto()).isEqualTo("Adulto fixtura");
        assertThat(creado.nombrePlan()).isEqualTo("Plan de la mañana");
        assertThat(creado.idEstado()).isEqualTo(idEstado("PLAN_MEDICAMENTOS", "ACTIVO"));
        assertThat(creado.medicamentosActivos()).isZero();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'PLAN_MEDICAMENTOS' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'CREAR_PLAN'",
                String.valueOf(creado.idPlan()));
        assertThat(eventos).hasSize(1);
        assertThat(((Number) eventos.get(0).get("ID_USUARIO_ACTOR")).longValue()).isEqualTo(idCoordinador);
    }

    @Test
    void crearConAdultoDeOtroGrupoDevuelve404() {
        String uidA = FixturaGrupos.uidUnico();
        long idCoordinadorA = fixturaGrupos.crearUsuario(uidA, false);
        long idGrupoA = fixturaGrupos.crearGrupo(idCoordinadorA);
        fixturaGrupos.agregarIntegrante(idGrupoA, idCoordinadorA, "COORDINADOR", "ACTIVO");

        long idCreadorB = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoB = fixturaGrupos.crearGrupo(idCreadorB);
        long idAdultoB = fixturaModelo.crearAdulto(idGrupoB, idCreadorB);
        autenticar(uidA);

        assertThatThrownBy(() -> servicio.crear(idGrupoA,
                new SolicitudCrearPlan(idAdultoB, "Plan", LocalDate.now(), null)))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
    }

    @Test
    void crearConAdultoInactivoDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "INACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.crear(idGrupo,
                new SolicitudCrearPlan(idAdulto, "Plan", LocalDate.now(), null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El adulto mayor está inactivo; reactívalo para modificarlo");
    }

    @Test
    void vigenciaInvertidaDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        autenticar(uid);

        LocalDate hoy = LocalDate.now();

        assertThatThrownBy(() -> servicio.crear(idGrupo,
                new SolicitudCrearPlan(idAdulto, "Plan", hoy, hoy.minusDays(1))))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("La vigencia del plan no es válida: la fecha de término es anterior a la de inicio");
    }

    @Test
    void acortarVigenciaDejandoDetallesFueraDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        autenticar(uid);

        LocalDate ayer = LocalDate.now().minusDays(1);
        RespuestaPlan creado = servicio.crear(idGrupo, new SolicitudCrearPlan(idAdulto, "Plan", ayer, null));
        long idMedicamento = fixturaPlanes.crearMedicamento("Paracetamol");
        // El detalle fixtura queda con FECHA_INICIO = hoy y FECHA_FIN nula
        // (vigencia abierta): acotar el plan con un vigenteHasta no nulo lo
        // deja fuera, porque una FECHA_FIN nula nunca cabe en un rango acotado.
        fixturaPlanes.crearDetalle(creado.idPlan(), idMedicamento, "ACTIVO");

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, creado.idPlan(),
                new SolicitudActualizarPlan("Plan", ayer, LocalDate.now())))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("Hay medicamentos del plan con vigencia fuera de la nueva vigencia del plan");
    }

    @Test
    void desactivarPlanNoTocaSusDetalles() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Ibuprofeno");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");
        autenticar(uid);

        RespuestaPlan desactivado = servicio.desactivar(idGrupo, idPlan);

        assertThat(desactivado.idEstado()).isEqualTo(idEstado("PLAN_MEDICAMENTOS", "INACTIVO"));
        Long idEstadoDetalle = jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM DETALLE_PLAN WHERE ID_DETALLE_PLAN = ?", Long.class, idDetalle);
        assertThat(idEstadoDetalle).isEqualTo(idEstado("DETALLE_PLAN", "ACTIVO"));
    }

    @Test
    void actualizarPlanInactivoDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "INACTIVO");
        autenticar(uid);

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idPlan,
                new SolicitudActualizarPlan("Plan renombrado", LocalDate.now(), null)))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El plan está inactivo; reactívalo para modificarlo");
    }

    @Test
    void actualizarSinCambiosNoGuardaNiAudita() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        autenticar(uid);

        LocalDate desde = LocalDate.now();
        RespuestaPlan creado = servicio.crear(idGrupo, new SolicitudCrearPlan(idAdulto, "Plan", desde, null));

        RespuestaPlan igual = servicio.actualizar(idGrupo, creado.idPlan(),
                new SolicitudActualizarPlan("Plan", desde, null));

        assertThat(igual.fechaActualizacion()).isNull();
        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'PLAN_MEDICAMENTOS' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ACTUALIZAR_PLAN'",
                String.valueOf(creado.idPlan()));
        assertThat(eventos).isEmpty();
    }

    @Test
    void desactivarPlanYaInactivoDevuelve409() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");
        autenticar(uid);

        servicio.desactivar(idGrupo, idPlan);

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idPlan))
                .isInstanceOf(ExcepcionConflicto.class)
                .hasMessage("El plan ya está inactivo");
    }

    @Test
    void planDeOtroGrupoDevuelve404() {
        long idCreadorA = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoA = fixturaGrupos.crearGrupo(idCreadorA);
        long idAdultoA = fixturaModelo.crearAdulto(idGrupoA, idCreadorA);
        long idPlanA = fixturaPlanes.crearPlan(idGrupoA, idAdultoA, idCreadorA, "ACTIVO");

        String uidB = FixturaGrupos.uidUnico();
        long idCoordinadorB = fixturaGrupos.crearUsuario(uidB, false);
        long idGrupoB = fixturaGrupos.crearGrupo(idCoordinadorB);
        fixturaGrupos.agregarIntegrante(idGrupoB, idCoordinadorB, "COORDINADOR", "ACTIVO");
        autenticar(uidB);

        assertThatThrownBy(() -> servicio.obtener(idGrupoB, idPlanA))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El plan no existe");
    }

    @Test
    void colaboradorPuedeConsultarPeroNoModificar() {
        String uidCoordinador = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uidCoordinador, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");

        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        List<RespuestaPlan> listado = servicio.listar(idGrupo, null, false);
        assertThat(listado).extracting(RespuestaPlan::idPlan).contains(idPlan);

        RespuestaPlanDetallado obtenido = servicio.obtener(idGrupo, idPlan);
        assertThat(obtenido.plan().idPlan()).isEqualTo(idPlan);

        assertThatThrownBy(() -> servicio.crear(idGrupo,
                new SolicitudCrearPlan(idAdulto, "Plan nuevo", LocalDate.now(), null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);

        assertThatThrownBy(() -> servicio.actualizar(idGrupo, idPlan,
                new SolicitudActualizarPlan("Otro nombre", LocalDate.now(), null)))
                .isInstanceOf(ExcepcionAccesoDenegado.class);

        assertThatThrownBy(() -> servicio.desactivar(idGrupo, idPlan)).isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    @Test
    void eliminarPlanSinReferenciasBorraPlanYDetalles() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Losartan");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");
        autenticar(uid);

        servicio.eliminar(idGrupo, idPlan);

        assertThat(contar("PLAN_MEDICAMENTOS", "ID_PLAN", idPlan)).isZero();
        assertThat(contar("DETALLE_PLAN", "ID_DETALLE_PLAN", idDetalle)).isZero();
        assertThat(contar("DETALLE_PLAN_DIA", "ID_DETALLE_PLAN", idDetalle)).isZero();
        assertThat(contar("DETALLE_PLAN_HORARIO", "ID_DETALLE_PLAN", idDetalle)).isZero();

        List<Map<String, Object>> eventos = jdbcTemplate.queryForList(
                "SELECT ID_USUARIO_ACTOR FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'PLAN_MEDICAMENTOS' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'ELIMINAR_PLAN'",
                String.valueOf(idPlan));
        assertThat(eventos).hasSize(1);
    }

    @Test
    void eliminarPlanConTareaDevuelve422() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Metformina");
        long idDetalle = fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");
        fixturaPlanes.crearTareaDeMedicamento(idGrupo, idAdulto, idDetalle, OffsetDateTime.now());
        autenticar(uid);

        assertThatThrownBy(() -> servicio.eliminar(idGrupo, idPlan))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El plan tiene historial o referencias; desactívalo en su lugar");

        assertThat(contar("PLAN_MEDICAMENTOS", "ID_PLAN", idPlan)).isEqualTo(1);
    }

    @Test
    void listarFiltraPorAdultoYOrdena() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdultoUno = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idAdultoDos = fixturaModelo.crearAdulto(idGrupo, idCoordinador);

        long idPlanUno = fixturaPlanes.crearPlan(idGrupo, idAdultoUno, idCoordinador, "ACTIVO");
        long idPlanDos = fixturaPlanes.crearPlan(idGrupo, idAdultoUno, idCoordinador, "ACTIVO");
        fixturaPlanes.crearPlan(idGrupo, idAdultoDos, idCoordinador, "ACTIVO");
        autenticar(uid);

        List<RespuestaPlan> deAdultoUno = servicio.listar(idGrupo, idAdultoUno, false);

        assertThat(deAdultoUno).extracting(RespuestaPlan::idPlan).containsExactly(idPlanDos, idPlanUno);
        assertThat(deAdultoUno).allMatch(plan -> plan.nombreAdulto() != null);
    }

    @Test
    void administradorSinMembresiaQuedaTrazado() {
        String uidAdmin = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAdmin, true);
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCreador, "ACTIVO");
        autenticar(uidAdmin);

        servicio.desactivar(idGrupo, idPlan);

        String detallesJson = jdbcTemplate.queryForObject(
                "SELECT DETALLES_JSON FROM EVENTO_AUDITORIA WHERE TIPO_ENTIDAD = 'PLAN_MEDICAMENTOS' "
                        + "AND ID_ENTIDAD = ? AND CODIGO_ACCION = 'DESACTIVAR_PLAN'",
                String.class, String.valueOf(idPlan));
        Map<String, Object> detalles = JsonMapper.builder().build()
                .readValue(detallesJson, new TypeReference<Map<String, Object>>() {
                });
        assertThat(detalles.get("accesoAdministrador")).isEqualTo(true);
    }

    @Test
    void listarCuentaSoloLosMedicamentosActivos() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idPlan = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Atorvastatina");
        fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");
        fixturaPlanes.crearDetalle(idPlan, idMedicamento, "ACTIVO");
        fixturaPlanes.crearDetalle(idPlan, idMedicamento, "INACTIVO");
        autenticar(uid);

        List<RespuestaPlan> listado = servicio.listar(idGrupo, idAdulto, false);

        assertThat(listado).singleElement().satisfies(plan -> {
            assertThat(plan.idPlan()).isEqualTo(idPlan);
            assertThat(plan.medicamentosActivos()).isEqualTo(2L);
        });
    }

    @Test
    void listarConInactivosPoneLosActivosPrimero() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        long idActivoAntiguo = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");
        long idInactivo = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "INACTIVO");
        long idActivoReciente = fixturaPlanes.crearPlan(idGrupo, idAdulto, idCoordinador, "ACTIVO");
        autenticar(uid);

        List<RespuestaPlan> soloActivos = servicio.listar(idGrupo, idAdulto, false);
        List<RespuestaPlan> todos = servicio.listar(idGrupo, idAdulto, true);

        assertThat(soloActivos).extracting(RespuestaPlan::idPlan).containsExactly(idActivoReciente, idActivoAntiguo);
        assertThat(todos).extracting(RespuestaPlan::idPlan)
                .containsExactly(idActivoReciente, idActivoAntiguo, idInactivo);
    }

    @Test
    void renombrarSinCambiarLaVigenciaNoRevisaLosDetalles() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        autenticar(uid);

        LocalDate desde = LocalDate.now().minusDays(1);
        LocalDate hasta = LocalDate.now().plusDays(30);
        RespuestaPlan creado = servicio.crear(idGrupo, new SolicitudCrearPlan(idAdulto, "Plan", desde, hasta));
        // Detalle preexistente con FECHA_FIN nula: ya está fuera de una
        // vigencia acotada. Renombrar el plan (sin tocar la vigencia) no debe
        // revisar los detalles ni rechazar el cambio.
        long idMedicamento = fixturaPlanes.crearMedicamento("Enalapril");
        fixturaPlanes.crearDetalle(creado.idPlan(), idMedicamento, "ACTIVO");

        RespuestaPlan renombrado = servicio.actualizar(idGrupo, creado.idPlan(),
                new SolicitudActualizarPlan("Plan renombrado", desde, hasta));

        assertThat(renombrado.nombrePlan()).isEqualTo("Plan renombrado");
    }

    @Test
    void crearConAdultoDeOtroGrupoYVigenciaInvertidaDevuelve404() {
        String uidA = FixturaGrupos.uidUnico();
        long idCoordinadorA = fixturaGrupos.crearUsuario(uidA, false);
        long idGrupoA = fixturaGrupos.crearGrupo(idCoordinadorA);
        fixturaGrupos.agregarIntegrante(idGrupoA, idCoordinadorA, "COORDINADOR", "ACTIVO");

        long idCreadorB = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupoB = fixturaGrupos.crearGrupo(idCreadorB);
        long idAdultoB = fixturaModelo.crearAdulto(idGrupoB, idCreadorB);
        autenticar(uidA);

        LocalDate hoy = LocalDate.now();

        // El adulto se resuelve antes que la regla de vigencia: un adulto
        // ajeno responde 404 aunque la vigencia también sea inválida.
        assertThatThrownBy(() -> servicio.crear(idGrupoA,
                new SolicitudCrearPlan(idAdultoB, "Plan", hoy, hoy.minusDays(1))))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("El adulto mayor no existe");
    }

    private Long contar(String tabla, String columnaId, long id) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tabla + " WHERE " + columnaId + " = ?", Long.class, id);
    }

    private long idEstado(String tabla, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = ?",
                Long.class, tabla, codigo);
    }

    private void autenticar(String uid) {
        Jwt jwt = Jwt.withTokenValue("token-de-prueba")
                .header("alg", "RS256")
                .subject(uid)
                .claim("email", uid + "@ejemplo.cl")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
