package cl.adndigital.mejorvejez.sucesion;

/**
 * Valores exactos (nombres de tabla/dominio, códigos de estado, índice de
 * respaldo y códigos de auditoría) del módulo {@code sucesion} (HU-13,
 * TSA-53). Pública: {@code ServicioSucesion}, el servicio de completar de la
 * Tarea 4 y sus pruebas la comparten como única fuente de verdad, igual que
 * {@code relevos.CodigosRelevos} / {@code tareas.CodigosTareas}.
 */
public final class CodigosSucesion {

    // ------------------------------------------------------------------
    // Nombres de tabla/dominio (CATALOGO_ESTADOS.NOMBRE_TABLA/NOMBRE_CAMPO)
    // ------------------------------------------------------------------

    public static final String TABLA_PROPUESTA_SUCESION = "PROPUESTA_SUCESION";
    public static final String TABLA_GRUPO_FAMILIAR = "GRUPO_FAMILIAR";
    public static final String TABLA_INTEGRANTE_GRUPO = "INTEGRANTE_GRUPO";
    public static final String TABLA_USUARIO = "USUARIO";
    public static final String CAMPO_ESTADO = "ESTADO";

    /** {@code ACTIVO} de {@code GRUPO_FAMILIAR}, {@code INTEGRANTE_GRUPO} y {@code USUARIO}. */
    public static final String ESTADO_ACTIVO = "ACTIVO";

    // ------------------------------------------------------------------
    // Estados de PROPUESTA_SUCESION.ESTADO (regla 16 de la Fase 7)
    // ------------------------------------------------------------------

    public static final String ESTADO_PENDIENTE = "PENDIENTE";
    public static final String ESTADO_ACEPTADA = "ACEPTADA";
    public static final String ESTADO_RECHAZADA = "RECHAZADA";
    public static final String ESTADO_CANCELADA = "CANCELADA";
    public static final String ESTADO_COMPLETADA = "COMPLETADA";

    // ------------------------------------------------------------------
    // Respaldo de la regla "una sola propuesta activa por grupo" (regla 18)
    // ------------------------------------------------------------------

    /** Índice único (V3) sobre {@code ID_GRUPO} de las propuestas {@code PENDIENTE} o {@code ACEPTADA}. */
    public static final String INDICE_PROPUESTA_ACTIVA = "UX_PROPUESTA_ACTIVA_GRUPO";

    /** Máximo de caracteres de {@code MOTIVO_CANCELACION} ({@code VARCHAR2(500)}). */
    public static final int MAXIMO_MOTIVO_CANCELACION = 500;

    // ------------------------------------------------------------------
    // Auditoría (EVENTO_AUDITORIA, regla 23)
    // ------------------------------------------------------------------

    /** {@code EVENTO_AUDITORIA.NOMBRE_SERVICIO} del módulo. */
    public static final String MODULO = "sucesion";

    /** {@code EVENTO_AUDITORIA.TIPO_ENTIDAD} de las propuestas. */
    public static final String TIPO_ENTIDAD_PROPUESTA = "PROPUESTA_SUCESION";

    public static final String ACCION_PROPONER_SUCESION = "PROPONER_SUCESION";
    public static final String ACCION_ACEPTAR_SUCESION = "ACEPTAR_SUCESION";
    public static final String ACCION_RECHAZAR_SUCESION = "RECHAZAR_SUCESION";
    public static final String ACCION_CANCELAR_SUCESION = "CANCELAR_SUCESION";
    public static final String ACCION_COMPLETAR_SUCESION = "COMPLETAR_SUCESION";

    // ------------------------------------------------------------------
    // Motivos (códigos fijos, nunca texto libre) de los 403 de dominio
    // auditados como RECHAZADA (ronda de corrección final, minor 1)
    // ------------------------------------------------------------------

    /** {@code aceptar}/{@code rechazar}: el actor no es el candidato de la propuesta. */
    public static final String MOTIVO_RECHAZO_NO_ES_CANDIDATO = "NO_ES_CANDIDATO";

    /** {@code proponer}: el actor no es un coordinador activo del grupo. */
    public static final String MOTIVO_RECHAZO_NO_ES_COORDINADOR = "NO_ES_COORDINADOR";

    /** {@code cancelar}: el actor no es el saliente, un coordinador activo del grupo, ni un administrador. */
    public static final String MOTIVO_RECHAZO_NO_ES_SALIENTE_NI_COORDINADOR = "NO_ES_SALIENTE_NI_COORDINADOR";

    /** {@code completar}: el actor no es el saliente ni un administrador. */
    public static final String MOTIVO_RECHAZO_NO_ES_SALIENTE = "NO_ES_SALIENTE";

    private CodigosSucesion() {
    }
}
