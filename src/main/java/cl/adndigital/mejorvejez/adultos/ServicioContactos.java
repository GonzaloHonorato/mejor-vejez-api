package cl.adndigital.mejorvejez.adultos;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.DetallesAuditoria;
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
 * Crear, consultar, actualizar, designar principal, desactivar, reactivar y
 * eliminar contactos de emergencia de un adulto mayor (HU-03). La
 * autorización por grupo la exige {@link ServicioAutorizacion} en cada
 * operación: consultar exige solo membresía activa; mutar exige coordinador
 * (el administrador pasa ambas sin membresía).
 *
 * <p><b>Contacto principal único.</b> A lo más un contacto ACTIVO con {@code
 * esPrincipal = true} por adulto mayor, incluso bajo concurrencia ({@code
 * UX_CONTACTO_PRINCIPAL_ACTIVO}, V3, es la red de seguridad). El traspaso
 * del principal siempre desmarca y hace {@code flush} del anterior ({@link
 * #traspasarPrincipal}) antes de marcar el nuevo, para que el índice único
 * nunca vea dos principales activos a la vez.
 *
 * <p><b>Orden de cada mutación.</b> {@code exigirCoordinador(idGrupo)} →
 * {@code repositorioAdulto.bloquearPorId(idAdulto)} (con {@code
 * SELECT ... FOR UPDATE}, serializa toda mutación de los contactos del mismo
 * adulto) → validar que el adulto pertenezca al grupo de la URL → validar
 * que esté ACTIVO → leer sus contactos → reglas → guardar. Autorizar antes
 * de bloquear evita revelar en qué grupo está un adulto ajeno.
 *
 * <p><b>Trazabilidad de administrador.</b> Ver {@link DetallesAuditoria},
 * compartido con {@link ServicioAdultosMayores}.
 */
@Service
public class ServicioContactos {

    private static final String MODULO = "adultos";
    private static final String TIPO_ENTIDAD = "CONTACTO_EMERGENCIA";
    private static final String TABLA_ESTADO_CONTACTO = "CONTACTO_EMERGENCIA";
    private static final String CAMPO_ESTADO = "ESTADO";
    private static final String CODIGO_ACTIVO = "ACTIVO";
    private static final String CODIGO_INACTIVO = "INACTIVO";

    private static final String ACCION_CREAR = "CREAR_CONTACTO";
    private static final String ACCION_ACTUALIZAR = "ACTUALIZAR_CONTACTO";
    private static final String ACCION_DESIGNAR_PRINCIPAL = "DESIGNAR_CONTACTO_PRINCIPAL";
    private static final String ACCION_DESACTIVAR = "DESACTIVAR_CONTACTO";
    private static final String ACCION_REACTIVAR = "REACTIVAR_CONTACTO";
    private static final String ACCION_ELIMINAR = "ELIMINAR_CONTACTO";

    private static final String MENSAJE_CONTACTO_NO_EXISTE = "El contacto no existe";
    private static final String MENSAJE_CONTACTO_INACTIVO_NO_EDITABLE =
            "El contacto está inactivo; reactívalo para modificarlo";
    private static final String MENSAJE_SOLO_ACTIVO_PRINCIPAL = "Solo un contacto activo puede ser principal";
    private static final String MENSAJE_DEBE_DESIGNAR_REEMPLAZO_DESACTIVAR =
            "Debes designar otro contacto principal antes de desactivar este";
    private static final String MENSAJE_REEMPLAZO_INVALIDO =
            "El nuevo contacto principal debe ser otro contacto activo del adulto mayor";
    private static final String MENSAJE_YA_INACTIVO = "El contacto ya está inactivo";
    private static final String MENSAJE_YA_ACTIVO = "El contacto ya está activo";
    private static final String MENSAJE_TIENE_HISTORIAL = "El contacto tiene historial; desactívalo en su lugar";
    private static final String MENSAJE_DEBE_DESIGNAR_REEMPLAZO_ELIMINAR =
            "Debes designar otro contacto principal antes de eliminar este";

    private final RepositorioAdultoMayor repositorioAdulto;
    private final RepositorioContactoEmergencia repositorioContacto;
    private final RepositorioHistorialAdulto repositorioHistorial;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioCatalogos servicioCatalogos;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;

    public ServicioContactos(RepositorioAdultoMayor repositorioAdulto,
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
     * Contactos del adulto mayor, en cualquier estado: activos primero, el
     * principal primero entre ellos y luego por {@code nombreContacto}.
     *
     * @throws ExcepcionAccesoDenegado no tiene membresía activa en el grupo.
     * @throws ExcepcionNoEncontrado   el adulto no existe o no pertenece al grupo.
     */
    public List<RespuestaContacto> listar(long idGrupo, long idAdulto) {
        servicioAutorizacion.exigirMiembro(idGrupo);
        buscarAdultoDelGrupo(idGrupo, idAdulto);

        long idEstadoActivo = idEstadoActivoContacto();
        return repositorioContacto.findByIdAdultoMayor(idAdulto).stream()
                .sorted(Comparator
                        .<ContactoEmergencia, Boolean>comparing(contacto -> contacto.getIdEstado() != idEstadoActivo)
                        .thenComparing(contacto -> !contacto.isEsPrincipal())
                        .thenComparing(ContactoEmergencia::getNombreContacto))
                .map(RespuestaContacto::desde)
                .toList();
    }

    /**
     * Crea el contacto, ACTIVO. Queda principal si {@code esPrincipal ==
     * true} o si el adulto no tiene ningún principal activo; en el primer
     * caso, el principal anterior deja de serlo en la misma transacción.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto no existe o no pertenece al grupo.
     * @throws ExcepcionReglaNegocio   el adulto está inactivo.
     */
    @Transactional
    public RespuestaContacto crear(long idGrupo, long idAdulto, SolicitudCrearContacto solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = bloquearAdultoDelGrupo(idGrupo, idAdulto);
        exigirAdultoActivo(adulto);

        List<ContactoEmergencia> contactos = repositorioContacto.findByIdAdultoMayor(idAdulto);
        ContactoEmergencia principalActual = principalActivoEntre(contactos).orElse(null);
        boolean quierePrincipal = Boolean.TRUE.equals(solicitud.esPrincipal());
        boolean seraPrincipal = quierePrincipal || principalActual == null;

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        if (quierePrincipal && principalActual != null) {
            desmarcarYFlush(principalActual, ahora);
        }

        ContactoEmergencia nuevo = new ContactoEmergencia(idAdulto, solicitud.nombreContacto(),
                solicitud.parentesco(), solicitud.telefono(), seraPrincipal, idEstadoActivoContacto(),
                contexto.usuario().getIdUsuario(), ahora);
        ContactoEmergencia guardado = repositorioContacto.save(nuevo);

        registrarAuditoria(contexto, guardado.getIdContacto(), idGrupo, ACCION_CREAR, null);

        return RespuestaContacto.desde(guardado);
    }

    /**
     * Actualiza nombre, parentesco y teléfono. Si, tras normalizar, ningún
     * campo cambia, no guarda ni audita.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto o el contacto no existen, o no pertenecen a la URL.
     * @throws ExcepcionReglaNegocio   el adulto está inactivo, o el contacto está inactivo.
     */
    @Transactional
    public RespuestaContacto actualizar(long idGrupo, long idAdulto, long idContacto,
            SolicitudActualizarContacto solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = bloquearAdultoDelGrupo(idGrupo, idAdulto);
        exigirAdultoActivo(adulto);

        ContactoEmergencia contacto = buscarContactoDelAdulto(idAdulto, idContacto);
        exigirContactoActivo(contacto);

        List<String> camposModificados = detectarCambios(contacto, solicitud);
        if (camposModificados.isEmpty()) {
            return RespuestaContacto.desde(contacto);
        }

        contacto.actualizar(solicitud.nombreContacto(), solicitud.parentesco(), solicitud.telefono(),
                OffsetDateTime.now(reloj));
        ContactoEmergencia guardado = repositorioContacto.save(contacto);

        registrarAuditoria(contexto, guardado.getIdContacto(), idGrupo, ACCION_ACTUALIZAR,
                Map.of("camposModificados", camposModificados));

        return RespuestaContacto.desde(guardado);
    }

    /**
     * Designa el contacto como principal. Si ya lo era, no hace nada y
     * devuelve 200 sin auditar; si no, desplaza al principal anterior (si lo
     * había) en la misma transacción.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto o el contacto no existen, o no pertenecen a la URL.
     * @throws ExcepcionReglaNegocio   el adulto está inactivo, o el contacto está inactivo.
     */
    @Transactional
    public RespuestaContacto designarPrincipal(long idGrupo, long idAdulto, long idContacto) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = bloquearAdultoDelGrupo(idGrupo, idAdulto);
        exigirAdultoActivo(adulto);

        List<ContactoEmergencia> contactos = repositorioContacto.findByIdAdultoMayor(idAdulto);
        ContactoEmergencia contacto = buscarEntre(contactos, idContacto);
        if (contacto.getIdEstado() != idEstadoActivoContacto()) {
            throw new ExcepcionReglaNegocio(MENSAJE_SOLO_ACTIVO_PRINCIPAL);
        }
        if (contacto.isEsPrincipal()) {
            return RespuestaContacto.desde(contacto);
        }

        ContactoEmergencia anterior = principalActivoEntre(contactos).orElse(null);
        Long idContactoAnterior = anterior == null ? null : anterior.getIdContacto();

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        traspasarPrincipal(anterior, contacto, ahora);

        registrarAuditoria(contexto, idContacto, idGrupo, ACCION_DESIGNAR_PRINCIPAL,
                Collections.singletonMap("idContactoAnterior", idContactoAnterior));

        return RespuestaContacto.desde(contacto);
    }

    /**
     * Desactiva el contacto. Si es el principal y hay otros contactos
     * ACTIVOS del mismo adulto, exige {@code idNuevoPrincipal} (otro
     * contacto ACTIVO del adulto) y lo traspasa en la misma transacción; si
     * no hay otros activos, el adulto queda sin principal.
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto o el contacto no existen, o no pertenecen a la URL.
     * @throws ExcepcionReglaNegocio   el adulto está inactivo, o falta/es inválido el reemplazo del principal.
     * @throws ExcepcionConflicto      el contacto ya está inactivo.
     */
    @Transactional
    public RespuestaContacto desactivar(long idGrupo, long idAdulto, long idContacto,
            SolicitudDesactivarContacto solicitud) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = bloquearAdultoDelGrupo(idGrupo, idAdulto);
        exigirAdultoActivo(adulto);

        List<ContactoEmergencia> contactos = repositorioContacto.findByIdAdultoMayor(idAdulto);
        ContactoEmergencia contacto = buscarEntre(contactos, idContacto);
        if (contacto.getIdEstado() == idEstadoInactivoContacto()) {
            throw new ExcepcionConflicto(MENSAJE_YA_INACTIVO);
        }

        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        Long idNuevoPrincipal = null;
        if (contacto.isEsPrincipal()) {
            List<ContactoEmergencia> otrosActivos = otrosActivosEntre(contactos, idContacto);
            if (!otrosActivos.isEmpty()) {
                Long idPropuesto = solicitud == null ? null : solicitud.idNuevoPrincipal();
                if (idPropuesto == null) {
                    throw new ExcepcionReglaNegocio(MENSAJE_DEBE_DESIGNAR_REEMPLAZO_DESACTIVAR);
                }
                ContactoEmergencia nuevoPrincipal = otrosActivos.stream()
                        .filter(candidato -> Objects.equals(candidato.getIdContacto(), idPropuesto))
                        .findFirst()
                        .orElseThrow(() -> new ExcepcionReglaNegocio(MENSAJE_REEMPLAZO_INVALIDO));
                traspasarPrincipal(contacto, nuevoPrincipal, ahora);
                idNuevoPrincipal = nuevoPrincipal.getIdContacto();
            } else {
                desmarcarYFlush(contacto, ahora);
            }
        }

        contacto.cambiarEstado(idEstadoInactivoContacto(), ahora);
        ContactoEmergencia guardado = repositorioContacto.save(contacto);

        Map<String, Object> detalles = idNuevoPrincipal == null ? null : Map.of("idNuevoPrincipal", idNuevoPrincipal);
        registrarAuditoria(contexto, guardado.getIdContacto(), idGrupo, ACCION_DESACTIVAR, detalles);

        return RespuestaContacto.desde(guardado);
    }

    /**
     * Reactiva el contacto. Queda principal solo si el adulto no tiene
     * principal activo; si lo tiene, queda no-principal (normaliza el valor
     * anterior de {@code esPrincipal}).
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto o el contacto no existen, o no pertenecen a la URL.
     * @throws ExcepcionReglaNegocio   el adulto está inactivo.
     * @throws ExcepcionConflicto      el contacto ya está activo.
     */
    @Transactional
    public RespuestaContacto reactivar(long idGrupo, long idAdulto, long idContacto) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = bloquearAdultoDelGrupo(idGrupo, idAdulto);
        exigirAdultoActivo(adulto);

        List<ContactoEmergencia> contactos = repositorioContacto.findByIdAdultoMayor(idAdulto);
        ContactoEmergencia contacto = buscarEntre(contactos, idContacto);
        if (contacto.getIdEstado() == idEstadoActivoContacto()) {
            throw new ExcepcionConflicto(MENSAJE_YA_ACTIVO);
        }

        boolean quedaPrincipal = principalActivoEntre(contactos).isEmpty();
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        contacto.cambiarEstado(idEstadoActivoContacto(), ahora);
        contacto.marcarPrincipal(quedaPrincipal, ahora);
        ContactoEmergencia guardado = repositorioContacto.save(contacto);

        registrarAuditoria(contexto, guardado.getIdContacto(), idGrupo, ACCION_REACTIVAR, null);

        return RespuestaContacto.desde(guardado);
    }

    /**
     * Elimina físicamente el contacto, solo si no tiene historial ({@link
     * RepositorioHistorialAdulto#contactoTieneHistorial}) y, si es el
     * principal activo, no hay otros contactos ACTIVOS sin designar
     * reemplazo (esta operación no admite reemplazo: debe desactivarse con
     * uno, o eliminarse después de que ya no sea principal).
     *
     * @throws ExcepcionAccesoDenegado no es coordinador activo del grupo.
     * @throws ExcepcionNoEncontrado   el adulto o el contacto no existen, o no pertenecen a la URL.
     * @throws ExcepcionReglaNegocio   el adulto está inactivo, el contacto tiene historial, o es el
     *                                 principal activo con otros contactos activos sin reemplazo.
     */
    @Transactional
    public void eliminar(long idGrupo, long idAdulto, long idContacto) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        AdultoMayor adulto = bloquearAdultoDelGrupo(idGrupo, idAdulto);
        exigirAdultoActivo(adulto);

        List<ContactoEmergencia> contactos = repositorioContacto.findByIdAdultoMayor(idAdulto);
        ContactoEmergencia contacto = buscarEntre(contactos, idContacto);

        if (repositorioHistorial.contactoTieneHistorial(idContacto)) {
            throw new ExcepcionReglaNegocio(MENSAJE_TIENE_HISTORIAL);
        }
        if (contacto.getIdEstado() == idEstadoActivoContacto() && contacto.isEsPrincipal()
                && !otrosActivosEntre(contactos, idContacto).isEmpty()) {
            throw new ExcepcionReglaNegocio(MENSAJE_DEBE_DESIGNAR_REEMPLAZO_ELIMINAR);
        }

        repositorioContacto.delete(contacto);

        registrarAuditoria(contexto, idContacto, idGrupo, ACCION_ELIMINAR, null);
    }

    /** @throws ExcepcionNoEncontrado el adulto no existe o no pertenece al grupo (lectura, sin bloqueo). */
    private AdultoMayor buscarAdultoDelGrupo(long idGrupo, long idAdulto) {
        AdultoMayor adulto = repositorioAdulto.findById(idAdulto)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE));
        if (adulto.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE);
        }
        return adulto;
    }

    /**
     * @throws ExcepcionNoEncontrado el adulto no existe o no pertenece al grupo. Bloquea la fila
     *                                ({@code SELECT ... FOR UPDATE}) para serializar toda mutación de
     *                                sus contactos.
     */
    private AdultoMayor bloquearAdultoDelGrupo(long idGrupo, long idAdulto) {
        AdultoMayor adulto = repositorioAdulto.bloquearPorId(idAdulto)
                .orElseThrow(() -> new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE));
        if (adulto.getIdGrupo() != idGrupo) {
            throw new ExcepcionNoEncontrado(ServicioAdultosMayores.MENSAJE_NO_EXISTE);
        }
        return adulto;
    }

    /** @throws ExcepcionReglaNegocio el adulto está inactivo. */
    private void exigirAdultoActivo(AdultoMayor adulto) {
        if (adulto.getIdEstado() != idEstadoActivoAdulto()) {
            throw new ExcepcionReglaNegocio(ServicioAdultosMayores.MENSAJE_INACTIVO_NO_EDITABLE);
        }
    }

    /** @throws ExcepcionReglaNegocio el contacto está inactivo. */
    private void exigirContactoActivo(ContactoEmergencia contacto) {
        if (contacto.getIdEstado() != idEstadoActivoContacto()) {
            throw new ExcepcionReglaNegocio(MENSAJE_CONTACTO_INACTIVO_NO_EDITABLE);
        }
    }

    /** @throws ExcepcionNoEncontrado el contacto no existe o no pertenece al adulto mayor. */
    private ContactoEmergencia buscarContactoDelAdulto(long idAdulto, long idContacto) {
        return buscarEntre(repositorioContacto.findByIdAdultoMayor(idAdulto), idContacto);
    }

    /** @throws ExcepcionNoEncontrado ningún contacto de {@code contactos} tiene ese id. */
    private ContactoEmergencia buscarEntre(List<ContactoEmergencia> contactos, long idContacto) {
        return contactos.stream()
                .filter(contacto -> contacto.getIdContacto() == idContacto)
                .findFirst()
                .orElseThrow(() -> new ExcepcionNoEncontrado(MENSAJE_CONTACTO_NO_EXISTE));
    }

    /** El contacto ACTIVO con {@code esPrincipal = true} entre {@code contactos}, si lo hay. */
    private Optional<ContactoEmergencia> principalActivoEntre(List<ContactoEmergencia> contactos) {
        long idEstadoActivo = idEstadoActivoContacto();
        return contactos.stream()
                .filter(contacto -> contacto.getIdEstado() == idEstadoActivo && contacto.isEsPrincipal())
                .findFirst();
    }

    /** Los contactos ACTIVOS de {@code contactos}, salvo {@code idExcluido}. */
    private List<ContactoEmergencia> otrosActivosEntre(List<ContactoEmergencia> contactos, long idExcluido) {
        long idEstadoActivo = idEstadoActivoContacto();
        return contactos.stream()
                .filter(contacto -> contacto.getIdContacto() != idExcluido && contacto.getIdEstado() == idEstadoActivo)
                .toList();
    }

    /**
     * Traspasa la designación de principal: primero desmarca y hace {@code
     * flush} del anterior (si lo había), después marca el nuevo. Así el
     * índice único {@code UX_CONTACTO_PRINCIPAL_ACTIVO} nunca ve dos
     * principales activos a la vez.
     */
    private void traspasarPrincipal(ContactoEmergencia anterior, ContactoEmergencia nuevo, OffsetDateTime ahora) {
        if (anterior != null) {
            desmarcarYFlush(anterior, ahora);
        }
        nuevo.marcarPrincipal(true, ahora);
        repositorioContacto.save(nuevo);
    }

    private void desmarcarYFlush(ContactoEmergencia contacto, OffsetDateTime ahora) {
        contacto.marcarPrincipal(false, ahora);
        repositorioContacto.saveAndFlush(contacto);
    }

    /** Campos del contacto cuyo valor normalizado cambia respecto del actual. */
    private List<String> detectarCambios(ContactoEmergencia contacto, SolicitudActualizarContacto solicitud) {
        List<String> cambios = new ArrayList<>();
        if (!Objects.equals(contacto.getNombreContacto(), solicitud.nombreContacto())) {
            cambios.add("nombreContacto");
        }
        if (!Objects.equals(contacto.getParentesco(), solicitud.parentesco())) {
            cambios.add("parentesco");
        }
        if (!Objects.equals(contacto.getTelefono(), solicitud.telefono())) {
            cambios.add("telefono");
        }
        return cambios;
    }

    private void registrarAuditoria(ContextoGrupo contexto, long idContacto, long idGrupo, String accion,
            Map<String, Object> extra) {
        servicioAuditoria.registrar(new EventoARegistrar(MODULO, TIPO_ENTIDAD, String.valueOf(idContacto), idGrupo,
                contexto.usuario().getIdUsuario(), accion, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(contexto, extra)));
    }

    private long idEstadoActivoAdulto() {
        return servicioCatalogos.idEstado("ADULTO_MAYOR", CAMPO_ESTADO, CODIGO_ACTIVO);
    }

    private long idEstadoActivoContacto() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_CONTACTO, CAMPO_ESTADO, CODIGO_ACTIVO);
    }

    private long idEstadoInactivoContacto() {
        return servicioCatalogos.idEstado(TABLA_ESTADO_CONTACTO, CAMPO_ESTADO, CODIGO_INACTIVO);
    }
}
