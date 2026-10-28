package cl.adndigital.mejorvejez.configuracion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import cl.adndigital.mejorvejez.comun.ClaveServicio;
import cl.adndigital.mejorvejez.seguridad.ManejadorErroresSeguridad;

import tools.jackson.databind.json.JsonMapper;

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
 *
 * <p>Fase 9: {@code /api/interno/**} (lo consumen los microservicios de
 * Alertas y Reportes) tiene su PROPIA cadena ({@link #cadenaInterna},
 * {@code @Order(1)}, así que se evalúa antes de esta) protegida solo por
 * {@link FiltroClaveServicio}: no configura {@code oauth2ResourceServer},
 * por lo que un JWT de usuario no abre esas rutas. La cadena de esta clase
 * pasa a {@code @Order(2)}.
 *
 * <p>{@link ClaveServicio} se construye directamente con el valor resuelto
 * por {@code @Value} en vez de inyectarse como bean: así ningún otro
 * contexto de prueba que solo importe esta clase (hay varios {@code
 * @WebMvcTest} que importan {@code ConfiguracionSeguridad} sin conocer
 * Fase 9) tiene que proveer un bean nuevo.
 */
@Configuration
@EnableWebSecurity
public class ConfiguracionSeguridad {

    @Bean
    @Order(1)
    public SecurityFilterChain cadenaInterna(HttpSecurity http,
            @Value("${mejorvejez.interno.clave-servicio}") String claveServicioConfigurada,
            JsonMapper jsonMapper) throws Exception {
        ClaveServicio claveServicio = new ClaveServicio(claveServicioConfigurada);
        http
            .securityMatcher("/api/interno/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .addFilterBefore(new FiltroClaveServicio(claveServicio, jsonMapper),
                    UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
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
