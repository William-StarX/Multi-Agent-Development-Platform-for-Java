package io.jdda.controlcenter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class ApiSecurity {
    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper,
            AgentIdentity identities, ConsoleAccess console,
            @Value("${JDDA_ADMIN_TOKEN}") String token) throws Exception {
        if (token.length() < 32) {
            throw new IllegalArgumentException("JDDA_ADMIN_TOKEN must contain at least 32 characters.");
        }
        var unauthorized = new org.springframework.security.web.AuthenticationEntryPoint() {
            public void commence(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,
                    org.springframework.security.core.AuthenticationException error) throws java.io.IOException {
                response.setStatus(401);
                response.setHeader("WWW-Authenticate", "Bearer");
                response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(),
                        ApiFailure.body("UNAUTHENTICATED", "A valid credential is required."));
            }
        };
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.contentSecurityPolicy(policy -> policy.policyDirectives(
                        "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                                + "base-uri 'none'; frame-ancestors 'none'; form-action 'none'")))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/console.js", "/console.css").permitAll()
                        .requestMatchers(HttpMethod.POST, "/_console/session").permitAll()
                        .requestMatchers(HttpMethod.GET, "/_console/projects", "/_console/projects/*/tasks/*/revisions/*/trace",
                                "/api/v1/projects/*/library/tasks",
                                "/api/v1/projects/*/dispatch").hasAnyAuthority("ROLE_ADMIN", "ROLE_CONSOLE")
                        .requestMatchers(HttpMethod.POST, "/_console/projects/*/tasks/*/revisions/*/guidance")
                                .hasAnyAuthority("ROLE_ADMIN", "ROLE_CONSOLE")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/projects/*/dispatch").hasAnyAuthority("ROLE_ADMIN", "ROLE_CONSOLE")
                        .requestMatchers("/api/v1/projects/*/worker/**").hasAuthority("ROLE_WORKER")
                        .requestMatchers("/api/v1/projects/*/bugfix/claims").hasAuthority("ROLE_BUGFIX")
                        .requestMatchers(HttpMethod.GET, "/api/v1/projects/*/attempts/*/instructions")
                                .hasAnyAuthority("ROLE_WORKER", "ROLE_BUGFIX")
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/attempts/*/started",
                                "/api/v1/projects/*/attempts/*/heartbeat", "/api/v1/projects/*/attempts/*/results",
                                "/api/v1/projects/*/attempts/*/stopped").hasAnyAuthority("ROLE_WORKER", "ROLE_BUGFIX")
                        .anyRequest().hasAuthority("ROLE_ADMIN"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized))
                .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(unauthorized)
                        .opaqueToken(opaque -> opaque.introspector(presented -> {
                            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                                    presented.getBytes(StandardCharsets.UTF_8))) {
                                var session = console.authenticate(presented);
                                return session != null ? session : identities.authenticate(presented);
                            }
                            return new DefaultOAuth2AuthenticatedPrincipal("local-admin",
                                    Map.of("sub", "local-admin"),
                                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                        })))
                .build();
    }
}
