package io.tradeflow.ledger.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Mints JWTs signed by an in-memory RSA keypair generated fresh per JVM — no file on
 * disk, no key material ever committed to git. {@link #publicKey()} is what test
 * decoders must verify against; it has nothing to do with the real {@code public.pem}
 * shipped for the actual app (that one pairs with gateway-service's future private key).
 */
final class JwtTestSupport {

    private static final int KEY_SIZE_BITS = 2048;
    private static final long TOKEN_TTL_SECONDS = 300;

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private JwtTestSupport() {
    }

    static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEY_PAIR.getPublic();
    }

    static String mint(String subject, List<String> roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("roles", roles)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(TOKEN_TTL_SECONDS)))
                    .build();
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            signedJwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
            return signedJwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("failed to mint test JWT", e);
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

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available", e);
        }
    }

}
