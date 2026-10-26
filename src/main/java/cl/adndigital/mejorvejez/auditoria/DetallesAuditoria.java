package cl.adndigital.mejorvejez.auditoria;

import java.util.LinkedHashMap;
import java.util.Map;

import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;

/**
 * Trazabilidad de administrador compartida por los servicios que mutan
 * datos de un grupo (HU-03, HU-04...): si el actor mutó como administrador
 * sin membresía activa en el grupo ({@code contexto.rol() == null}), los
 * detalles de auditoría de la mutación deben dejarlo explícito con {@code
 * "accesoAdministrador": true}. Pública (movida desde {@code adultos}) para
 * que cualquier módulo que audite por grupo la reutilice sin duplicar la
 * lógica.
 */
public final class DetallesAuditoria {

    private DetallesAuditoria() {
    }

    /**
     * Agrega {@code "accesoAdministrador": true} a {@code extra} si el actor
     * mutó como administrador sin membresía en el grupo ({@code
     * contexto.rol() == null}); si no, devuelve {@code extra} tal cual
     * (incluido {@code null}).
     */
    public static Map<String, Object> conTrazabilidad(ContextoGrupo contexto, Map<String, Object> extra) {
        return conTrazabilidad(esAccesoAdministrador(contexto), extra);
    }

    /**
     * {@code true} si el actor mutó como administrador sin membresía en el
     * grupo ({@code contexto.rol() == null}): la misma regla de {@link
     * #conTrazabilidad(ContextoGrupo, Map)}, para quien deba transportarla
     * (p. ej. en un evento de dominio) y auditar después sin el contexto.
     */
    public static boolean esAccesoAdministrador(ContextoGrupo contexto) {
        return contexto.rol() == null;
    }

    /**
     * Igual que {@link #conTrazabilidad(ContextoGrupo, Map)}, pero con la
     * regla ya resuelta ({@link #esAccesoAdministrador}): si {@code
     * accesoAdministrador} es {@code true}, agrega {@code
     * "accesoAdministrador": true} a una copia de {@code extra}; si no,
     * devuelve {@code extra} tal cual (incluido {@code null}).
     */
    public static Map<String, Object> conTrazabilidad(boolean accesoAdministrador, Map<String, Object> extra) {
        if (!accesoAdministrador) {
            return extra;
        }
        Map<String, Object> conTrazabilidad = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
        conTrazabilidad.put("accesoAdministrador", true);
        return conTrazabilidad;
    }
}
