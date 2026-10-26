package cl.adndigital.mejorvejez.alertas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.adndigital.mejorvejez.ConfiguracionTestcontainers;
import cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.FixturaGrupos;
import cl.adndigital.mejorvejez.modelo.FixturaModelo;
import cl.adndigital.mejorvejez.planificacion.FixturaPlanes;
import cl.adndigital.mejorvejez.tareas.CodigosTareas;
import cl.adndigital.mejorvejez.tareas.FixturaTareas;

/**
 * Prueba {@link ServicioConsultaAlertas} contra Oracle real (Testcontainers),
 * con el reloj de negocio fijo en el lunes 2026-11-02 10:00
 * (America/Santiago), igual que {@code tareas.ServicioConsultaTareasTest}.
 * Datos propios por prueba; nunca se cuentan tablas completas.
 */
@SpringBootTest
@Import(ConfiguracionTestcontainers.class)
class ServicioConsultaAlertasTest {

    /** Lunes. */
    private static final LocalDate HOY = LocalDate.of(2026, 11, 2);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelojFijo {
        @Bean
        @Primary
        Clock relojFijoDePrueba() {
            return Clock.fixed(HOY.atTime(10, 0).atZone(ZonaNegocio.ZONA).toInstant(), ZonaNegocio.ZONA);
        }
    }

    @Autowired
    private ServicioConsultaAlertas servicio;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private FixturaGrupos fixturaGrupos;
    private FixturaModelo fixturaModelo;
    private FixturaTareas fixturaTareas;
    private FixturaPlanes fixturaPlanes;
    private FixturaAlertas fixturaAlertas;

    @BeforeEach
    void prepararFixturas() {
        fixturaGrupos = new FixturaGrupos(jdbcTemplate);
        fixturaModelo = new FixturaModelo(jdbcTemplate);
        fixturaTareas = new FixturaTareas(jdbcTemplate);
        fixturaPlanes = new FixturaPlanes(jdbcTemplate);
        fixturaAlertas = new FixturaAlertas(jdbcTemplate);
    }

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Bandeja: filtros, orden y paginación (regla 18 de la Fase 5)
    // ------------------------------------------------------------------

    @Test
    void bandejaFiltraPorEstadoTipoYAdultoYPagina() {
        Escenario e = escenario();
        long idAdultoDos = fixturaModelo.crearAdulto(e.idGrupo, e.idCoordinador);

        long idAtraso = crearAlertaConFecha(e.idGrupo, e.idAdulto, CodigosAlertas.TIPO_ATRASO_TAREA, "ABIERTA",
                fecha(10, 0));
        long idCita = crearAlertaConFecha(e.idGrupo, e.idAdulto, CodigosAlertas.TIPO_CITA_PROXIMA, "ABIERTA",
                fecha(10, 1));
        long idReposicion = crearAlertaConFecha(e.idGrupo, e.idAdulto, CodigosAlertas.TIPO_REPOSICION_MEDICAMENTO,
                "ABIERTA", fecha(10, 2));
        long idCerrada = crearAlertaConFecha(e.idGrupo, e.idAdulto, CodigosAlertas.TIPO_ATRASO_TAREA, "CERRADA",
                fecha(10, 3));
        long idDeOtroAdulto = crearAlertaConFecha(e.idGrupo, idAdultoDos, CodigosAlertas.TIPO_ATRASO_TAREA, "ABIERTA",
                fecha(10, 4));

        // Sin filtros: las 5 del grupo, ordenadas por FECHA_DETECCION descendente.
        RespuestaPagina<RespuestaAlerta> sinFiltro = servicio.listar(e.idGrupo, null, null, null, 0, 20);
        assertThat(sinFiltro.total()).isEqualTo(5);
        assertThat(sinFiltro.elementos()).extracting(RespuestaAlerta::idAlerta)
                .containsExactly(idDeOtroAdulto, idCerrada, idReposicion, idCita, idAtraso);

        // Por estado ABIERTA: excluye la cerrada.
        long idEstadoAbierta = idEstado("ALERTA_CUIDADO", "ABIERTA");
        RespuestaPagina<RespuestaAlerta> porEstado = servicio.listar(e.idGrupo, idEstadoAbierta, null, null, 0, 20);
        assertThat(porEstado.elementos()).extracting(RespuestaAlerta::idAlerta)
                .containsExactly(idDeOtroAdulto, idReposicion, idCita, idAtraso);

        // Por tipo ATRASO_TAREA: solo esa, de cualquier estado.
        RespuestaPagina<RespuestaAlerta> porTipo = servicio.listar(e.idGrupo, null,
                CodigosAlertas.TIPO_ATRASO_TAREA, null, 0, 20);
        assertThat(porTipo.elementos()).extracting(RespuestaAlerta::idAlerta)
                .containsExactly(idDeOtroAdulto, idCerrada, idAtraso);

        // Por adulto: excluye la del segundo adulto.
        RespuestaPagina<RespuestaAlerta> porAdulto = servicio.listar(e.idGrupo, null, null, e.idAdulto, 0, 20);
        assertThat(porAdulto.elementos()).extracting(RespuestaAlerta::idAlerta)
                .containsExactly(idCerrada, idReposicion, idCita, idAtraso);
        assertThat(porAdulto.total()).isEqualTo(4);

        // Un adulto de otro grupo: lista vacía, no un error.
        Escenario ajeno = escenario();
        autenticar(e.uid);
        RespuestaPagina<RespuestaAlerta> deAdultoAjeno = servicio.listar(e.idGrupo, null, null, ajeno.idAdulto, 0,
                20);
        assertThat(deAdultoAjeno.elementos()).isEmpty();
        assertThat(deAdultoAjeno.total()).isZero();

        // Paginación: tamaño 2, página 0 y 1; el total refleja todo el filtro, no la página.
        RespuestaPagina<RespuestaAlerta> paginaUno = servicio.listar(e.idGrupo, null, null, null, 0, 2);
        assertThat(paginaUno.elementos()).extracting(RespuestaAlerta::idAlerta)
                .containsExactly(idDeOtroAdulto, idCerrada);
        assertThat(paginaUno.total()).isEqualTo(5);
        assertThat(paginaUno.pagina()).isZero();
        assertThat(paginaUno.tamano()).isEqualTo(2);
        RespuestaPagina<RespuestaAlerta> paginaDos = servicio.listar(e.idGrupo, null, null, null, 1, 2);
        assertThat(paginaDos.elementos()).extracting(RespuestaAlerta::idAlerta)
                .containsExactly(idReposicion, idCita);
        assertThat(paginaDos.total()).isEqualTo(5);
    }

