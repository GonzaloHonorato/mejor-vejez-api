package cl.adndigital.mejorvejez.auditoria;

/**
 * Resultado de la acción registrada en {@code EVENTO_AUDITORIA}. El nombre
 * de cada constante es el código que se busca en el catálogo
 * {@code EVENTO_AUDITORIA.RESULTADO} (ver {@code V2__catalogos.sql}).
 */
public enum ResultadoAuditoria {
    EXITO,
    FALLO,
    RECHAZADA
}
