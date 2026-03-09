package com.interview.platform.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${app.jwtSecret}")
    private String jwtSecret;

    @Value("${app.jwtExpirationInMs}")
    private int jwtExpirationInMs;

    /**
     * Fail-fast validation: ensure JWT_SECRET is set and meets minimum length.
     * Without this, the app would start with an insecure or missing secret.
     */
    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set. The application cannot start without a secure signing key. "
                            + "Generate one with: openssl rand -base64 64");
        }
        if (jwtSecret.length() < 64) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least 64 characters for HS512 security. "
                            + "Current length: " + jwtSecret.length() + ". "
                            + "Generate one with: openssl rand -base64 64");
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generate a JWT token with email, userId, and role claims.
     *
     * <p>P2-13: The {@code role} claim is now included in the token so that
     * {@code JwtAuthenticationFilter} can populate granted authorities.
     * This enables the ADMIN role check in SecurityConfig for actuator
     * endpoints and future admin-only features (e.g., JobRole CRUD).</p>
     *
     * @param email  the user's email (used as the JWT subject)
     * @param userId the user's database ID
     * @return the signed JWT token string
     */
    public String generateToken(String email, Long userId) {
        return generateToken(email, userId, null);
    }

    /**
     * Generate a JWT token with email, userId, and role claims.
     *
     * @param email  the user's email (used as the JWT subject)
     * @param userId the user's database ID
     * @param role   the user's role (e.g., "USER", "ADMIN"), or null for no role
     * @return the signed JWT token string
     */
    public String generateToken(String email, Long userId, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationInMs);

        var builder = Jwts.builder()
                .setSubject(email)
                .claim("userId", userId)
                .setIssuedAt(now)
                .setExpiration(expiryDate);

        // P2-13: Include role claim if present
        if (role != null && !role.isBlank()) {
            builder.claim("role", role);
        }

        return builder.signWith(getSigningKey(), SignatureAlgorithm.HS512)
                .compact();
    }

    public String getUsernameFromJWT(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();

        return claims.getSubject();
    }

    public Long getUserIdFromJWT(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();

        return claims.get("userId", Long.class);
    }

    /**
     * Extract the user's role from the JWT token (P2-13).
     *
     * <p>Returns the value of the {@code role} claim, or {@code null} if
     * the claim is not present (e.g., tokens issued before the role system
     * was added). The caller should handle a null return gracefully.</p>
     *
     * @param token the JWT token string
     * @return the role string (e.g., "USER", "ADMIN"), or null if absent
     */
    public String getRoleFromJWT(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();

        return claims.get("role", String.class);
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parserBuilder().setSigningKey(getSigningKey()).build().parseClaimsJws(authToken);
            return true;
        } catch (SecurityException | MalformedJwtException ex) {
            logger.error("Invalid JWT signature");
        } catch (ExpiredJwtException ex) {
            logger.error("Expired JWT token");
        } catch (UnsupportedJwtException ex) {
            logger.error("Unsupported JWT token");
        } catch (IllegalArgumentException ex) {
            logger.error("JWT claims string is empty");
        }
        return false;
    }
}