    @Test
    void idEstadoDeOtroDominioDa422() {
        Escenario e = escenario();
        long idEstadoDeOtroDominio = idEstado("TAREA_CUIDADO", "PENDIENTE");

        assertThatThrownBy(() -> servicio.listar(e.idGrupo, idEstadoDeOtroDominio, null, null, 0, 20))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El estado indicado no corresponde a ALERTA_CUIDADO.ESTADO");
    }

    @Test
    void tipoInexistenteDa422() {
        Escenario e = escenario();

        assertThatThrownBy(() -> servicio.listar(e.idGrupo, null, "NO_EXISTE", null, 0, 20))
                .isInstanceOf(ExcepcionReglaNegocio.class)
                .hasMessage("El tipo de alerta no existe.");
    }

    @Test
    void noMiembroDa403() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(e.idGrupo, e.idAdulto, idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        String uidAjeno = FixturaGrupos.uidUnico();
        fixturaGrupos.crearUsuario(uidAjeno, false);
        autenticar(uidAjeno);

        assertThatThrownBy(() -> servicio.listar(e.idGrupo, null, null, null, 0, 20))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
        assertThatThrownBy(() -> servicio.obtener(e.idGrupo, idAlerta))
                .isInstanceOf(ExcepcionAccesoDenegado.class);
    }

    // ------------------------------------------------------------------
    // Detalle: alerta de otro grupo, destinos y detalleError (regla 14)
    // ------------------------------------------------------------------

