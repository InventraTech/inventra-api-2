package com.inventra.api.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtService {

    public static final String USER_ID_CLAIM = "uid";
    public static final String PASSWORD_FINGERPRINT_CLAIM = "pwd";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    public String generateToken(UserPrincipal principal) {
        Instant now = Instant.now();
        String authority = principal.getAuthorities().iterator().next().getAuthority();

        return Jwts.builder()
                .subject(principal.getUsername())
                .claim(USER_ID_CLAIM, principal.getUser().getId().toString())
                .claim("role", authority)
                .claim(PASSWORD_FINGERPRINT_CLAIM, passwordFingerprint(principal.getPassword()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // Valida assinatura e expiração; lança JwtException/IllegalArgumentException se o token não presta.
    public Claims parse(String token) {
        return parseClaims(token);
    }

    public String extractSubject(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    // Impressão digital curta do hash da senha: muda quando a senha muda, e com isso todos os tokens
    // emitidos antes da troca deixam de valer. Não expõe o hash (é um SHA-256 dele, truncado).
    // Conta sem senha (login com Google) usa um valor fixo; se ela criar uma senha, os tokens antigos caem.
    public static String passwordFingerprint(String passwordHash) {
        try {
            String source = passwordHash == null ? "" : passwordHash;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
