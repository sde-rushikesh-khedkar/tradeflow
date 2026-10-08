package io.tradeflow.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import net.devh.boot.grpc.server.security.authentication.BearerAuthenticationReader;
import net.devh.boot.grpc.server.security.authentication.GrpcAuthenticationReader;

/**
 * Bridges JWT auth into gRPC — Tomcat's filter chain never runs for this transport.
 *
 * <p>These two beans existing is the entire trigger: grpc-server-spring-boot-starter
 * auto-registers {@code DefaultAuthenticatingServerInterceptor} globally once both
 * are present, which authenticates each call and populates {@code SecurityContextHolder}
 * — that's what makes @PreAuthorize work on {@code LedgerGrpcService}.
 *
 * <p>Reuses {@code AppConfig}'s {@code JwtDecoder}/{@code JwtAuthenticationConverter}
 * so HTTP and gRPC validate tokens identically.
 */
@Configuration
public class GrpcSecurityConfig {

    /** Validates the bearer token read off a gRPC call — same decode+convert steps as HTTP. */
    @Bean
    public AuthenticationManager grpcAuthenticationManager(
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtDecoder);
        provider.setJwtAuthenticationConverter(jwtAuthenticationConverter);
        return new ProviderManager(provider);
    }

    /** Reads the bearer token off gRPC call metadata — gRPC's equivalent of reading the Authorization header. */
    @Bean
    public GrpcAuthenticationReader grpcAuthenticationReader() {
        return new BearerAuthenticationReader(BearerTokenAuthenticationToken::new);
    }

}
