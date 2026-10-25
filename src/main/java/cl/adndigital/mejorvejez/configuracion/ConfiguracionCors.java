package cl.adndigital.mejorvejez.configuracion;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import cl.adndigital.mejorvejez.comun.FiltroCorrelacion;

@Configuration
public class ConfiguracionCors {

    @Value("${app.cors.allowed-origins}")
    private String origenesPermitidos;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origenesPermitidos.split(","))
                .map(String::trim)
                .filter(origen -> !origen.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Id-Correlacion: el front puede enviarla y leerla (FiltroCorrelacion la devuelve).
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", FiltroCorrelacion.CABECERA_ID_CORRELACION));
        config.setExposedHeaders(List.of(FiltroCorrelacion.CABECERA_ID_CORRELACION));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
