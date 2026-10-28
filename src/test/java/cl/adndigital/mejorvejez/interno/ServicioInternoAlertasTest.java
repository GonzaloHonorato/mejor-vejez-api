package cl.adndigital.mejorvejez.interno;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.adultos.FixturaAdultos;
import cl.adndigital.mejorvejez.alertas.CodigosAlertas;
import cl.adndigital.mejorvejez.alertas.CreadorAlertas;
import cl.adndigital.mejorvejez.alertas.FixturaAlertas;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;
import cl.adndigital.mejorvejez.tareas.RespuestaEjecucion;

/**
 * Prueba las rutas internas de alertas y ejecuciones (Fase 9, TSA-61) contra
 * Oracle real (Testcontainers), con el reloj fijo en el lunes 2026-11-02
 * 10:00 de Santiago (mismo instante que {@code
 * interno.ServicioReservaIntentosTest}, en un rango de ids de usuario
 * propio). Como {@code GET /api/interno/alertas/candidatos-*} no filtra por
 * grupo (la BD es compartida: puede haber candidatas de otras pruebas), cada
 * prueba filtra el resultado por sus propios ids, nunca por tamaño de lista.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioInternoAlertasTest {

    /** Lunes 2026-11-02 10:00 en Santiago (−03:00). */
    private static final OffsetDateTime AHORA = ZonedDateTime
            .of(LocalDate.of(2026, 11, 2), LocalTime.of(10, 0), ZonaNegocio.ZONA).toOffsetDateTime();

    /** "Hoy" en Santiago al momento de {@link #AHORA}. */
    private static final LocalDate HOY = AHORA.atZoneSameInstant(ZonaNegocio.ZONA).toLocalDate();

    /** Ids de usuario explícitos > 127, en un rango propio (distinto del de las otras pruebas de alertas). */
    private static final AtomicLong SIGUIENTE_ID_ALTO = new AtomicLong(982_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(AHORA.toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioInternoAlertas servicio;

    @Autowired
    private ControladorInternoEjecuciones controladorEjecuciones;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaAdultos fixturaAdultos;
    private FixturaTareas fixturaTareas;
    private FixturaPlanes fixturaPlanes;
    private FixturaAlertas fixturaAlertas;

    @BeforeEach
    void preparar() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaAdultos = new FixturaAdultos(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
    }

    // ------------------------------------------------------------------
    // GET candidatos-atraso
    // ------------------------------------------------------------------

    @Test
    void candidatosAtrasoTraeVentanaSinAplicarRegla() {
        Escenario e = escenario();
        OffsetDateTime horario = AHORA.withHour(9).withMinute(50); // 09:50: dentro de la tolerancia (30 min).
        long idTarea = tareaManual(e, horario);

        List<CandidatoAtraso> candidatos = servicio.candidatosAtraso(AHORA.minusHours(2), AHORA);

        CandidatoAtraso candidato = unico(candidatos, idTarea);
        assertThat(candidato.idGrupo()).isEqualTo(e.idGrupo);
        assertThat(candidato.idAdulto()).isEqualTo(e.idAdulto);
        assertThat(candidato.fechaHorario()).isAtSameInstantAs(horario);
        assertThat(candidato.minutosTolerancia()).isEqualTo(30);
        assertThat(candidato.codigoEstado()).isEqualTo(CodigosTareas.ESTADO_TAREA_PENDIENTE);
        assertThat(candidato.generaAlerta()).isTrue();
    }

    @Test
    void candidatosAtrasoExcluyeConAlertaYOrigenInactivo() {
        OffsetDateTime horario = AHORA.minusHours(1);

        Escenario conAlerta = escenario();
        long idTareaConAlerta = tareaManual(conAlerta, horario);
        fixturaAlertas.crearAlertaDeTarea(conAlerta.idGrupo, conAlerta.idAdulto, idTareaConAlerta,
                CodigosAlertas.TIPO_ATRASO_TAREA, CreadorAlertas.claveAtraso(idTareaConAlerta));

        Escenario adultoInactivo = escenario();
        long idTareaAdultoInactivo = tareaManual(adultoInactivo, horario);
        jdbcTemplate.update("UPDATE ADULTO_MAYOR SET ID_ESTADO = ? WHERE ID_ADULTO_MAYOR = ?",
                idEstado("ADULTO_MAYOR", "INACTIVO"), adultoInactivo.idAdulto);

        // candidatosAtraso filtra por ID_ESTADO y GENERA_ALERTA, así que una COMPLETADA y una
        // GENERA_ALERTA='N' tampoco deben venir aunque su origen esté vigente y no tengan alerta previa.
        Escenario completada = escenario();
        long idTareaCompletada = tareaManual(completada, horario);
        fixturaTareas.cambiarEstadoTarea(idTareaCompletada, CodigosTareas.ESTADO_TAREA_COMPLETADA);

        Escenario sinGeneraAlerta = escenario();
        long idTareaSinGeneraAlerta = tareaManual(sinGeneraAlerta, horario);
        jdbcTemplate.update("UPDATE TAREA_CUIDADO SET GENERA_ALERTA = 'N' WHERE ID_TAREA = ?",
                idTareaSinGeneraAlerta);

        Escenario vigente = escenario();
        long idTareaVigente = tareaManual(vigente, horario);

        List<CandidatoAtraso> candidatos = servicio.candidatosAtraso(AHORA.minusHours(2), AHORA);

        assertThat(candidatos).extracting(CandidatoAtraso::idTarea).doesNotContain(idTareaConAlerta,
                idTareaAdultoInactivo, idTareaCompletada, idTareaSinGeneraAlerta);
        unico(candidatos, idTareaVigente);
    }

    // ------------------------------------------------------------------
    // GET candidatos-preventivos
    // ------------------------------------------------------------------

    @Test
    void candidatosPreventivosRespetaHorasYDias() {
        Escenario e = escenario();

        long idCitaDentro = tareaCita(e, AHORA.plusHours(20)); // dentro de 24h.
        long idCitaFuera = tareaCita(e, AHORA.plusHours(30)); // fuera de 24h.

        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Losartán");
        long idDetalleDentro = fixturaPlanes.crearDetalleConTermino(idPlan, idMedicamento, HOY.plusDays(2));
        long idDetalleFuera = fixturaPlanes.crearDetalleConTermino(idPlan, idMedicamento, HOY.plusDays(5));

        CandidatosPreventivos candidatos = servicio.candidatosPreventivos(AHORA, 24, 3);

        assertThat(candidatos.citas()).extracting(CandidatoCita::idTarea).contains(idCitaDentro)
                .doesNotContain(idCitaFuera);
        CandidatoCita cita = candidatos.citas().stream().filter(c -> c.idTarea() == idCitaDentro).findFirst()
                .orElseThrow();
        assertThat(cita.fechaHorario()).isAtSameInstantAs(AHORA.plusHours(20));

        assertThat(candidatos.reposiciones()).extracting(CandidatoReposicion::idDetallePlan)
                .contains(idDetalleDentro).doesNotContain(idDetalleFuera);
        CandidatoReposicion reposicion = candidatos.reposiciones().stream()
                .filter(r -> r.idDetallePlan() == idDetalleDentro).findFirst().orElseThrow();
        assertThat(reposicion.fechaEstimadaTermino()).isEqualTo(HOY.plusDays(2));
    }

    // ------------------------------------------------------------------
    // POST crear alerta
    // ------------------------------------------------------------------

    @Test
    void crearAlertaEsIdempotente() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(8)); // 08:00, pasado el límite (30 min) a las 10:00.
        SolicitudCrearAlerta solicitud = new SolicitudCrearAlerta(CodigosAlertas.TIPO_ATRASO_TAREA, idTarea, null);

        RespuestaCrearAlerta primera = servicio.crearAlerta(solicitud);
        RespuestaCrearAlerta segunda = servicio.crearAlerta(solicitud);

        assertThat(primera.creada()).isTrue();
        assertThat(primera.idAlerta()).isNotNull();
        assertThat(segunda.creada()).isFalse();
        assertThat(segunda.idAlerta()).isEqualTo(primera.idAlerta());
    }

    @Test
    void crearAlertaQueYaNoCorrespondeDevuelveNull() {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(8));
        fixturaTareas.cambiarEstadoTarea(idTarea, CodigosTareas.ESTADO_TAREA_COMPLETADA);

        RespuestaCrearAlerta respuesta = servicio
                .crearAlerta(new SolicitudCrearAlerta(CodigosAlertas.TIPO_ATRASO_TAREA, idTarea, null));

        assertThat(respuesta.creada()).isFalse();
        assertThat(respuesta.idAlerta()).isNull();
    }

    @Test
    void crearAlertaTipoInvalidoDa422() {
        assertThatThrownBy(() -> servicio.crearAlerta(new SolicitudCrearAlerta("TIPO_INEXISTENTE", 1L, null)))
                .isInstanceOf(ExcepcionReglaNegocio.class);
    }

    // ------------------------------------------------------------------
    // Reglas de creación que siguen en el Core
    // (CreadorAlertas#crearCitaProxima / crearReposicion / crearAtraso),
    // sin cobertura propia tras retirar los detectores.
    // ------------------------------------------------------------------

    @Test
    void crearCitaProximaDentroDe24hGeneraAlertaConMedicoYLugar() {
        Escenario e = escenario();
        OffsetDateTime horario = AHORA.plusDays(1).withHour(9).withMinute(0); // martes 09:00 (dentro de 24h)
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario);
        fixturaTareas.crearCita(idTarea, "Dr. Fixtura", "Clínica Fixtura", null);

        RespuestaCrearAlerta respuesta = servicio
                .crearAlerta(new SolicitudCrearAlerta(CodigosAlertas.TIPO_CITA_PROXIMA, idTarea, null));

        assertThat(respuesta.creada()).isTrue();
        List<Map<String, Object>> alertas = fixturaAlertas.alertasDeTarea(idTarea);
        assertThat(alertas).hasSize(1);
        Map<String, Object> alerta = alertas.get(0);
        assertThat(alerta.get("CLAVE_ALERTA")).isEqualTo("CIT-" + idTarea);
        assertThat(alerta.get("TIPO_ALERTA")).isEqualTo(CodigosAlertas.TIPO_CITA_PROXIMA);
        assertThat(alerta.get("MENSAJE_ALERTA")).isEqualTo("Adulto fixtura tiene «Tarea fixtura manual» el "
                + "03-11-2026 a las 09:00. Médico: Dr. Fixtura. Lugar: Clínica Fixtura.");
        assertThat(alerta.get("ID_DETALLE_PLAN")).isNull();
    }

    @Test
    void crearReposicionNoCreaTareas() {
        Escenario e = escenario();
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idDetalle = fixturaPlanes.crearDetalleConTermino(idPlan, fixturaPlanes.crearMedicamento("Losartán"),
                HOY); // término hoy mismo: dentro de la ventana

        RespuestaCrearAlerta respuesta = servicio
                .crearAlerta(new SolicitudCrearAlerta(CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO, null, idDetalle));

        assertThat(respuesta.creada()).isTrue();
        assertThat(fixturaTareas.tareasDelDetalle(idDetalle)).isEmpty();
    }

    @Test
    void cambioDeTerminoGeneraNuevaAlertaDeReposicion() {
        Escenario e = escenario();
        long idPlan = fixturaPlanes.crearPlan(e.idGrupo, e.idAdulto, e.idCoordinador, "ACTIVO");
        long idMedicamento = fixturaPlanes.crearMedicamento("Losartán");
        LocalDate primerTermino = HOY.plusDays(3);
        long idDetalle = fixturaPlanes.crearDetalleConTermino(idPlan, idMedicamento, primerTermino);

        RespuestaCrearAlerta primera = servicio
                .crearAlerta(new SolicitudCrearAlerta(CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO, null, idDetalle));

        LocalDate segundoTermino = HOY.plusDays(1);
        fixturaPlanes.cambiarTermino(idDetalle, segundoTermino);

        RespuestaCrearAlerta segunda = servicio
                .crearAlerta(new SolicitudCrearAlerta(CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO, null, idDetalle));

        assertThat(primera.creada()).isTrue();
        assertThat(segunda.creada()).isTrue();
        assertThat(primera.idAlerta()).isNotEqualTo(segunda.idAlerta());
        List<Map<String, Object>> alertas = fixturaAlertas.alertasDelDetalle(idDetalle);
        assertThat(alertas).hasSize(2);
        assertThat(alertas).extracting(a -> a.get("CLAVE_ALERTA")).containsExactlyInAnyOrder(
                CreadorAlertas.claveReposicion(idDetalle, primerTermino),
                CreadorAlertas.claveReposicion(idDetalle, segundoTermino));
    }

    // Nota: el caso "detalle desactivado entre la proyección del plan (sin bloqueo) y el bloqueo
    // del plan" (regla 5) necesita interceptar RepositorioConsultaAlertas#planDeDetalle, que es de
    // paquete (solo lo usa CreadorAlertas) y además un método de repositorio Spring Data: un spy de
    // interfaz no puede delegar al "método real" con doAnswer/callRealMethod (Mockito no genera un
    // cuerpo invocable para eso). Por eso esa prueba vive en alertas.CreadorAlertasTest, en el mismo
    // paquete que RepositorioConsultaAlertas (una clase concreta, sí espiable con callRealMethod).

    /** Tareas de antes de {@code ahora − 48 h} no generan (regla 2); justo en el borde sí. */
    @Test
    void tareaMasViejaQueLaRetrospectivaNoGenera() {
        Escenario e = escenario();
        long idVieja = tareaManual(e, AHORA.minusHours(48).minusMinutes(1));
        long idEnElBorde = tareaManual(e, AHORA.minusHours(48));

        RespuestaCrearAlerta respuestaVieja = servicio
                .crearAlerta(new SolicitudCrearAlerta(CodigosAlertas.TIPO_ATRASO_TAREA, idVieja, null));
        RespuestaCrearAlerta respuestaEnElBorde = servicio
                .crearAlerta(new SolicitudCrearAlerta(CodigosAlertas.TIPO_ATRASO_TAREA, idEnElBorde, null));

        assertThat(respuestaVieja.creada()).isFalse();
        assertThat(fixturaAlertas.alertasDeTarea(idVieja)).isEmpty();
        assertThat(respuestaEnElBorde.creada()).isTrue();
        assertThat(fixturaAlertas.alertasDeTarea(idEnElBorde)).hasSize(1);
    }

    @Test
    void atrasoConcurrenteNoDuplicaAlerta() throws Exception {
        Escenario e = escenario();
        long idTarea = tareaManual(e, AHORA.withHour(9));
        SolicitudCrearAlerta solicitud = new SolicitudCrearAlerta(CodigosAlertas.TIPO_ATRASO_TAREA, idTarea, null);

        CyclicBarrier barrera = new CyclicBarrier(2);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            List<Future<RespuestaCrearAlerta>> corridas = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                corridas.add(hilos.submit(() -> {
                    barrera.await(30, TimeUnit.SECONDS);
                    return servicio.crearAlerta(solicitud);
                }));
            }
            List<RespuestaCrearAlerta> resultados = new ArrayList<>();
            for (Future<RespuestaCrearAlerta> corrida : corridas) {
                resultados.add(corrida.get(60, TimeUnit.SECONDS));
            }
            assertThat(resultados).extracting(RespuestaCrearAlerta::creada).containsExactlyInAnyOrder(true, false);
            assertThat(resultados.get(0).idAlerta()).isEqualTo(resultados.get(1).idAlerta());
        } finally {
            hilos.shutdownNow();
        }

        assertThat(fixturaAlertas.alertasDeTarea(idTarea)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Ejecuciones
    // ------------------------------------------------------------------

    @Test
    void ejecucionIniciarYFinalizar() {
        SolicitudIniciarEjecucion inicio = new SolicitudIniciarEjecucion(
                CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS, AHORA.minusHours(48), AHORA);

        ResponseEntity<RespuestaIdEjecucion> respuestaInicio = controladorEjecuciones.iniciar(inicio);

        assertThat(respuestaInicio.getStatusCode().value()).isEqualTo(201);
        long idEjecucion = respuestaInicio.getBody().idEjecucion();

        SolicitudFinalizarEjecucion fin = new SolicitudFinalizarEjecucion(CodigosTareas.ESTADO_EJECUCION_EXITOSA, 3,
                0, null);
        RespuestaEjecucion respuesta = controladorEjecuciones.finalizar(idEjecucion, fin);

        assertThat(respuesta.idEjecucion()).isEqualTo(idEjecucion);
        assertThat(respuesta.tipoEjecucion()).isEqualTo(CodigosAlertas.TIPO_EJECUCION_DETECCION_ATRASOS);
        assertThat(respuesta.inicioVentana()).isAtSameInstantAs(AHORA.minusHours(48));
        assertThat(respuesta.finVentana()).isAtSameInstantAs(AHORA);
        assertThat(respuesta.cantidadCreada()).isEqualTo(3);
        assertThat(respuesta.cantidadErrores()).isZero();
        assertThat(respuesta.idEstado()).isEqualTo(idEstado(CodigosTareas.TABLA_EJECUCION_PROCESO,
                CodigosTareas.CAMPO_ESTADO, CodigosTareas.ESTADO_EJECUCION_EXITOSA));
        assertThat(respuesta.fechaFin()).isNotNull();
    }

    // ------------------------------------------------------------------
    // Escenario y ayudantes
    // ------------------------------------------------------------------

    /** Grupo ACTIVO con un coordinador ACTIVO (id > 127) y un adulto ACTIVO. */
    private Escenario escenario() {
        long idCoordinador = insertarUsuarioAlto();
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaAdultos.crearAdulto(idGrupo, idCoordinador, "ACTIVO");
        return new Escenario(idCoordinador, idGrupo, idAdulto);
    }

    private record Escenario(long idCoordinador, long idGrupo, long idAdulto) {
    }

    /** Tarea manual de cita, PENDIENTE, con la tolerancia por defecto (30 min) y GENERA_ALERTA = 'S'. */
    private long tareaManual(Escenario e, OffsetDateTime horario) {
        return fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto, CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS,
                horario);
    }

    private long tareaCita(Escenario e, OffsetDateTime horario) {
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario);
        fixturaTareas.crearCita(idTarea, "Dr. Fixtura", "Clínica Fixtura", null);
        return idTarea;
    }

    private CandidatoAtraso unico(List<CandidatoAtraso> candidatos, long idTarea) {
        List<CandidatoAtraso> filtrados = candidatos.stream().filter(c -> c.idTarea() == idTarea).toList();
        assertThat(filtrados).hasSize(1);
        return filtrados.get(0);
    }

    private long insertarUsuarioAlto() {
        long idUsuario = SIGUIENTE_ID_ALTO.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO USUARIO (ID_USUARIO, UID_FIREBASE, EMAIL, NOMBRE, ES_ADMINISTRADOR, ID_ESTADO) "
                        + "VALUES (?, ?, ?, ?, 'N', (SELECT ID_ESTADO FROM CATALOGO_ESTADOS "
                        + "WHERE NOMBRE_TABLA = 'USUARIO' AND NOMBRE_CAMPO = 'ESTADO' AND CODIGO_ESTADO = 'ACTIVO'))",
                idUsuario, FixturaGrupos.uidUnico(), "fixtura-" + UUID.randomUUID() + "@interno.mejorvejez.test",
                "Usuario " + idUsuario);
        return idUsuario;
    }

    private long idEstado(String tabla, String codigo) {
        return idEstado(tabla, "ESTADO", codigo);
    }

    private long idEstado(String tabla, String campo, String codigo) {
        return jdbcTemplate.queryForObject(
                "SELECT ID_ESTADO FROM CATALOGO_ESTADOS WHERE NOMBRE_TABLA = ? AND NOMBRE_CAMPO = ? "
                        + "AND CODIGO_ESTADO = ?",
                Long.class, tabla, campo, codigo);
    }
}
