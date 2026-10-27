package cl.adndigital.mejorvejez.auditoria;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.adndigital.mejorvejez.comun.ExcepcionReglaNegocio;
import cl.adndigital.mejorvejez.comun.RespuestaPagina;
import cl.adndigital.mejorvejez.comun.RespuestaPersona;
import cl.adndigital.mejorvejez.comun.ZonaNegocio;
import cl.adndigital.mejorvejez.grupos.RepositorioGrupoFamiliar;
import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;
import cl.adndigital.mejorvejez.seguridad.ServicioAutorizacion;
import cl.adndigital.mejorvejez.usuarios.Usuario;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consulta paginada de {@code EVENTO_AUDITORIA} (HU-12, TSA-52): el
 * coordinador ve la de su grupo ({@link #consultarGrupo}) y el administrador
 * la de todos los grupos, incluidos los eventos sin grupo ({@link
 * #consultarGlobal}). Es de solo lectura: no existe ninguna operación para
 * modificar o eliminar (la inmutabilidad la garantiza además el trigger
 * {@code TRG_AUDITORIA_INMUTABLE} de la Fase 0).
 *
 * <p><b>Consultas del administrador auditadas (regla 13 de la Fase 7).</b>
 * En {@link #consultarGrupo}, solo cuando el actor accede como administrador
 * sin membresía activa en el grupo (los coordinadores no se auditan); en
 * {@link #consultarGlobal}, siempre (todo acceso ahí es de un administrador).
 * Ambas registran {@code CONSULTAR_AUDITORIA} en una transacción propia (la
 * del llamador es de solo lectura), con los filtros usados (sin {@code
 * idEntidad}) y {@code accesoAdministrador: true}.
 *
 * <p><b>Sin secretos, con defensa en profundidad (regla 12).</b> {@code
 * DETALLES_JSON} se interpreta con {@link JsonMapper} y se vuelve a filtrar
 * con {@link FiltroDatosSensibles} antes de responder, aunque ya se filtró
 * al escribir. Si no se puede interpretar como JSON, {@code detalles} queda
 * {@code null} y solo se registra el id del evento (nunca el JSON crudo) en
 * el log.
 */
@Service
public class ServicioConsultaAuditoria {

    static final String MENSAJE_FECHA_INVALIDA = "La fecha desde no puede ser posterior a la fecha hasta";
    static final String MENSAJE_RESULTADO_INVALIDO = "El código de resultado no es válido";

    private static final Logger LOG = LoggerFactory.getLogger(ServicioConsultaAuditoria.class);

    private static final String MODULO = "auditoria";
    private static final String ACCION_CONSULTAR_AUDITORIA = "CONSULTAR_AUDITORIA";
    private static final String TIPO_ENTIDAD_GRUPO = "GRUPO_FAMILIAR";
    private static final String ID_ENTIDAD_GLOBAL = "GLOBAL";

    /** Dominio de {@code EVENTO_AUDITORIA.RESULTADO} (regla 9: fuera de estos tres, 422). */
    private static final Set<String> RESULTADOS_VALIDOS = Set.of("EXITO", "FALLO", "RECHAZADA");

    private final RepositorioConsultaAuditoria repositorioConsulta;
    private final RepositorioGrupoFamiliar repositorioGrupo;
    private final ServicioAutorizacion servicioAutorizacion;
    private final ServicioAuditoria servicioAuditoria;
    private final JsonMapper jsonMapper;

    public ServicioConsultaAuditoria(RepositorioConsultaAuditoria repositorioConsulta,
            RepositorioGrupoFamiliar repositorioGrupo, ServicioAutorizacion servicioAutorizacion,
            ServicioAuditoria servicioAuditoria, JsonMapper jsonMapper) {
        this.repositorioConsulta = repositorioConsulta;
        this.repositorioGrupo = repositorioGrupo;
        this.servicioAutorizacion = servicioAutorizacion;
        this.servicioAuditoria = servicioAuditoria;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Auditoría del grupo. El administrador sin membresía también puede
     * (regla 9); en ese caso queda auditado (regla 13).
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionNoEncontrado   el grupo no existe.
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es coordinador activo del grupo (ni
     *                                 administrador).
     * @throws ExcepcionReglaNegocio   {@code desde} posterior a {@code hasta}, o {@code codigoResultado} inválido.
     */
    @Transactional(readOnly = true)
    public RespuestaPagina<RespuestaEventoAuditoria> consultarGrupo(long idGrupo, FiltroConsultaAuditoria filtro,
            int pagina, int tamano) {
        ContextoGrupo contexto = servicioAutorizacion.exigirCoordinador(idGrupo);
        FiltroConsultaAuditoria filtroNormalizado = normalizar(filtro);
        validarFiltro(filtroNormalizado);
        if (DetallesAuditoria.esAccesoAdministrador(contexto)) {
            auditarConsultaDeGrupo(contexto, idGrupo, filtroNormalizado);
        }
        return buscar(idGrupo, filtroNormalizado, pagina, tamano);
    }

    /**
     * Auditoría global: todos los grupos, incluidos los eventos sin grupo
     * ({@code EVENTO_AUDITORIA.ID_GRUPO IS NULL}). {@code filtro.idGrupo()}
     * la acota opcionalmente a un grupo. Siempre queda auditada (regla 13).
     * Un {@code idGrupo} que no existe no es un error: no hay {@code
     * EVENTO_AUDITORIA} con ese grupo, así que la búsqueda simplemente no
     * encuentra nada (página vacía); el evento de auditoría de la consulta
     * en sí se registra con {@code ID_GRUPO = null} (ver {@link
     * #auditarConsultaGlobal}), porque {@code EVENTO_AUDITORIA.ID_GRUPO}
     * tiene una FK a {@code GRUPO_FAMILIAR} y no admite un id inexistente.
     *
     * @throws cl.adndigital.mejorvejez.comun.ExcepcionAccesoDenegado no es administrador.
     * @throws ExcepcionReglaNegocio   {@code desde} posterior a {@code hasta}, o {@code codigoResultado} inválido.
     */
    @Transactional(readOnly = true)
    public RespuestaPagina<RespuestaEventoAuditoria> consultarGlobal(FiltroConsultaAuditoria filtro, int pagina,
            int tamano) {
        Usuario administrador = servicioAutorizacion.exigirAdministrador();
        FiltroConsultaAuditoria filtroNormalizado = normalizar(filtro);
        validarFiltro(filtroNormalizado);
        auditarConsultaGlobal(administrador, filtroNormalizado);
        return buscar(null, filtroNormalizado, pagina, tamano);
    }

    private RespuestaPagina<RespuestaEventoAuditoria> buscar(Long idGrupoFijo, FiltroConsultaAuditoria filtro,
            int pagina, int tamano) {
        long total = repositorioConsulta.contar(idGrupoFijo, filtro);
        List<RespuestaEventoAuditoria> elementos =
                repositorioConsulta.buscar(idGrupoFijo, filtro, pagina, tamano).stream().map(this::ensamblar).toList();
        return new RespuestaPagina<>(elementos, pagina, tamano, total);
    }

    private RespuestaEventoAuditoria ensamblar(FilaEventoAuditoria fila) {
        RespuestaPersona actor = fila.idUsuarioActor() == null ? null
                : new RespuestaPersona(fila.idUsuarioActor(), fila.nombreActor());
        return new RespuestaEventoAuditoria(
                fila.idEvento(),
                ZonaNegocio.enZona(fila.fechaEvento()),
                fila.nombreServicio(),
                fila.tipoEntidad(),
                fila.idEntidad(),
                fila.idGrupo(),
                actor,
                fila.codigoAccion(),
                fila.idResultado(),
                fila.codigoResultado(),
                fila.idCorrelacion(),
                interpretarDetalles(fila.idEvento(), fila.detallesJson()));
    }

    /**
     * Normaliza los filtros de texto: en blanco (vacío o solo espacios) se
     * trata como "sin filtro" ({@code null}), igual que si no se hubiera
     * mandado el parámetro. Un {@code ?tipoEntidad=} vacío desde el
     * selector "Todas" del front (regla 28) no debe excluir todo en
     * silencio (en Oracle {@code '' = NULL}, así que {@code = :tipoEntidad}
     * con cadena vacía no calza con ninguna fila).
     */
    private FiltroConsultaAuditoria normalizar(FiltroConsultaAuditoria filtro) {
        return new FiltroConsultaAuditoria(filtro.idGrupo(), enBlancoANulo(filtro.tipoEntidad()),
                enBlancoANulo(filtro.idEntidad()), enBlancoANulo(filtro.codigoAccion()),
                enBlancoANulo(filtro.codigoResultado()), filtro.desde(), filtro.hasta());
    }

    private String enBlancoANulo(String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.trim();
        return recortado.isEmpty() ? null : recortado;
    }

    /** @throws ExcepcionReglaNegocio {@code desde} posterior a {@code hasta}, o {@code codigoResultado} inválido. */
    private void validarFiltro(FiltroConsultaAuditoria filtro) {
        if (filtro.desde() != null && filtro.hasta() != null && filtro.desde().isAfter(filtro.hasta())) {
            throw new ExcepcionReglaNegocio(MENSAJE_FECHA_INVALIDA);
        }
        if (filtro.codigoResultado() != null && !RESULTADOS_VALIDOS.contains(filtro.codigoResultado())) {
            throw new ExcepcionReglaNegocio(MENSAJE_RESULTADO_INVALIDO);
        }
    }

    private void auditarConsultaDeGrupo(ContextoGrupo contexto, long idGrupo, FiltroConsultaAuditoria filtro) {
        Map<String, Object> detalles = detallesDeFiltro(filtro);
        servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(MODULO, TIPO_ENTIDAD_GRUPO,
                String.valueOf(idGrupo), idGrupo, contexto.usuario().getIdUsuario(), ACCION_CONSULTAR_AUDITORIA,
                ResultadoAuditoria.EXITO, DetallesAuditoria.conTrazabilidad(contexto, detalles)));
    }

    /**
     * {@code EVENTO_AUDITORIA.ID_GRUPO} tiene {@code FK_AUDITORIA_GRUPO →
     * GRUPO_FAMILIAR}: si {@code filtro.idGrupo()} no corresponde a un
     * grupo existente, el evento de auditoría de la consulta se registra
     * con {@code ID_GRUPO = null} (nunca con un id que no existe, o el
     * insert violaría la FK y toda la consulta respondería 409 en vez de
     * resultados). El {@code idGrupo} solicitado, exista o no, siempre
     * queda en los detalles ({@link #detallesDeFiltro}).
     */
    private void auditarConsultaGlobal(Usuario administrador, FiltroConsultaAuditoria filtro) {
        Long idGrupoSolicitado = filtro.idGrupo();
        Long idGrupoAuditado =
                idGrupoSolicitado != null && repositorioGrupo.existsById(idGrupoSolicitado) ? idGrupoSolicitado
                        : null;
        String idEntidad = idGrupoAuditado == null ? ID_ENTIDAD_GLOBAL : String.valueOf(idGrupoAuditado);
        Map<String, Object> detalles = detallesDeFiltro(filtro);
        servicioAuditoria.registrarEnTransaccionPropia(new EventoARegistrar(MODULO, TIPO_ENTIDAD_GRUPO, idEntidad,
                idGrupoAuditado, administrador.getIdUsuario(), ACCION_CONSULTAR_AUDITORIA, ResultadoAuditoria.EXITO,
                DetallesAuditoria.conTrazabilidad(true, detalles)));
    }

    /** Los filtros usados, sin {@code idEntidad} (regla 13), o {@code null} si no se usó ninguno. */
    private Map<String, Object> detallesDeFiltro(FiltroConsultaAuditoria filtro) {
        Map<String, Object> detalles = new LinkedHashMap<>();
        if (filtro.idGrupo() != null) {
            detalles.put("idGrupo", filtro.idGrupo());
        }
        if (filtro.tipoEntidad() != null) {
            detalles.put("tipoEntidad", filtro.tipoEntidad());
        }
        if (filtro.codigoAccion() != null) {
            detalles.put("codigoAccion", filtro.codigoAccion());
        }
        if (filtro.codigoResultado() != null) {
            detalles.put("codigoResultado", filtro.codigoResultado());
        }
        if (filtro.desde() != null) {
            detalles.put("desde", filtro.desde().toString());
        }
        if (filtro.hasta() != null) {
            detalles.put("hasta", filtro.hasta().toString());
        }
        return detalles.isEmpty() ? null : detalles;
    }

    /**
     * Interpreta {@code detallesJson} como mapa y lo vuelve a filtrar con
     * {@link FiltroDatosSensibles} (defensa en profundidad). Si no se puede
     * interpretar, devuelve {@code null} y registra solo el id del evento
     * (nunca el JSON crudo) en el log.
     */
    private Map<String, Object> interpretarDetalles(Long idEvento, String detallesJson) {
        if (detallesJson == null) {
            return null;
        }
        try {
            Map<String, Object> detalles =
                    jsonMapper.readValue(detallesJson, new TypeReference<Map<String, Object>>() {
                    });
            return FiltroDatosSensibles.filtrar(detalles);
        } catch (RuntimeException excepcion) {
            LOG.warn("No se pudo interpretar DETALLES_JSON del evento de auditoría {}", idEvento);
            return null;
        }
    }
}
