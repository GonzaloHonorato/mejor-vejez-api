package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.grupos.RepositorioHistorialIntegrante;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.notificaciones.CanalNotificacionUsuario;
import cl.adndigital.mejorvejez.notificaciones.RepositorioCanalNotificacion;
import cl.adndigital.mejorvejez.tareas.RegistroEjecuciones;

/**
 * Prueba las entidades y repositorios del módulo {@code alertas} (y de
 * {@code notificaciones.CanalNotificacionUsuario}) contra Oracle real
 * (Testcontainers). Los datos los crea {@link FixturaAlertas} / {@link
 * FixturaModelo} / {@link FixturaGrupos} con IDs propios; quedan confirmados
 * en la base compartida, así que cada prueba filtra siempre por sus propios
 * IDs y nunca cuenta tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class RepositoriosAlertasTest {

    @Autowired
    private RepositorioAlertaCuidado repositorioAlertaCuidado;

    @Autowired
    private RepositorioIntentoNotificacion repositorioIntentoNotificacion;

    @Autowired
    private RepositorioCanalNotificacion repositorioCanalNotificacion;

    @Autowired
    private RegistroEjecuciones registroEjecuciones;

    @Autowired
    private RepositorioHistorialIntegrante repositorioHistorialIntegrante;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager gestorTransacciones;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaAlertas fixturaAlertas;
    private TransactionTemplate plantillaTransaccion;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
        plantillaTransaccion = new TransactionTemplate(gestorTransacciones);
    }

    @Test
    void guardarYLeerAlertaConservaInstantes() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idTarea = fixturaModelo.crearTareaManual(idGrupo, idAdulto);
        long idEstadoAbierta = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_ABIERTA);

        // Instantes distintos para distinguir detección de programación al releer.
        OffsetDateTime fechaProgramada = OffsetDateTime.parse("2026-11-02T08:00:00-03:00");
        OffsetDateTime ahora = OffsetDateTime.parse("2026-11-02T09:00:00-03:00");

        Long idAlerta = plantillaTransaccion.execute(estado -> repositorioAlertaCuidado
                .save(AlertaCuidado.deTarea(FixturaAlertas.claveUnica(), CodigosAlertas.TIPO_ATRASO_TAREA, idTarea,
                        fechaProgramada, idGrupo, idAdulto, "La tarea sigue sin confirmación.", idEstadoAbierta,
                        ahora))
                .getIdAlerta());

        AlertaCuidado releida =
                plantillaTransaccion.execute(estado -> repositorioAlertaCuidado.findById(idAlerta).orElseThrow());

        assertThat(releida.getFechaProgramada().isEqual(fechaProgramada)).isTrue();
        assertThat(releida.getFechaDeteccion().isEqual(ahora)).isTrue();
    }

    @Test
    void claveDeAlertaRepetidaFalla() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idTarea = fixturaModelo.crearTareaManual(idGrupo, idAdulto);
        long idEstadoAbierta = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        String claveRepetida = FixturaAlertas.claveUnica();
        OffsetDateTime ahora = OffsetDateTime.now();

        repositorioAlertaCuidado.saveAndFlush(AlertaCuidado.deTarea(claveRepetida, CodigosAlertas.TIPO_ATRASO_TAREA,
                idTarea, ahora, idGrupo, idAdulto, "Mensaje uno", idEstadoAbierta, ahora));

        assertThrows(DataIntegrityViolationException.class,
                () -> repositorioAlertaCuidado.saveAndFlush(AlertaCuidado.deTarea(claveRepetida,
                        CodigosAlertas.TIPO_ATRASO_TAREA, idTarea, ahora, idGrupo, idAdulto, "Mensaje dos",
                        idEstadoAbierta, ahora)));
    }

    @Test
    void intentoRepetidoMismoNumeroFalla() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idTarea = fixturaModelo.crearTareaManual(idGrupo, idAdulto);
        long idUsuarioDestinatario = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(idGrupo, idAdulto, idTarea, CodigosAlertas.TIPO_ATRASO_TAREA,
                FixturaAlertas.claveUnica());
        long idEstadoEnviado = idEstadoIntento(CodigosAlertas.ESTADO_ENVIO_ENVIADO);
        OffsetDateTime ahora = OffsetDateTime.now();

        repositorioIntentoNotificacion.saveAndFlush(
                new IntentoNotificacion(idAlerta, idUsuarioDestinatario, "CORREO", 1, idEstadoEnviado, ahora, null,
                        "msg-1@local"));

        assertThrows(DataIntegrityViolationException.class,
                () -> repositorioIntentoNotificacion.saveAndFlush(new IntentoNotificacion(idAlerta,
                        idUsuarioDestinatario, "CORREO", 1, idEstadoEnviado, ahora, null, "msg-2@local")));
    }

    @Test
    void buscarFiltraPorEstadoTipoYAdultoOrdenadoPorDeteccion() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdultoUno = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idAdultoDos = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idTareaUno = fixturaModelo.crearTareaManual(idGrupo, idAdultoUno);
        long idTareaDos = fixturaModelo.crearTareaManual(idGrupo, idAdultoUno);
        long idTareaTres = fixturaModelo.crearTareaManual(idGrupo, idAdultoUno);
        long idTareaCuatro = fixturaModelo.crearTareaManual(idGrupo, idAdultoDos);
        long idEstadoAbierta = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_ABIERTA);
        long idEstadoCerrada = idEstadoAlerta(CodigosAlertas.ESTADO_ALERTA_CERRADA);

        OffsetDateTime t1 = OffsetDateTime.now().minusHours(3);

        // Coincide con los tres filtros (ABIERTA, ATRASO_TAREA, adultoUno): detectada primero.
        Long idCoincide = plantillaTransaccion.execute(estado -> repositorioAlertaCuidado
                .save(AlertaCuidado.deTarea(FixturaAlertas.claveUnica(), CodigosAlertas.TIPO_ATRASO_TAREA, idTareaUno,
                        t1, idGrupo, idAdultoUno, "Mensaje", idEstadoAbierta, t1.plusMinutes(10)))
                .getIdAlerta());
        // Mismo filtro pero CERRADA: no debe salir.
        plantillaTransaccion.executeWithoutResult(estado -> repositorioAlertaCuidado
                .save(AlertaCuidado.deTarea(FixturaAlertas.claveUnica(), CodigosAlertas.TIPO_ATRASO_TAREA, idTareaDos,
                        t1, idGrupo, idAdultoUno, "Mensaje", idEstadoCerrada, t1.plusMinutes(11))));
        // Mismo filtro pero CITA_PROXIMA: no debe salir.
        plantillaTransaccion.executeWithoutResult(estado -> repositorioAlertaCuidado
                .save(AlertaCuidado.deTarea(FixturaAlertas.claveUnica(), CodigosAlertas.TIPO_CITA_PROXIMA,
                        idTareaTres, t1, idGrupo, idAdultoUno, "Mensaje", idEstadoAbierta, t1.plusMinutes(12))));
        // Mismo filtro pero de otro adulto: no debe salir.
        plantillaTransaccion.executeWithoutResult(estado -> repositorioAlertaCuidado
                .save(AlertaCuidado.deTarea(FixturaAlertas.claveUnica(), CodigosAlertas.TIPO_ATRASO_TAREA,
                        idTareaCuatro, t1, idGrupo, idAdultoDos, "Mensaje", idEstadoAbierta, t1.plusMinutes(13))));
        // Coincide con los tres filtros, detectada después: debe salir primero (DESC).
        Long idCoincideMasReciente = plantillaTransaccion.execute(estado -> repositorioAlertaCuidado
                .save(AlertaCuidado.deTarea(FixturaAlertas.claveUnica(), CodigosAlertas.TIPO_ATRASO_TAREA, idTareaUno,
                        t1, idGrupo, idAdultoUno, "Mensaje", idEstadoAbierta, t1.plusMinutes(14)))
                .getIdAlerta());

        Page<AlertaCuidado> pagina = repositorioAlertaCuidado.buscar(idGrupo, idEstadoAbierta,
                CodigosAlertas.TIPO_ATRASO_TAREA, idAdultoUno, PageRequest.of(0, 20));

        assertThat(pagina.getContent()).extracting(AlertaCuidado::getIdAlerta)
                .containsExactly(idCoincideMasReciente, idCoincide);
    }

    @Test
    void detalleErrorConTildesSeTruncaA500Bytes() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idTarea = fixturaModelo.crearTareaManual(idGrupo, idAdulto);
        long idUsuarioDestinatario = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(idGrupo, idAdulto, idTarea, CodigosAlertas.TIPO_ATRASO_TAREA,
                FixturaAlertas.claveUnica());
        long idEstadoFallido = idEstadoIntento(CodigosAlertas.ESTADO_ENVIO_FALLIDO);

        // Cada 'á' ocupa 2 bytes en UTF-8: 400 repeticiones ocupan 800 bytes, por sobre el máximo de 500.
        String detalleLargo = "á".repeat(400);

        Long idIntento = plantillaTransaccion.execute(estado -> repositorioIntentoNotificacion
                .save(new IntentoNotificacion(idAlerta, idUsuarioDestinatario, "CORREO", 1, idEstadoFallido,
                        OffsetDateTime.now(), detalleLargo, null))
                .getIdIntento());

        String detalleGuardado = plantillaTransaccion.execute(
                estado -> repositorioIntentoNotificacion.findById(idIntento).orElseThrow().getDetalleError());

        assertThat(detalleGuardado.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(500);
        // 250 'á' = 500 bytes exactos: el prefijo más largo que cabe.
        assertThat(detalleGuardado).isEqualTo("á".repeat(250));
    }

    @Test
    void canalHabilitadoSeGuardaComoSN() {
        long idUsuario = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        OffsetDateTime ahora = OffsetDateTime.now();

        plantillaTransaccion.executeWithoutResult(estado -> repositorioCanalNotificacion.save(
                new CanalNotificacionUsuario(idUsuario, "CORREO", "destinatario-" + UUID.randomUUID() + "@test.cl",
                        true, ahora)));

        String habilitadoColumna = jdbcTemplate.queryForObject(
                "SELECT HABILITADO FROM CANAL_NOTIFICACION_USUARIO WHERE ID_USUARIO = ? AND CANAL = ?", String.class,
                idUsuario, "CORREO");
        assertThat(habilitadoColumna).isEqualTo("S");

        List<CanalNotificacionUsuario> canales = repositorioCanalNotificacion.findByIdUsuario(idUsuario);
        assertThat(canales).hasSize(1);
        assertThat(canales.get(0).getHabilitado()).isTrue();
    }

    @Test
    void iniciarConTipoRegistraElTipo() {
        OffsetDateTime inicio = OffsetDateTime.now().minusHours(48);
        OffsetDateTime fin = OffsetDateTime.now();

        long idEjecucion = registroEjecuciones.iniciar(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS, inicio, fin);

        String tipoGuardado = jdbcTemplate.queryForObject(
                "SELECT TIPO_EJECUCION FROM EJECUCION_PROCESO WHERE ID_EJECUCION = ?", String.class, idEjecucion);

        assertThat(tipoGuardado).isEqualTo(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS);
    }

    @Test
    void integranteConIntentosTieneHistorial() {
        long idCreador = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        long idGrupo = fixturaGrupos.crearGrupo(idCreador);
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCreador);
        long idTarea = fixturaModelo.crearTareaManual(idGrupo, idAdulto);
        long idIntegrante = fixturaGrupos.crearUsuario(FixturaGrupos.uidUnico(), false);
        fixturaGrupos.agregarIntegrante(idGrupo, idIntegrante, "COLABORADOR", "ACTIVO");
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(idGrupo, idAdulto, idTarea, CodigosAlertas.TIPO_ATRASO_TAREA,
                FixturaAlertas.claveUnica());
        fixturaAlertas.crearIntento(idAlerta, idIntegrante, "CORREO", 1, CodigosAlertas.ESTADO_ENVIO_ENVIADO);

        assertThat(repositorioHistorialIntegrante.tieneHistorial(idGrupo, idIntegrante)).isTrue();
    }

    private long idEstadoAlerta(String codigo) {
        return fixturaModelo.idEstado(CodigosAlertas.TABLA_ALERTA_CUIDADO, CodigosAlertas.CAMPO_ESTADO, codigo);
    }

    private long idEstadoIntento(String codigo) {
        return fixturaModelo.idEstado(CodigosAlertas.TABLA_INTENTO_NOTIFICACION, CodigosAlertas.CAMPO_ESTADO_ENVIO,
                codigo);
    }
}
