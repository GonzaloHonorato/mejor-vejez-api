package cl.adndigital.mejorvejez.comun;

/**
 * Referencia liviana a una persona (usuario) dentro de la respuesta de otro
 * recurso: solo su id y su nombre, sin el resto de {@code USUARIO}
 * (correo, teléfono, estado...). La introduce la consulta de auditoría
 * (Fase 7, Tarea 1) y la reutilizan los relevos y la sucesión (Tareas 2 y
 * 3) para identificar al actor, al saliente, al entrante o al candidato.
 */
public record RespuestaPersona(Long idUsuario, String nombre) {
}
