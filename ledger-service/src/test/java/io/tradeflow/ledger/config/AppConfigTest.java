package io.tradeflow.ledger.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AppConfig}'s JWT beans, using a real token signed with the
 * throwaway test keypair ({@link JwtTestSupport}) verified against the committed public key.
 */
@DisplayName("AppConfig")
class AppConfigTest {

    private final AppConfig appConfig = new AppConfig();
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        RSAPublicKey rsaPublicKey = appConfig.rsaPublicKey(new ClassPathResource("keys/public.pem"));
        jwtDecoder = appConfig.jwtDecoder(rsaPublicKey);
    }

    @Nested
    @DisplayName("jwtDecoder()")
    class JwtDecoderTests {

        @Test
        @DisplayName("decodes a token signed by the matching private key")
        void decodesValidToken() {
            String token = JwtTestSupport.mint("merchant-42", List.of("MERCHANT"));

            Jwt jwt = jwtDecoder.decode(token);

            assertEquals("merchant-42", jwt.getSubject());
        }

        @Test
        @DisplayName("rejects a token with a tampered signature")
        void rejectsTamperedToken() {
            String token = JwtTestSupport.mint("merchant-42", List.of("MERCHANT"));
            String tampered = JwtTestSupport.tamper(token);

            assertThrows(JwtException.class, () -> jwtDecoder.decode(tampered));
        }
    }

    @Nested
    @DisplayName("jwtAuthenticationConverter()")
    class JwtAuthenticationConverterTests {

        @Test
        @DisplayName("maps the roles claim to ROLE_-prefixed authorities")
        void mapsRolesToAuthorities() {
            JwtAuthenticationConverter converter = appConfig.jwtAuthenticationConverter();
            Jwt jwt = jwtDecoder.decode(JwtTestSupport.mint("merchant-42", List.of("MERCHANT", "ADMIN")));

            AbstractAuthenticationToken authentication = converter.convert(jwt);

            assertTrue(authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_MERCHANT")));
            assertTrue(authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        }
    }

}
