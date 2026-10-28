package cl.adndigital.mejorvejez.notificaciones;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.auditoria.EventoARegistrar;
import cl.adndigital.mejorvejez.auditoria.ResultadoAuditoria;
import cl.adndigital.mejorvejez.auditoria.ServicioAuditoria;
import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.seguridad.ServicioUsuarioActual;
import cl.adndigital.mejorvejez.usuarios.RepositorioUsuario;
import cl.adndigital.mejorvejez.usuarios.Usuario;

/**
 * Canales de notificación del usuario autenticado ({@code GET}/{@code PUT
 * /api/yo/canales}, regla 17 de la Fase 5) y resolución en lote de los
 * canales operativos de un conjunto de usuarios (regla 8, consumido por
 * {@code interno.ServicioReservaIntentos} al reservar los intentos de una
 * alerta).
 *
 * <p>{@code CORREO} siempre tiene un canal operativo (Fase 9: el Core ya no
 * envía correos, lo hace el microservicio de Alertas, que marca FALLIDO un
 * intento si no tiene SMTP configurado): si el usuario no configuró una
 * fila propia, se usa el {@code USUARIO.EMAIL} de su cuenta, habilitado
 * (regla 8). Por eso un usuario nunca puede quedar, tras un {@code PUT}, sin
 * ningún canal operativo: para lograrlo tendría que deshabilitar
 * explícitamente {@code CORREO} sin que ningún otro canal esté disponible,
 * lo que la validación rechaza (último chequeo de la regla 17). {@code
 * WHATSAPP} sigue gobernado por {@code
 * mejorvejez.notificaciones.whatsapp-habilitado} (spike TSA-46, fijo en
 * {@code false} en este MVP).
 */
@Service
public class ServicioCanales {

    private static final String TIPO_ENTIDAD = "USUARIO";

    private static final Pattern PATRON_CORREO = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PATRON_WHATSAPP = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final int MAXIMO_LARGO_CORREO = 254;
    private static final String MENSAJE_LISTA_INVALIDA = "La lista de canales no es válida.";

    private final RepositorioCanalNotificacion repositorio;
    private final RepositorioUsuario repositorioUsuario;
    private final ServicioUsuarioActual servicioUsuarioActual;
    private final ServicioAuditoria servicioAuditoria;
    private final Clock reloj;
    private final boolean whatsappHabilitado;

    public ServicioCanales(RepositorioCanalNotificacion repositorio, RepositorioUsuario repositorioUsuario,
            ServicioUsuarioActual servicioUsuarioActual, ServicioAuditoria servicioAuditoria, Clock reloj,
            @Value("${mejorvejez.notificaciones.whatsapp-habilitado:false}") boolean whatsappHabilitado) {
        this.repositorio = repositorio;
        this.repositorioUsuario = repositorioUsuario;
        this.servicioUsuarioActual = servicioUsuarioActual;
        this.servicioAuditoria = servicioAuditoria;
        this.reloj = reloj;
        this.whatsappHabilitado = whatsappHabilitado;
    }

    /** Canales del usuario autenticado ({@code GET /api/yo/canales}). */
    @Transactional(readOnly = true)
    public RespuestaCanales misCanales() {
        Usuario usuario = servicioUsuarioActual.obtener();
        List<CanalNotificacionUsuario> filas = repositorio.findByIdUsuario(usuario.getIdUsuario());
        return construirRespuesta(usuario, filas);
    }

