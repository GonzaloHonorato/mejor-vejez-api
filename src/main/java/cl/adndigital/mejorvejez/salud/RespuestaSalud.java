package cl.adndigital.mejorvejez.salud;

import java.time.Instant;

public record RespuestaSalud(
        String estado,
        String servicio,
        String version,
        Instant fechaHora,
        Componente baseDatos) {

    public record Componente(String estado, Long latenciaMs) {
    }
}
