package cl.adndigital.mejorvejez.adultos;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.catalogos.ServicioCatalogos;
import cl.adndigital.mejorvejez.comun.ExcepcionConflicto;
import cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;

/**
 * Crear, consultar, actualizar, desactivar, reactivar y eliminar perfiles de
 * adultos mayores de un grupo (HU-03). La autorización por grupo la exige
 * {@link ServicioAutorizacion} en cada operación: consultar exige solo
 * membresía activa; mutar exige coordinador (el administrador pasa ambas sin
 * membresía, como ya hace {@code ServicioAutorizacion}).
 *
 * <p><b>Contacto principal.</b> {@link #listar(long, boolean)} y
 * {@link #obtener(long, long)} resuelven el contacto principal ACTIVO de
 * cada adulto en una sola consulta ({@code
 * RepositorioContactoEmergencia#findByIdAdultoMayorInAndEsPrincipalTrueAndIdEstado}),
 * nunca una por adulto. Esta tarea no toca contactos: las reglas de
 * designación y traspaso del principal son de la Tarea 3.
 *
 * <p><b>Bajas.</b> Desactivar/reactivar son siempre lógicas y no tocan
 * contactos ni otras tablas. Eliminar es físico, y solo si el adulto no tiene
 * contactos, historial ni otras referencias ({@code
 * RepositorioHistorialAdulto#adultoTieneReferencias}); si las tiene, debe
 * desactivarse en su lugar.
 *
 * <p><b>Trazabilidad de administrador.</b> Si el actor mutó como
 * administrador sin membresía en el grupo ({@code contexto.rol() == null}),
 * el evento de auditoría agrega {@code "accesoAdministrador": true} a sus
 * detalles (ver {@link #detalles(ContextoGrupo, Map)}).
 */
@Service
public class ServicioAdultosMayores {

    private static final String MODULO = "adultos";
    private static final String TIPO_ENTIDAD = "ADULTO_MAYOR";
    private static final String TABLA_ESTADO_ADULTO = "ADULTO_MAYOR";
    private static final String TABLA_ESTADO_CONTACTO = "CONTACTO_EMERGENCIA";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String CODIGO_ACTIVO = "ACTIVO";
    private static final String CODIGO_INACTIVO = "INACTIVO";

    private static final String ACCION_CREAR = "CREAR_ADULTO_MAYOR";
    private static final String ACCION_ACTUALIZAR = "ACTUALIZAR_ADULTO_MAYOR";
    private static final String ACCION_DESACTIVAR = "DESACTIVAR_ADULTO_MAYOR";
    private static final String ACCION_REACTIVAR = "REACTIVAR_ADULTO_MAYOR";
    private static final String ACCION_ELIMINAR = "ELIMINAR_ADULTO_MAYOR";

    // Visibilidad de paquete: ServicioContactos (Tarea 3) reutiliza el mismo
    // texto exacto en vez de duplicarlo, porque ambos mensajes describen el
    // mismo adulto mayor (el de la URL), no un concepto propio de esta clase.
    static final String MENSAJE_NO_EXISTE = "El adulto mayor no existe";
    static final String MENSAJE_INACTIVO_NO_EDITABLE = "El adulto mayor está inactivo; reactívalo para modificarlo";

    private static final String MENSAJE_FECHA_FUTURA = "La fecha de nacimiento no puede ser futura";
    private static final String MENSAJE_CODIGO_DUPLICADO = "Ya existe un adulto mayor con ese código en el grupo";
    private static final String MENSAJE_YA_INACTIVO = "El adulto mayor ya está inactivo";
    private static final String MENSAJE_YA_ACTIVO = "El adulto mayor ya está activo";
    private static final String MENSAJE_TIENE_REFERENCIAS =
            "El adulto mayor tiene contactos, historial o referencias; desactívalo en su lugar";

    private final RepositorioAdultoMayor repositorioAdulto;
    private final RepositorioContactoEmergencia repositorioContacto;
    private final RepositorioHistorialAdulto repositorioHistorial;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioAdultosMayores(RepositorioAdultoMayor repositorioAdulto,
            RepositorioContactoEmergencia repositorioContacto, RepositorioHistorialAdulto repositorioHistorial,
            ServicioAutorizacion servicioAutorizacion, ServicioCatalogos servicioCatalogos,
            ServicioAuditoria servicioAuditoria, Clock reloj) {
        this.repositorioAdulto = repositorioAdulto;
        this.repositorioContacto = repositorioContacto;
        this.repositorioHistorial = repositorioHistorial;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioCatalogos = servicioCatalogos;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
    }

