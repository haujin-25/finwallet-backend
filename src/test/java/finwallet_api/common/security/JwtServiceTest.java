package finwallet_api.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private static final long EXPIRATION_SECONDS = 900;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION_SECONDS);
    }

    @Test
    void generatesValidTokenAndExtractsClaims() {
        String email = "trader@finwallet.test";
        Long userId = 42L;

        String token = jwtService.generateToken(email, userId);

        assertNotNull(token);
        assertTrue(jwtService.validateToken(token));
        assertEquals(email, jwtService.extractEmail(token));
        assertEquals(userId, jwtService.extractUserId(token));
        assertEquals(EXPIRATION_SECONDS, jwtService.getExpirationSeconds());
    }

    @Test
    void rejectsExpiredToken() {
        String email = "expired@finwallet.test";
        // Negative expiration -> expired token
        String token = jwtService.generateToken(email, 1L, -1000L);

        assertFalse(jwtService.validateToken(token));
        assertThrows(ExpiredJwtException.class, () -> jwtService.extractEmail(token));
    }

    @Test
    void rejectsTamperedTokenSignature() {
        String token = jwtService.generateToken("alice@finwallet.test", 2L);

        // Tamper with the signature (last part of JWT)
        String tamperedToken = token.substring(0, token.lastIndexOf('.') + 1) + "invalidSignature";

        assertFalse(jwtService.validateToken(tamperedToken));
        assertThrows(SignatureException.class, () -> jwtService.extractEmail(tamperedToken));
    }

    @Test
    void rejectsMalformedToken() {
        String malformedToken = "not.a.valid.jwt.token";

        assertFalse(jwtService.validateToken(malformedToken));
        assertThrows(MalformedJwtException.class, () -> jwtService.extractEmail(malformedToken));
    }
}
