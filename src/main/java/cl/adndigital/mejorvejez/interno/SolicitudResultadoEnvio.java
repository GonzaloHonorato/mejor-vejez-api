package cl.adndigital.mejorvejez.interno;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de {@code PUT /api/interno/reportes/envios/{idEnvio}/resultado}
 * (Fase 9, TSA-61): el microservicio de Reportes entrega el resultado del
 * envío a cada destinatario de la foto. {@code resultados} no puede venir
 * vacía ni contener elementos nulos.
 */
public record SolicitudResultadoEnvio(
        List<@NotNull(message = "Un resultado de la lista no puede ser nulo.") ResultadoDestinatario> resultados) {
}
