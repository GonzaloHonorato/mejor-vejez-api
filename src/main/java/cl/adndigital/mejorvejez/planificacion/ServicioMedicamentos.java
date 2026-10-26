package cl.adndigital.mejorvejez.planificacion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.catalogos.TipoCatalogo;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Catálogo GLOBAL de medicamentos (HU-04, INC-19/D4): búsqueda que
 * prioriza los usados en el grupo y alta sin duplicados. No es un recurso
 * de un grupo (no tiene {@code ID_GRUPO}), pero toda operación cuelga de
 * {@code /api/grupos/{idGrupo}/medicamentos} porque la autorización y la
 * auditoría son por grupo: consultar exige solo membresía activa; crear
 * exige coordinador (el administrador pasa ambas sin membresía, como ya
 * hace {@link ServicioAutorizacion}).
 *
 * <p><b>Sin duplicados.</b> {@code UX_MEDICAMENTO_NORMALIZADO} (V3) impone
 * la unicidad por {@code (UPPER(TRIM(NOMBRE)), ID_PRESENTACION,
 * UPPER(TRIM(CONCENTRACION)))}. {@link #crear(long, SolicitudCrearMedicamento)}
 * primero busca ese normalizado; si existe, lo devuelve sin auditar. Si no,
 * inserta en una transacción propia ({@code REQUIRES_NEW}, mismo patrón que
 * {@code seguridad.ServicioUsuarioActual#darDeAlta}); si dos altas
 * concurrentes del mismo medicamento compiten, el índice rechaza la
 * segunda con {@link DataIntegrityViolationException} y esa transacción
 * queda marcada para rollback, así que la relectura del normalizado ocurre
 * fuera de ella, ya confirmada por la transacción ganadora.
 */
@Service
public class ServicioMedicamentos {

    private static final String MODULO = "planificacion";
    private static final String TIPO_ENTIDAD = "MEDICAMENTO";
    private static final String ACCION_CREAR = "CREAR_MEDICAMENTO";

    static final String MENSAJE_NO_EXISTE_O_INACTIVO = "El medicamento indicado no existe o no está activo";

    private final RepositorioMedicamento repositorio;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;
    private final TransactionTemplate transaccionNueva;

    public ServicioMedicamentos(RepositorioMedicamento repositorio, ServicioAutorizacion servicioAutorizacion,
            ServicioCatalogos servicioCatalogos, ServicioAuditoria servicioAuditoria, Clock reloj,
            PlatformTransactionManager gestorTransacciones) {
        this.repositorio = repositorio;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
        this.transaccionNueva = new TransactionTemplate(gestorTransacciones);
        this.transaccionNueva.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Medicamentos {@code ACTIVO}, máximo 20. Sin {@code texto}, solo los
     * usados en el grupo; con {@code texto}, los que calcen por nombre o
     * principio activo (sin distinguir mayúsculas; {@code %} y {@code _} del
     * propio texto se escapan). Primero los usados en el grupo, luego por
     * nombre.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     */
    public List<RespuestaMedicamento> buscar(long idGrupo, String texto) {
        servicioAutorizacion.exigirMiembro(idGrupo);

        String patron = patronDe(texto);
        return repositorio.buscar(idGrupo, patron).stream()
                .map(fila -> new RespuestaMedicamento(fila.getIdMedicamento(), fila.getNombre(),
                        fila.getPrincipioActivo(), fila.getIdPresentacion(), fila.getConcentracion(),
                        fila.getUsadoEnGrupo().intValue() == 1))
                .toList();
    }

    /**
     * Da de alta el medicamento en el catálogo global, o devuelve el
     * existente si ya hay uno con el mismo {@code (nombre, idPresentacion,
     * concentracion)} normalizado (sin auditar en ese caso).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionReglaNegocio   {@code idPresentacion} no es una opción de {@code PRESENTACION_MEDICAMENTO}.
     */
    public ResultadoMedicamento crear(long idGrupo, SolicitudCrearMedicamento solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        servicioCatalogos.validarOpcion(solicitud.idPresentacion(), TipoCatalogo.PRESENTACION_MEDICAMENTO);

        Optional<Medicamento> existente = buscarNormalizado(solicitud);
        if (existente.isPresent()) {
            return new ResultadoMedicamento(respuestaDesde(existente.get(), idGrupo), false);
        }

        try {
            Medicamento guardado = transaccionNueva.execute(estado -> insertar(solicitud));
            servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                    String.valueOf(guardado.getIdMedicamento()), idGrupo, contexto.usuario().getIdUsuario(),
                    ACCION_CREAR, ResultadoAuditoria.EXITO, DetallesAuditoria.conTrazabilidad(contexto, null)));
            return new ResultadoMedicamento(respuestaDesde(guardado, idGrupo), true);
        } catch (DataIntegrityViolationException carrera) {
            Medicamento existenteTrasCarrera = buscarNormalizado(solicitud).orElseThrow(() -> carrera);
            return new ResultadoMedicamento(respuestaDesde(existenteTrasCarrera, idGrupo), false);
        }
    }

    /**
     * El medicamento, solo si existe y está {@code ACTIVO}. Lo usa la Tarea
     * 5 (detalle de plan) para validar el medicamento referenciado.
     *
     * @throws ExcepcionReglaNegocio el medicamento no existe o no está activo.
     */
    public Medicamento obtenerActivo(long idMedicamento) {
        return repositorio.findByIdMedicamentoAndActivoTrue(idMedicamento)
                .orElseThrow(() -> new ExcepcionReglaNegocio(MENSAJE_NO_EXISTE_O_INACTIVO));
    }

    private Optional<Medicamento> buscarNormalizado(SolicitudCrearMedicamento solicitud) {
        return repositorio.buscarNormalizado(solicitud.nombre(), solicitud.idPresentacion(),
                solicitud.concentracion());
    }

    private Medicamento insertar(SolicitudCrearMedicamento solicitud) {
        Medicamento nuevo = new Medicamento(solicitud.nombre(), solicitud.principioActivo(),
                solicitud.idPresentacion(), solicitud.concentracion(), OffsetDateTime.now(reloj));
        return repositorio.saveAndFlush(nuevo);
    }

    private RespuestaMedicamento respuestaDesde(Medicamento medicamento, long idGrupo) {
        boolean usadoEnGrupo = repositorio.usadoEnGrupo(medicamento.getIdMedicamento(), idGrupo) == 1;
        return RespuestaMedicamento.desde(medicamento, usadoEnGrupo);
    }

    /** {@code texto} normalizado para el {@code LIKE} nativo, o {@code null} si viene vacío. */
    private String patronDe(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return "%" + escaparComodinesLike(texto.trim().toUpperCase(Locale.ROOT)) + "%";
    }

    /** Escapa {@code \}, {@code %} y {@code _} para usarlo como literal en un {@code LIKE ... ESCAPE '\'}. */
    private String escaparComodinesLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Resultado de {@link #crear(long, SolicitudCrearMedicamento)}: el medicamento y si se creó recién. */
    record ResultadoMedicamento(RespuestaMedicamento medicamento, boolean creado) {
    }
}
