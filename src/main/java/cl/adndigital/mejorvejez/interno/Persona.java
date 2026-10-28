package cl.adndigital.mejorvejez.interno;

/**
 * Referencia liviana a una persona (usuario) dentro de {@link DatosSemana}
 * (Fase 9, TSA-61, contrato "Core → lo consume Reportes"): solo su id y su
 * nombre. La usan {@link TareaSemana} (confirmador, responsable) y {@link
 * RelevoSemana} (saliente, entrante).
 */
public record Persona(Long idUsuario, String nombre) {
}