    /**
     * Adultos mayores del grupo. Por defecto solo los ACTIVOS; con {@code
     * incluirInactivos}, todos, con los activos primero y luego por {@code
     * nombreCompleto}.
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     */
    public List<RespuestaAdultoMayor> listar(long idGrupo, boolean incluirInactivos) {
        servicioAutorizacion.exigirMiembro(idGrupo);

        long idEstadoActivo = idEstadoActivoAdulto();
        List<AdultoMayor> adultos = incluirInactivos
                ? ordenarActivosPrimero(repositorioAdulto.findByIdGrupoOrderByNombreCompleto(idGrupo), idEstadoActivo)
                : repositorioAdulto.findByIdGrupoAndIdEstadoOrderByNombreCompleto(idGrupo, idEstadoActivo);

        Map<Long, ContactoEmergencia> principales = principalesActivosPorAdultos(
                adultos.stream().map(AdultoMayor::getIdAdultoMayor).toList());

        return adultos.stream()
                .map(adulto -> respuestaConPrincipal(adulto, principales.get(adulto.getIdAdultoMayor())))
                .toList();
    }

    /**
     * El perfil del adulto mayor, también si está inactivo.
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   el adulto no existe o no pertenece al grupo.
     */
    public RespuestaAdultoMayor obtener(long idGrupo, long idAdulto) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        AdultoMayor adulto = buscarAdulto(idGrupo, idAdulto);
        return respuestaConPrincipal(adulto, principalActivo(idAdulto));
    }

    /**
     * Crea el perfil del adulto mayor, ACTIVO.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionReglaNegocio   la fecha de nacimiento es futura.
     * @throws ExcepcionConflicto      ya existe un adulto con ese código en el grupo.
     */
    @Transactional
    public RespuestaAdultoMayor crear(long idGrupo, SolicitudGuardarAdultoMayor solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);

        validarFechaNacimiento(solicitud.fechaNacimiento());
        validarCodigoNoDuplicado(idGrupo, solicitud.codigoInterno(), null);

        AdultoMayor adulto = new AdultoMayor(idGrupo, solicitud.codigoInterno(), solicitud.nombreCompleto(),
                solicitud.fechaNacimiento(), solicitud.notasEmergencia(), solicitud.observacionesGenerales(),
                contexto.usuario().getIdUsuario(), idEstadoActivoAdulto(), OffsetDateTime.now(reloj));
        AdultoMayor guardado = repositorioAdulto.save(adulto);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdAdultoMayor()), idGrupo, contexto.usuario().getIdUsuario(), ACCION_CREAR,
                ResultadoAuditoria.EXITO, detalles(contexto, null)));

        return respuestaConPrincipal(guardado, null);
    }

    /**
     * Actualiza los datos del perfil. Si, tras normalizar, ningún campo
     * cambia, no guarda ni audita (igual que {@code ServicioPerfil}).
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   el adulto está inactivo, o la fecha de
     *                                 nacimiento es futura.
     * @throws ExcepcionConflicto      ya existe otro adulto con ese código en el grupo.
     */
    @Transactional
    public RespuestaAdultoMayor actualizar(long idGrupo, long idAdulto, SolicitudGuardarAdultoMayor solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = buscarAdulto(idGrupo, idAdulto);

        if (adulto.getIdEstado() != idEstadoActivoAdulto()) {
            throw new ExcepcionReglaNegocio(MENSAJE_INACTIVO_NO_EDITABLE);
        }
        validarFechaNacimiento(solicitud.fechaNacimiento());
        validarCodigoNoDuplicado(idGrupo, solicitud.codigoInterno(), adulto.getCodigoInterno());

        List<String> camposModificados = detectarCambios(adulto, solicitud);
        if (camposModificados.isEmpty()) {
            return respuestaConPrincipal(adulto, principalActivo(idAdulto));
        }

        adulto.actualizar(solicitud.codigoInterno(), solicitud.nombreCompleto(), solicitud.fechaNacimiento(),
                solicitud.notasEmergencia(), solicitud.observacionesGenerales(), contexto.usuario().getIdUsuario(),
                OffsetDateTime.now(reloj));
        AdultoMayor guardado = repositorioAdulto.save(adulto);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdAdultoMayor()), idGrupo, contexto.usuario().getIdUsuario(),
                ACCION_ACTUALIZAR, ResultadoAuditoria.EXITO,
                detalles(contexto, Map.of("camposModificados", camposModificados))));

        return respuestaConPrincipal(guardado, principalActivo(idAdulto));
    }

    /**
     * Desactivación lógica: conserva la fila y sus contactos, con trazabilidad
     * del actor y la fecha.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto no existe o no pertenece al grupo.
     * @throws ExcepcionConflicto      el adulto ya está inactivo.
     */
    @Transactional
    public RespuestaAdultoMayor desactivar(long idGrupo, long idAdulto) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = buscarAdulto(idGrupo, idAdulto);

        long idEstadoInactivo = idEstadoInactivoAdulto();
        if (adulto.getIdEstado() == idEstadoInactivo) {
            throw new ExcepcionConflicto(MENSAJE_YA_INACTIVO);
        }

        adulto.cambiarEstado(idEstadoInactivo, contexto.usuario().getIdUsuario(), OffsetDateTime.now(reloj));
        AdultoMayor guardado = repositorioAdulto.save(adulto);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdAdultoMayor()), idGrupo, contexto.usuario().getIdUsuario(),
                ACCION_DESACTIVAR, ResultadoAuditoria.EXITO, detalles(contexto, null)));

        return respuestaConPrincipal(guardado, principalActivo(idAdulto));
    }

    /**
     * Reactiva el perfil.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto no existe o no pertenece al grupo.
     * @throws ExcepcionConflicto      el adulto ya está activo.
     */
    @Transactional
    public RespuestaAdultoMayor reactivar(long idGrupo, long idAdulto) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = buscarAdulto(idGrupo, idAdulto);

        long idEstadoActivo = idEstadoActivoAdulto();
        if (adulto.getIdEstado() == idEstadoActivo) {
            throw new ExcepcionConflicto(MENSAJE_YA_ACTIVO);
        }

        adulto.cambiarEstado(idEstadoActivo, contexto.usuario().getIdUsuario(), OffsetDateTime.now(reloj));
        AdultoMayor guardado = repositorioAdulto.save(adulto);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD,
                String.valueOf(guardado.getIdAdultoMayor()), idGrupo, contexto.usuario().getIdUsuario(),
                ACCION_REACTIVAR, ResultadoAuditoria.EXITO, detalles(contexto, null)));

        return respuestaConPrincipal(guardado, principalActivo(idAdulto));
    }

    /**
     * Elimina físicamente el perfil, solo si no tiene contactos, historial ni
     * otras referencias ({@link RepositorioHistorialAdulto#adultoTieneReferencias}).
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   el adulto tiene contactos, historial o referencias.
     */
    @Transactional
    public void eliminar(long idGrupo, long idAdulto) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = buscarAdulto(idGrupo, idAdulto);

        if (repositorioHistorial.adultoTieneReferencias(idAdulto)) {
            throw new ExcepcionReglaNegocio(MENSAJE_TIENE_REFERENCIAS);
        }

        repositorioAdulto.delete(adulto);

        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(idAdulto), idGrupo,
                contexto.usuario().getIdUsuario(), ACCION_ELIMINAR, ResultadoAuditoria.EXITO,
                detalles(contexto, null)));
    }

    /** @throws ExcepcionNoEncontrado el adulto no existe o no pertenece al grupo. */
    private AdultoMayor buscarAdulto(long idGrupo, long idAdulto) {
        AdultoMayor adulto = repositorioAdulto.findById(idAdulto)
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE));
        if (adulto.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(MENSAJE_NO_EXISTE);
        }
        return adulto;
    }

    /** @throws ExcepcionReglaNegocio {@code fechaNacimiento} es posterior a hoy. */
    private void validarFechaNacimiento(LocalDate fechaNacimiento) {
        if (fechaNacimiento != null && fechaNacimiento.isAfter(LocalDate.now(reloj))) {
            throw new ExcepcionReglaNegocio(MENSAJE_FECHA_FUTURA);
        }
    }

    /**
     * Solo verifica contra el catálogo del grupo cuando {@code codigoNuevo}
     * cambia respecto de {@code codigoActual} (en creación, {@code
     * codigoActual} es siempre {@code null}): así, al actualizar, el propio
     * adulto queda excluido sin necesitar un repositorio que lo filtre, ya
     * que si el código no cambió, su fila no puede chocar con sí misma.
     *
     * @throws ExcepcionConflicto ya existe otro adulto con ese código en el grupo.
     */
    private void validarCodigoNoDuplicado(long idGrupo, String codigoNuevo, String codigoActual) {
        if (codigoNuevo == null || codigoNuevo.equals(codigoActual)) {
            return;
        }
        if (repositorioAdulto.existsByIdGrupoAndCodigoInterno(idGrupo, codigoNuevo)) {
            throw new ExcepcionConflicto(MENSAJE_CODIGO_DUPLICADO);
        }
    }

    /** Campos del perfil cuyo valor normalizado cambia respecto del actual. */
    private List<String> detectarCambios(AdultoMayor adulto, SolicitudGuardarAdultoMayor solicitud) {
        List<String> cambios = new ArrayList<>();
        if (!Objects.equals(adulto.getCodigoInterno(), solicitud.codigoInterno())) {
            cambios.add("codigoInterno");
        }
        if (!Objects.equals(adulto.getNombreCompleto(), solicitud.nombreCompleto())) {
            cambios.add("nombreCompleto");
        }
        if (!Objects.equals(adulto.getFechaNacimiento(), solicitud.fechaNacimiento())) {
            cambios.add("fechaNacimiento");
        }
        if (!Objects.equals(adulto.getNotasEmergencia(), solicitud.notasEmergencia())) {
            cambios.add("notasEmergencia");
        }
        if (!Objects.equals(adulto.getObservacionesGenerales(), solicitud.observacionesGenerales())) {
            cambios.add("observacionesGenerales");
        }
        return cambios;
    }

    /**
     * Agrega {@code "accesoAdministrador": true} a {@code extra} si el actor
     * mutó como administrador sin membresía en el grupo. Delega en
     * {@link DetallesAuditoria}, compartido con {@link ServicioContactos}
     * (Tarea 3), para no duplicar la lógica.
     */
    private Map<String, Object> detalles(ContextoGrupo contexto, Map<String, Object> extra) {
        return DetallesAuditoria.conTrazabilidad(contexto, extra);
    }

    private List<AdultoMayor> ordenarActivosPrimero(List<AdultoMayor> adultos, long idEstadoActivo) {
        return adultos.stream()
                .sorted(Comparator.comparing(adulto -> adulto.getIdEstado() != idEstadoActivo))
                .toList();
    }

    /** Contactos principales ACTIVOS de los adultos indicados, por {@code idAdultoMayor}. */
    private Map<Long, ContactoEmergencia> principalesActivosPorAdultos(List<Long> idsAdultos) {
        if (idsAdultos.isEmpty()) {
            return Map.of();
        }
        long idEstadoActivoContacto = idEstadoActivoContacto();
        return repositorioContacto.findByIdAdultoMayorInAndEsPrincipalTrueAndIdEstado(idsAdultos, idEstadoActivoContacto)
                .stream()
                .collect(Collectors.toMap(ContactoEmergencia::getIdAdultoMayor, Function.identity()));
    }

    private ContactoEmergencia principalActivo(long idAdulto) {
        return principalesActivosPorAdultos(List.of(idAdulto)).get(idAdulto);
    }

    private RespuestaAdultoMayor respuestaConPrincipal(AdultoMayor adulto, ContactoEmergencia principal) {
        RespuestaContacto respuestaPrincipal = principal == null ? null : RespuestaContacto.desde(principal);
        return RespuestaAdultoMayor.desde(adulto, respuestaPrincipal);
    }

    private long idEstadoActivoAdulto() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_ADULTO, CAMPO_ESTADO, CODIGO_ACTIVO);
    }

    private long idEstadoInactivoAdulto() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_ADULTO, CAMPO_ESTADO, CODIGO_INACTIVO);
    }

    private long idEstadoActivoContacto() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_CONTACTO, CAMPO_ESTADO, CODIGO_ACTIVO);
    }
}
