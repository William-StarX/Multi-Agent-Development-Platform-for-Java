package io.jdda.controlcenter;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.stereotype.Component;

@Component
class ConsoleAccess {
    record Pairing(String code, Instant expiresAt) {}
    record Session(String token, Instant expiresAt) {}

    private static final String SESSION_PREFIX = "jdda-ui-";
    private static final Duration PAIRING_LIFETIME = Duration.ofSeconds(60);
    private static final Duration SESSION_LIFETIME = Duration.ofMinutes(30);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> pairings = new HashMap<>();
    private final Map<String, Instant> sessions = new HashMap<>();

    synchronized Pairing issue() {
        Instant now = Instant.now();
        pairings.values().removeIf(expiry -> !expiry.isAfter(now));
        if (pairings.size() >= 32) throw new ApiFailure(429, "CONSOLE_LIMIT", "Wait for existing pairing codes to expire.");
        String code = secret();
        Instant expiry = now.plus(PAIRING_LIFETIME);
        pairings.put(AgentIdentity.hash(code), expiry);
        return new Pairing(code, expiry);
    }

    synchronized Session redeem(String code) {
        Instant now = Instant.now();
        Instant expiry = pairings.remove(AgentIdentity.hash(code));
        if (expiry == null || !expiry.isAfter(now))
            throw new ApiFailure(401, "INVALID_PAIRING", "The pairing code has expired or was already used.");
        sessions.values().removeIf(end -> !end.isAfter(now));
        if (sessions.size() >= 64) throw new ApiFailure(429, "CONSOLE_LIMIT", "Wait for existing console sessions to expire.");
        String token = SESSION_PREFIX + secret();
        Instant end = now.plus(SESSION_LIFETIME);
        sessions.put(AgentIdentity.hash(token), end);
        return new Session(token, end);
    }

    synchronized DefaultOAuth2AuthenticatedPrincipal authenticate(String token) {
        if (!token.startsWith(SESSION_PREFIX)) return null;
        String fingerprint = AgentIdentity.hash(token);
        Instant end = sessions.get(fingerprint);
        if (end == null) return null;
        if (!end.isAfter(Instant.now())) {
            sessions.remove(fingerprint);
            return null;
        }
        return new DefaultOAuth2AuthenticatedPrincipal("local-console", Map.of("sub", "local-console"),
                List.of(new SimpleGrantedAuthority("ROLE_CONSOLE")));
    }

    private String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