    /**
     * Reemplaza por completo el conjunto de canales del usuario autenticado
     * ({@code PUT /api/yo/canales}): inserta o actualiza los enviados y
     * borra los ausentes. Audita {@code ACTUALIZAR_CANALES_NOTIFICACION}
     * sobre {@code USUARIO} con los códigos de los canales que quedaron
     * habilitados, nunca los destinos.
     *
     * <p>Bloquea la fila del usuario ({@code SELECT ... FOR UPDATE},
     * {@code RepositorioUsuario#bloquearPorId}) antes de leer sus canales
     * actuales, para serializar dos {@code PUT} concurrentes del mismo
     * usuario: sin el bloqueo, ambos podrían ver "sin fila" para el mismo
     * canal y chocar con la PK compuesta {@code (ID_USUARIO, CANAL)} de
     * {@code CANAL_NOTIFICACION_USUARIO}, lo que terminaría en el 409
     * genérico de {@code DataIntegrityViolationException}.
     */
    @Transactional
    public RespuestaCanales actualizarMisCanales(SolicitudActualizarCanales solicitud) {
        Usuario usuario = servicioUsuarioActual.obtener();
        List<SolicitudCanal> canales = solicitud.canales();
        // `contains(null)` lanza con las listas inmutables de `List.of`; por eso el recorrido.
        if (canales == null || canales.stream().anyMatch(Objects::isNull)) {
            throw new ExcepcionReglaNegocio(MENSAJE_LISTA_INVALIDA);
        }
        validar(canales);

        repositorioUsuario.bloquearPorId(usuario.getIdUsuario()).orElseThrow();

        List<CanalNotificacionUsuario> filasActuales = repositorio.findByIdUsuario(usuario.getIdUsuario());
        Map<String, CanalNotificacionUsuario> filasPorCanal = filasActuales.stream()
                .collect(Collectors.toMap(CanalNotificacionUsuario::getCanal, Function.identity()));
        OffsetDateTime ahora = OffsetDateTime.now(reloj);

        Set<String> canalesEnviados = new HashSet<>();
        for (SolicitudCanal canal : canales) {
            String destino = canal.destino() == null ? null : canal.destino().strip();
            canalesEnviados.add(canal.canal());
            CanalNotificacionUsuario fila = filasPorCanal.get(canal.canal());
            if (fila == null) {
                repositorio.save(
                        new CanalNotificacionUsuario(usuario.getIdUsuario(), canal.canal(), destino,
                                canal.habilitado(), ahora));
            } else {
                fila.actualizar(destino, canal.habilitado(), ahora);
            }
        }
        for (CanalNotificacionUsuario fila : filasActuales) {
            if (!canalesEnviados.contains(fila.getCanal())) {
                repositorio.delete(fila);
            }
        }

        List<CanalNotificacionUsuario> filasFinales = repositorio.findByIdUsuario(usuario.getIdUsuario());
        RespuestaCanales respuesta = construirRespuesta(usuario, filasFinales);

        List<String> canalesHabilitados = respuesta.canales().stream()
                .filter(RespuestaCanal::habilitado)
                .map(RespuestaCanal::canal)
                .toList();
        servicioAuditoria.registrar(new EventoARegistrar(CodigosNotificaciones.MODULO, TIPO_ENTIDAD,
                String.valueOf(usuario.getIdUsuario()), null, usuario.getIdUsuario(),
                CodigosNotificaciones.ACCION_ACTUALIZAR_CANALES_NOTIFICACION, ResultadoAuditoria.EXITO,
                Map.of("canalesHabilitados", canalesHabilitados)));

        return respuesta;
    }

