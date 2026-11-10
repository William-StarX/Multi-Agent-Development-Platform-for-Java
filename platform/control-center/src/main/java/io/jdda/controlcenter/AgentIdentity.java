package io.jdda.controlcenter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.stereotype.Service;

@Service
class AgentIdentity {
    private final JdbcTemplate jdbc;
    AgentIdentity(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    DefaultOAuth2AuthenticatedPrincipal authenticate(String token) {
        var rows = jdbc.query("""
                SELECT c.project_id,c.agent_id,
                    CASE WHEN a.body->'roles' ?? 'BUGFIX' THEN 'ROLE_BUGFIX' ELSE 'ROLE_WORKER' END AS authority
                FROM jdda.agent_credential c
                JOIN jdda.agent a USING(project_id,agent_id)
                WHERE c.token_hash=? AND a.body->>'status'='ACTIVE'
                AND (a.body->'roles' ?? 'WORKER' OR (a.body->'roles' ?? 'BUGFIX' AND c.execution_profile IS NOT NULL))
                """, (rs, row) -> new DefaultOAuth2AuthenticatedPrincipal(rs.getString("agent_id"),
                        Map.of("projectId", rs.getString("project_id"), "agentId", rs.getString("agent_id")),
                        List.of(new SimpleGrantedAuthority(rs.getString("authority")))), hash(token));
        if (rows.isEmpty()) throw new BadOpaqueTokenException("Invalid credential");
        return rows.getFirst();
    }
}
