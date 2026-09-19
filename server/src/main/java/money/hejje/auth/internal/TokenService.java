package money.hejje.auth.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import money.hejje.auth.AuthProperties;
import money.hejje.common.security.HejjePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Issues and verifies HS256 access tokens (JWT) and opaque refresh tokens. */
@Component
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    static final String CLAIM_SCOPE = "scope";
    static final String CLAIM_TYPE = "typ";
    static final String CLAIM_USER_ID = "uid";
    static final String TYPE_ACCESS = "access";

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Clock clock;
    private final Duration accessTtl;

    TokenService(AuthProperties properties, Environment environment, AuthTime time) {
        this.clock = time.clock();
        this.accessTtl = properties.accessTokenTtl();
        SecretKey key = new SecretKeySpec(resolveSecret(properties, environment), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        this.decoder.setJwtValidator(timestamps);
    }

    private static byte[] resolveSecret(AuthProperties properties, Environment environment) {
        String configured = properties.jwtSecret();
        if (configured != null && !configured.isBlank()) {
            byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("hejje.auth.jwt-secret must be at least 32 bytes");
            }
            return bytes;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("HEJJE_JWT_SECRET is required in prod");
        }
        log.warn("hejje.auth.jwt-secret not set; using a random per-start secret (tokens expire on restart)");
        return Secrets.randomToken(48).getBytes(StandardCharsets.UTF_8);
    }

    public String issueAccessToken(HejjePrincipal principal) {
        return issueAccessToken(principal, clock.instant(), accessTtl);
    }

    /** Issues a token with explicit timing; visible for tests that need expired tokens. */
    public String issueAccessToken(HejjePrincipal principal, Instant issuedAt, Duration ttl) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(principal.name())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .claim(CLAIM_USER_ID, principal.id().toString())
                .claim(CLAIM_SCOPE, String.join(" ", new java.util.TreeSet<>(principal.scopes())))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Instant accessTokenExpiry(Instant issuedAt) {
        return issuedAt.plus(accessTtl);
    }

    /** Verifies signature, expiry and token type; empty when invalid. */
    public Optional<HejjePrincipal> verifyAccessToken(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            if (!TYPE_ACCESS.equals(jwt.getClaimAsString(CLAIM_TYPE))) {
                return Optional.empty();
            }
            String scope = jwt.getClaimAsString(CLAIM_SCOPE);
            Set<String> scopes = scope == null || scope.isBlank() ? Set.of() : new LinkedHashSet<>(Arrays.asList(scope.split(" ")));
            return Optional.of(new HejjePrincipal(UUID.fromString(jwt.getClaimAsString(CLAIM_USER_ID)), jwt.getSubject(),
                    HejjePrincipal.Type.USER, scopes));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String newRefreshToken() {
        return Secrets.randomToken(32);
    }

    public String hashRefreshToken(String token) {
        return Secrets.sha256Hex(token);
    }
}
