package io.tradeflow.ledger.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GrpcSecurityConfig}'s {@code AuthenticationManager} — proves the
 * gRPC path authenticates a bearer token identically to the HTTP path, reusing the same
 * {@link AppConfig} decoder/converter beans.
 */
@DisplayName("GrpcSecurityConfig")
class GrpcSecurityConfigTest {

    private final AppConfig appConfig = new AppConfig();
    private final GrpcSecurityConfig grpcSecurityConfig = new GrpcSecurityConfig();
    private AuthenticationManager authenticationManager;

    @BeforeEach
    void setUp() {
        JwtDecoder jwtDecoder = appConfig.jwtDecoder(JwtTestSupport.publicKey());
        JwtAuthenticationConverter converter = appConfig.jwtAuthenticationConverter();
        authenticationManager = grpcSecurityConfig.grpcAuthenticationManager(jwtDecoder, converter);
    }

    @Nested
    @DisplayName("grpcAuthenticationManager()")
    class GrpcAuthenticationManagerTests {

        @Test
        @DisplayName("authenticates a valid bearer token and maps roles to authorities")
        void authenticatesValidToken() {
            String token = JwtTestSupport.mint("merchant-42", List.of("MERCHANT"));

            Authentication result = authenticationManager.authenticate(new BearerTokenAuthenticationToken(token));

            assertTrue(result.isAuthenticated());
            assertTrue(result.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_MERCHANT")));
        }

        @Test
        @DisplayName("rejects a token with an invalid signature")
        void rejectsInvalidToken() {
            String token = JwtTestSupport.mint("merchant-42", List.of("MERCHANT"));
            String tampered = JwtTestSupport.tamper(token);

            assertThrows(InvalidBearerTokenException.class, () ->
                    authenticationManager.authenticate(new BearerTokenAuthenticationToken(tampered)));
        }
    }

}
