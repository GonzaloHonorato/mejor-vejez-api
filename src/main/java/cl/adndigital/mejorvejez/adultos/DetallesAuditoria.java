package cl.adndigital.mejorvejez.adultos;

import java.util.LinkedHashMap;
import java.util.Map;

import cl.adndigital.mejorvejez.seguridad.ContextoGrupo;

/**
 * Trazabilidad de administrador compartida por {@link ServicioAdultosMayores}
 * y {@link ServicioContactos} (HU-03): si el actor mutó como administrador
 * sin membresía activa en el grupo ({@code contexto.rol() == null}), los
 * detalles de auditoría de la mutación deben dejarlo explícito con {@code
 * "accesoAdministrador": true}. Extraído acá para que ambos servicios no
 * dupliquen la misma lógica.
 */
final class DetallesAuditoria {

    private DetallesAuditoria() {
    }

    /**
     * Agrega {@code "accesoAdministrador": true} a {@code extra} si el actor
     * mutó como administrador sin membresía en el grupo ({@code
     * contexto.rol() == null}); si no, devuelve {@code extra} tal cual
     * (incluido {@code null}).
     */
    static Map<String, Object> conTrazabilidad(ContextoGrupo contexto, Map<String, Object> extra) {
        if (contexto.rol() != null) {
            return extra;
        }
        Map<String, Object> conTrazabilidad = extra == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extra);
        conTrazabilidad.put("accesoAdministrador", true);
        return conTrazabilidad;
    }
}
