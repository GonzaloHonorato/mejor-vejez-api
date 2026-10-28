package cl.adndigital.mejorvejez.notificaciones;

/**
 * Canal operativo de un usuario (regla 8 de la Fase 5): habilitado y
 * disponible en el Core para ese canal. {@code destino} es el correo o
 * teléfono resuelto; cuando el usuario no tiene fila {@code CORREO}, es el
 * {@code USUARIO.EMAIL} de la cuenta. Lo produce {@link
 * ServicioCanales#canalesOperativos} para que {@code interno} sepa, al
 * reservar los intentos de una alerta, a qué canales corresponde entregar
 * un mensaje (el envío en sí ya no lo hace el Core, sino el microservicio de
 * Alertas).
 */
public record CanalOperativo(String canal, String destino) {
}
