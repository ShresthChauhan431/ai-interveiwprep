package com.interview.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT authentication filter that extracts and validates JWT tokens from requests.
 *
 * <h3>P0-5 Fix:</h3>
 * <p>Previously, this filter accepted JWT tokens from URL query parameters
 * ({@code ?token=...}) to support SSE {@code EventSource} connections which
 * cannot set custom headers. This leaked the full JWT into browser history,
 * server access logs, reverse proxy logs, and Referer headers.</p>
 *
 * <p>The query parameter extraction has been removed. SSE endpoints should
 * use a short-lived, single-use ticket mechanism instead. The frontend
 * requests a ticket via {@code POST /api/interviews/{id}/sse-ticket},
 * then passes it as {@code ?ticket=...}. The ticket is validated and
 * consumed by the SSE controller, not by this filter.</p>
 *
 * <h3>P2-13 Fix:</h3>
 * <p>Previously, authentication was created with {@code Collections.emptyList()}
 * (no granted authorities), making the ADMIN role in SecurityConfig unassignable.
 * Now reads a {@code role} claim from the JWT and populates granted authorities.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && tokenProvider.validateToken(jwt)) {
                String username = tokenProvider.getUsernameFromJWT(jwt);
                Long userId = tokenProvider.getUserIdFromJWT(jwt);
                String role = tokenProvider.getRoleFromJWT(jwt);

                // Set userId as request attribute for controllers to access
                if (userId != null) {
                    request.setAttribute("userId", userId);
                }

                // Build granted authorities from the role claim (P2-13)
                var authorities = new java.util.ArrayList<org.springframework.security.core.GrantedAuthority>();
                if (role != null && !role.isBlank()) {
                    authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
                }

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(username,
                        null, authorities);

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ex) {
            logger.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT from the Authorization header only.
     *
     * <p><strong>P0-5:</strong> Query parameter extraction ({@code ?token=...})
     * has been intentionally removed. Passing JWTs in URLs leaks them into
     * browser history, server logs, and Referer headers. SSE endpoints
     * should use a short-lived ticket mechanism instead.</p>
     */
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        // P0-5: Removed query parameter token extraction.
        // SSE connections use a short-lived ticket via /api/interviews/{id}/sse-ticket
        return null;
    }
}
