package io.tradeflow.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;

/**
 * RSA key, JWT validation, and HTTP security config for ledger-service.
 *
 * <p>{@code JwtDecoder}/{@code JwtAuthenticationConverter} are standalone beans
 * (not inlined into {@link #securityFilterChain}) so {@code GrpcSecurityConfig}
 * can reuse them for gRPC auth.
 *
 * <p>{@code proxyTargetClass = true}: required because {@code LedgerGrpcService}
 * extends a generated class, not an interface — without it, @PreAuthorize can
 * silently fail to apply.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(proxyTargetClass = true)
public class AppConfig {

    private static final String ROLES_CLAIM = "roles";
    private static final String ROLE_AUTHORITY_PREFIX = "ROLE_";

    /** Loads the RSA public key used to verify JWT signatures. Private key stays in gateway-service. */
    @Bean
    public RSAPublicKey rsaPublicKey(
            @Value("${app.security.rsa.public-key-location}") Resource location)
            throws IOException {
        try (InputStream inputStream = location.getInputStream()) {
            return (RSAPublicKey) RsaKeyConverters.x509().convert(inputStream);
        }
    }

    /** Verifies JWT signatures. Shared bean — same instance used by HTTP and gRPC auth. */
    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey rsaPublicKey) {
        return NimbusJwtDecoder.withPublicKey(rsaPublicKey).build();
    }

    /** Maps the JWT's {@code roles} claim to {@code ROLE_*} authorities. Shared bean, same as above. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName(ROLES_CLAIM);
        authoritiesConverter.setAuthorityPrefix(ROLE_AUTHORITY_PREFIX);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    /** Stateless JWT resource server: no session, no CSRF (no cookies = no CSRF risk), bearer token required. */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    JwtDecoder jwtDecoder,
                                                    JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }

}
