package com.payflow.auth.security;

import com.payflow.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class TokenManager {

    public enum TokenType {
        ACCESS,
        REFRESH
    }

    private final SecretKey secretKey;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public TokenManager(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-expiration-ms:900000}") long accessExpirationMs,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(User user) {
        return generateToken(user, accessExpirationMs, TokenType.ACCESS);
    }

    public String generateRefreshToken(User user) {
        return generateToken(user, refreshExpirationMs, TokenType.REFRESH);
    }

    private String generateToken(User user, long expirationMs, TokenType type) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole().name());
        claims.put("type", type.name());
        claims.put("userId", user.getId());
        claims.put("ownerId", user.getOwnerId().toString());
        // The handle travels in the token so payment-service can label the
        // sender without a service call on every transfer. It is a convenience
        // copy, not the source of truth: auth-service still owns the mapping and
        // payment-service resolves the recipient through it.
        claims.put("handle", user.getDisplayUsername());

        Instant now = Instant.now();
        return Jwts.builder()
                .claims(claims)
                .subject(user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public Claims verifyAndExtractClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return verifyAndExtractClaims(token).getSubject();
    }

    public Instant getExpiration(String token) {
        return verifyAndExtractClaims(token).getExpiration().toInstant();
    }

    public boolean isTokenExpired(String token) {
        return verifyAndExtractClaims(token).getExpiration().before(new Date());
    }

    public boolean isTokenValid(String token, TokenType expectedType) {
        try {
            Claims claims = verifyAndExtractClaims(token);
            String type = claims.get("type", String.class);
            return expectedType.name().equals(type) && !claims.getExpiration().before(new Date());
        } catch (Exception ex) {
            return false;
        }
    }
}
