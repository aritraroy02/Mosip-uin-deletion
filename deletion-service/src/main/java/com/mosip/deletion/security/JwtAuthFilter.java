package com.mosip.deletion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Gate for /api/deletion/**: requires a valid gateway JWT and, on success,
 * exposes the verified UIN as the request attribute "uin". Any missing/invalid/
 * expired token is rejected with 401 before a controller runs, so the deletion
 * endpoints can trust that a UIN is present and authenticated.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String UIN_ATTRIBUTE = "uin";

    private final JwtVerifier verifier;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtAuthFilter(JwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/deletion/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            deny(response, "missing bearer token");
            return;
        }
        try {
            String uin = verifier.verifyAndGetUin(header.substring(7).trim());
            request.setAttribute(UIN_ATTRIBUTE, uin);
        } catch (JwtVerifier.InvalidTokenException e) {
            deny(response, e.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(mapper.writeValueAsString(
                Map.of("error", "unauthorized", "reason", reason)));
    }
}
