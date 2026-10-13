package com.inventra.api.infrastructure.security;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import com.inventra.api.infrastructure.exception.ProblemDetailFactory;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                authenticate(token, request);
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException ex) {
                SecurityContextHolder.clearContext();
            } catch (DataAccessException ex) {
                // Banco fora do ar: responde no mesmo formato de erro da API, em vez de um 500 cru do container.
                log.error("Falha ao carregar o usuário do token", ex);
                ProblemDetailFactory.writeTo(response, HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                        "Serviço temporariamente indisponível. Tente novamente.");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void authenticate(String token, HttpServletRequest request) {
        Claims claims = jwtService.parse(token);
        String rawUserId = claims.get(JwtService.USER_ID_CLAIM, String.class);
        if (rawUserId == null) {
            return;
        }
        UUID userId = UUID.fromString(rawUserId);
        UserPrincipal principal = (UserPrincipal) userDetailsService.loadUserById(userId);

        // Conta desativada ou senha trocada depois da emissão: o token deixa de valer na hora.
        if (!principal.isEnabled()) {
            return;
        }
        String fingerprint = claims.get(JwtService.PASSWORD_FINGERPRINT_CLAIM, String.class);
        if (!JwtService.passwordFingerprint(principal.getPassword()).equals(fingerprint)) {
            return;
        }

        var authToken = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
