package com.interview.platform.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for the Interview Platform.
 *
 * <h3>P0 Changes from prototype:</h3>
 * <ul>
 * <li><strong>Actuator lockdown:</strong> Previously {@code /actuator/**} was
 * {@code permitAll()} with {@code show-details=always}, leaking DB connection
 * status, disk space, and environment info to anonymous users. Now restricted
 * to ADMIN role only. The {@code show-details} setting is controlled in
 * {@code application.properties} via {@code when_authorized}.</li>
 * <li><strong>Configurable CORS:</strong> Previously hardcoded to
 * {@code http://localhost:3000}. Now reads from
 * {@code app.cors.allowed-origins}
 * property (comma-separated), defaulting to localhost:3000 for dev.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Comma-separated list of allowed CORS origins.
     * Set via CORS_ALLOWED_ORIGINS env var or app.cors.allowed-origins property.
     * Default: http://localhost:3000
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * P3-12: Fail-fast validation for CORS configuration.
     *
     * <p>{@code setAllowCredentials(true)} is incompatible with wildcard
     * origins ({@code *}). Spring will throw an error at runtime on the first
     * CORS preflight request, but only at that point — not at startup. This
     * means an operator deploying with {@code CORS_ALLOWED_ORIGINS=*} won't
     * discover the misconfiguration until the first browser request.</p>
     *
     * <p>This {@code @PostConstruct} check catches the problem at startup,
     * giving the operator immediate feedback in the logs.</p>
     */
    @PostConstruct
    public void validateCorsConfig() {
        if (allowedOrigins.contains("*")) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGINS cannot contain '*' when credentials are enabled "
                            + "(setAllowCredentials=true). Specify explicit origins instead, e.g.: "
                            + "CORS_ALLOWED_ORIGINS=http://localhost:3000,https://myapp.example.com");
        }
        log.info("CORS configured with allowed origins: {}", allowedOrigins);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json");
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write(
                                    "{\"error\":\"Unauthorized\",\"message\":\"Authentication required\",\"status\":401}");
                        }))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — no authentication required
                        .requestMatchers("/api/auth/**").permitAll()
                        // Job roles list is read-only and needed before login on the start screen
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/job-roles").permitAll()
                        // File serving — requires authentication + ownership checks in FileController
                        // Previously permitAll() which exposed all videos/resumes to unauthenticated users (P0-2/P0-3)
                        .requestMatchers("/api/files/**").authenticated()
                        // Actuator — health endpoint is public (details are gated
                        // by show-details=when_authorized in properties), but all
                        // other actuator endpoints require ADMIN role.
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Everything else requires authentication
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration)
            throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Parse comma-separated origins from property
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        configuration.setAllowedOrigins(origins);

        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setExposedHeaders(Arrays.asList("X-Total-Count", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
