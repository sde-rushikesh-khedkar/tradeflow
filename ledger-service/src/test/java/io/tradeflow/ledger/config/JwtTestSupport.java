package io.tradeflow.ledger.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Mints JWTs signed by the throwaway test keypair, verifiable against the committed public.pem. */
final class JwtTestSupport {

    private static final String PRIVATE_KEY_PATH = "src/test/resources/keys/private-dev.pem";

    private JwtTestSupport() {
    }

    static String mint(String subject, List<String> roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("roles", roles)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            signedJwt.sign(new RSASSASigner(loadPrivateKey()));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("failed to mint test JWT", e);
        }
    }

    private static RSAPrivateKey loadPrivateKey() throws IOException {
        try (FileInputStream in = new FileInputStream(PRIVATE_KEY_PATH)) {
            return (RSAPrivateKey) RsaKeyConverters.pkcs8().convert(in);
        }
    }

    /**
     * Flips one base64url character inside the signature to invalidate it.
     *
     * <p>Avoids the very last character: for a 256-byte RSA-2048 signature
     * (256 mod 3 == 1), that position only encodes 2 significant bits, so an
     * {@code 'A'}/{@code 'B'} swap there can decode to the same byte and silently
     * leave the signature valid. One character earlier is always fully 6-bit
     * significant, guaranteeing the swap changes the actual signature bytes.
     */
    static String tamper(String token) {
        int index = token.length() - 2;
        char original = token.charAt(index);
        char replacement = original == 'A' ? 'B' : 'A';
        return token.substring(0, index) + replacement + token.substring(index + 1);
    }

}
