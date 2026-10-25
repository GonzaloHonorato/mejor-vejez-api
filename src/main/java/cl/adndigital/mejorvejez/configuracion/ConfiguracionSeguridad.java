package cl.adndigital.mejorvejez.configuracion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

/**
 * API stateless: cada request trae el ID token de Firebase en
 * {@code Authorization: Bearer <token>} y se valida como JWT (ver
 * application.properties). Todo lo que no sea público exige token.
 *
 * <p>{@link ManejadorErroresSeguridad} se registra dos veces porque cubre
 * dos puntos distintos de la cadena: {@code exceptionHandling} atiende el
 * caso sin token (no hay ningún intento de autenticación, así que el
 * {@code AuthenticationEntryPoint} del resource server nunca se invoca) y
 * el acceso denegado (403); {@code oauth2ResourceServer} atiende el token
 * inválido (401), que el filtro del resource server resuelve antes y con
 * su propio {@code AuthenticationEntryPoint} si no se sobrescribe acá.
 */
@Configuration
@EnableWebSecurity
public class ConfiguracionSeguridad {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   ManejadorErroresSeguridad manejadorErroresSeguridad) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/salud").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(manejadorErroresSeguridad)
                .accessDeniedHandler(manejadorErroresSeguridad)
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(manejadorErroresSeguridad)
                .accessDeniedHandler(manejadorErroresSeguridad)
                .jwt(Customizer.withDefaults())
            );
        return http.build();
    }
}