    /**
     * Canales operativos (regla 8) de varios usuarios en una sola consulta
     * por lote, sin N+1: lo consume {@code alertas} para saber a qué
     * canales entregar una notificación. Orden por usuario: CORREO,
     * WHATSAPP.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<CanalOperativo>> canalesOperativos(Collection<Usuario> usuarios) {
        if (usuarios.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = usuarios.stream().map(Usuario::getIdUsuario).toList();
        Map<Long, List<CanalNotificacionUsuario>> filasPorUsuario = repositorio.findByIdUsuarioIn(ids).stream()
                .collect(Collectors.groupingBy(CanalNotificacionUsuario::getIdUsuario));

        Map<Long, List<CanalOperativo>> resultado = new LinkedHashMap<>();
        for (Usuario usuario : usuarios) {
            List<CanalNotificacionUsuario> filas = filasPorUsuario.getOrDefault(usuario.getIdUsuario(), List.of());
            Map<String, CanalNotificacionUsuario> porCanal = filas.stream()
                    .collect(Collectors.toMap(CanalNotificacionUsuario::getCanal, Function.identity()));
            resultado.put(usuario.getIdUsuario(), canalesOperativosDe(usuario, porCanal));
        }
        return resultado;
    }

    private List<CanalOperativo> canalesOperativosDe(Usuario usuario,
            Map<String, CanalNotificacionUsuario> porCanal) {
        List<CanalOperativo> operativos = new ArrayList<>();

        CanalNotificacionUsuario filaCorreo = porCanal.get(CodigosNotificaciones.CANAL_CORREO);
        if (filaCorreo != null) {
            if (esCanalOperativo(CodigosNotificaciones.CANAL_CORREO, filaCorreo.getHabilitado())) {
                operativos.add(new CanalOperativo(CodigosNotificaciones.CANAL_CORREO, filaCorreo.getDestino()));
            }
        } else if (esCanalOperativo(CodigosNotificaciones.CANAL_CORREO, true)) {
            operativos.add(new CanalOperativo(CodigosNotificaciones.CANAL_CORREO, usuario.getEmail()));
        }

        CanalNotificacionUsuario filaWhatsapp = porCanal.get(CodigosNotificaciones.CANAL_WHATSAPP);
        if (filaWhatsapp != null && esCanalOperativo(CodigosNotificaciones.CANAL_WHATSAPP, filaWhatsapp.getHabilitado())) {
            operativos.add(new CanalOperativo(CodigosNotificaciones.CANAL_WHATSAPP, filaWhatsapp.getDestino()));
        }

        return operativos;
    }

    private RespuestaCanales construirRespuesta(Usuario usuario, List<CanalNotificacionUsuario> filas) {
        Map<String, CanalNotificacionUsuario> porCanal = filas.stream()
                .collect(Collectors.toMap(CanalNotificacionUsuario::getCanal, Function.identity()));
        List<RespuestaCanal> canales = new ArrayList<>();

        CanalNotificacionUsuario filaCorreo = porCanal.get(CodigosNotificaciones.CANAL_CORREO);
        if (filaCorreo != null) {
            canales.add(new RespuestaCanal(CodigosNotificaciones.CANAL_CORREO, filaCorreo.getDestino(),
                    filaCorreo.getHabilitado(), false));
        } else {
            canales.add(new RespuestaCanal(CodigosNotificaciones.CANAL_CORREO, usuario.getEmail(), true, true));
        }

        CanalNotificacionUsuario filaWhatsapp = porCanal.get(CodigosNotificaciones.CANAL_WHATSAPP);
        if (filaWhatsapp != null) {
            canales.add(new RespuestaCanal(CodigosNotificaciones.CANAL_WHATSAPP, filaWhatsapp.getDestino(),
                    filaWhatsapp.getHabilitado(), false));
        }

        boolean whatsappDisponible = esDisponible(CodigosNotificaciones.CANAL_WHATSAPP);
        return new RespuestaCanales(canales, whatsappDisponible);
    }

    private void validar(List<SolicitudCanal> canales) {
        Set<String> vistos = new HashSet<>();
        for (SolicitudCanal canal : canales) {
            String codigo = canal.canal();
            if (!CodigosNotificaciones.CANAL_CORREO.equals(codigo) && !CodigosNotificaciones.CANAL_WHATSAPP.equals(codigo)) {
                throw new ExcepcionReglaNegocio("El canal indicado no existe.");
            }
            if (!vistos.add(codigo)) {
                throw new ExcepcionReglaNegocio("Cada canal puede aparecer una sola vez.");
            }
            String destino = canal.destino() == null ? "" : canal.destino().strip();
            if (CodigosNotificaciones.CANAL_CORREO.equals(codigo)) {
                if (!esCorreoValido(destino)) {
                    throw new ExcepcionReglaNegocio("El correo de notificación no es válido.");
                }
            } else if (!PATRON_WHATSAPP.matcher(destino).matches()) {
                throw new ExcepcionReglaNegocio(
                        "El número de WhatsApp debe tener formato internacional, por ejemplo +56912345678.");
            }
        }
        if (!resultaConCanalOperativo(canales)) {
            throw new ExcepcionReglaNegocio(
                    "Debes mantener al menos un canal habilitado que el sistema pueda usar (hoy, el correo).");
        }
    }

    /** Si, tras aplicar {@code canales}, el usuario queda con al menos un canal operativo (regla 8). */
    private boolean resultaConCanalOperativo(List<SolicitudCanal> canales) {
        Map<String, SolicitudCanal> porCanal = canales.stream()
                .collect(Collectors.toMap(SolicitudCanal::canal, Function.identity()));

        SolicitudCanal correo = porCanal.get(CodigosNotificaciones.CANAL_CORREO);
        boolean correoOperativo = correo == null
                ? esCanalOperativo(CodigosNotificaciones.CANAL_CORREO, true)
                : esCanalOperativo(CodigosNotificaciones.CANAL_CORREO, correo.habilitado());
        if (correoOperativo) {
            return true;
        }

        SolicitudCanal whatsapp = porCanal.get(CodigosNotificaciones.CANAL_WHATSAPP);
        return whatsapp != null && esCanalOperativo(CodigosNotificaciones.CANAL_WHATSAPP, whatsapp.habilitado());
    }

    private boolean esCanalOperativo(String canal, boolean habilitado) {
        return habilitado && esDisponible(canal);
    }

    /**
     * Si el canal está disponible en el Core: {@code CORREO} siempre lo
     * está (el envío lo hace Alertas); {@code WHATSAPP} depende de {@code
     * mejorvejez.notificaciones.whatsapp-habilitado}.
     */
    private boolean esDisponible(String canal) {
        if (CodigosNotificaciones.CANAL_CORREO.equals(canal)) {
            return true;
        }
        return CodigosNotificaciones.CANAL_WHATSAPP.equals(canal) && whatsappHabilitado;
    }

    private boolean esCorreoValido(String destino) {
        return !destino.isBlank() && destino.length() <= MAXIMO_LARGO_CORREO && PATRON_CORREO.matcher(destino).matches();
    }
}
