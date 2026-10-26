package cl.adndigital.mejorvejez.notificaciones;

/**
 * Canal operativo de un usuario (regla 8 de la Fase 5): habilitado y con un
 * {@link EnviadorNotificacion} disponible para ese canal. {@code destino}
 * es el correo o teléfono resuelto; cuando el usuario no tiene fila {@code
 * CORREO}, es el {@code USUARIO.EMAIL} de la cuenta. Lo produce {@link
 * ServicioCanales#canalesOperativos} para que los módulos que envían
 * notificaciones (p. ej. {@code alertas}) sepan a qué canales entregar un
 * mensaje, en una sola consulta por lote.
 */
public record CanalOperativo(String canal, String destino) {
}