    @Test
    void alertaDeOtroGrupoDa404() {
        Escenario propio = escenario();
        Escenario ajeno = escenario();
        long idTareaAjena = fixturaTareas.crearTareaManual(ajeno.idGrupo, ajeno.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        long idAlertaAjena = fixturaAlertas.crearAlertaDeTarea(ajeno.idGrupo, ajeno.idAdulto, idTareaAjena,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        autenticar(propio.uid);

        assertThatThrownBy(() -> servicio.obtener(propio.idGrupo, idAlertaAjena))
                .isInstanceOf(ExcepcionNoEncontrado.class)
                .hasMessage("La alerta no existe");
    }

    @Test
    void colaboradorNoVeDetalleErrorYCoordinadorSi() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(e.idGrupo, e.idAdulto, idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        fixturaAlertas.crearIntento(idAlerta, e.idCoordinador, "CORREO", 1, "FALLIDO");
        jdbcTemplate.update("UPDATE INTENTO_NOTIFICACION SET DETALLE_ERROR = ? WHERE ID_ALERTA = ?",
                "El envío de correo no está configurado.", idAlerta);

        String uidColaborador = FixturaGrupos.uidUnico();
        long idColaborador = fixturaGrupos.crearUsuario(uidColaborador, false);
        fixturaGrupos.agregarIntegrante(e.idGrupo, idColaborador, "COLABORADOR", "ACTIVO");
        autenticar(uidColaborador);

        RespuestaAlertaDetallada paraColaborador = servicio.obtener(e.idGrupo, idAlerta);
        assertThat(paraColaborador.intentos()).hasSize(1);
        assertThat(paraColaborador.intentos().get(0).detalleError()).isNull();

        autenticar(e.uid);
        RespuestaAlertaDetallada paraCoordinador = servicio.obtener(e.idGrupo, idAlerta);
        assertThat(paraCoordinador.intentos().get(0).detalleError()).isEqualTo("El envío de correo no está configurado.");
    }

    @Test
    void respuestaNoIncluyeDestinos() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(e.idGrupo, e.idAdulto, idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        String correoSecreto = "destino-secreto-" + idAlerta + "@no-debe-salir.test";
        fixturaAlertas.crearCanal(e.idCoordinador, "CORREO", correoSecreto, true);
        fixturaAlertas.crearIntento(idAlerta, e.idCoordinador, "CORREO", 1, "ENVIADO");

        RespuestaAlertaDetallada detalle = servicio.obtener(e.idGrupo, idAlerta);

        assertThat(detalle.toString()).doesNotContain(correoSecreto);
        assertThat(detalle.intentos()).hasSize(1);
        assertThat(detalle.intentos().get(0).canal()).isEqualTo("CORREO");
    }

    // ------------------------------------------------------------------
    // Instantes en el offset de Santiago (ruling final de F4, reutilizado)
    // ------------------------------------------------------------------

    @Test
    void instantesConOffsetDeSantiago() {
        Escenario e = escenario();
        long idTarea = fixturaTareas.crearTareaManual(e.idGrupo, e.idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(e.idGrupo, e.idAdulto, idTarea,
                CodigosAlertas.TIPO_ATRASO_TAREA, FixturaAlertas.claveUnica());
        // Se guarda con offset UTC (+00:00); la respuesta debe convertirlo a Santiago (-03:00).
        OffsetDateTime fechaDeteccionUtc = OffsetDateTime.of(2026, 11, 2, 13, 0, 0, 0, ZoneOffset.UTC);
        jdbcTemplate.update("UPDATE ALERTA_CUIDADO SET FECHA_DETECCION = ? WHERE ID_ALERTA = ?",
                fechaDeteccionUtc, idAlerta);

        RespuestaAlertaDetallada detalle = servicio.obtener(e.idGrupo, idAlerta);

        assertThat(detalle.alerta().fechaDeteccion()).isEqualTo(
                OffsetDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZoneOffset.of("-03:00")));
        assertThat(detalle.alerta().fechaDeteccion().getOffset()).isEqualTo(ZoneOffset.of("-03:00"));
    }

    // ------------------------------------------------------------------
    // Ayudantes
    // ------------------------------------------------------------------

    private Escenario escenario() {
        String uid = FixturaGrupos.uidUnico();
        long idCoordinador = fixturaGrupos.crearUsuario(uid, false);
        long idGrupo = fixturaGrupos.crearGrupo(idCoordinador);
        fixturaGrupos.agregarIntegrante(idGrupo, idCoordinador, "COORDINADOR", "ACTIVO");
        long idAdulto = fixturaModelo.crearAdulto(idGrupo, idCoordinador);
        autenticar(uid);
        return new Escenario(uid, idCoordinador, idGrupo, idAdulto);
    }

    private record Escenario(String uid, long idCoordinador, long idGrupo, long idAdulto) {
    }

    private OffsetDateTime fecha(int hora, int minuto) {
        return HOY.atTime(hora, minuto).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
    }

    /** Crea una alerta nacida de una tarea manual del adulto indicado, con la fecha de detección exacta dada. */
    private long crearAlertaConFecha(long idGrupo, long idAdulto, String tipoAlerta, String estado,
            OffsetDateTime fechaDeteccion) {
        long idTarea = fixturaTareas.crearTareaManual(idGrupo, idAdulto,
                CodigosTareas.TIPO_TAREA_CITAS_CONTROLES_MEDICOS, horario(HOY, 8, 0));
        long idAlerta = fixturaAlertas.crearAlertaDeTarea(idGrupo, idAdulto, idTarea, tipoAlerta,
                FixturaAlertas.claveUnica());
        jdbcTemplate.update("UPDATE ALERTA_CUIDADO SET FECHA_DETECCION = ? WHERE ID_ALERTA = ?", fechaDeteccion,
                idAlerta);
        if (!"ABIERTA".equals(estado)) {
            fixturaAlertas.cambiarEstadoAlerta(idAlerta, estado);
        }
        return idAlerta;
    }

    private OffsetDateTime horario(LocalDate dia, int hora, int minuto) {
        return dia.atTime(hora, minuto).atZone(ZonaNegocio.ZONA).toOffsetDateTime();
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
