package com.mosip.deletion.api;

import com.mosip.deletion.model.CheckResult;
import com.mosip.deletion.model.DeletionResult;
import com.mosip.deletion.security.JwtAuthFilter;
import com.mosip.deletion.service.DeletionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * JWT-secured deletion API. Every request must carry a valid gateway-signed
 * Bearer token (enforced by {@link JwtAuthFilter}); the UIN comes from that
 * token, never from the request body, and there is no consent parameter --
 * consent was obtained at eSignet before the token was minted.
 *
 *   POST /api/deletion/check     -> availability for the token's UIN
 *   POST /api/deletion/execute   -> delete the token's UIN, return module status
 */
@RestController
@RequestMapping("/api/deletion")
public class DeletionController {

    private final DeletionService service;

    public DeletionController(DeletionService service) {
        this.service = service;
    }

    private String uin(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthFilter.UIN_ATTRIBUTE);
    }

    @PostMapping("/check")
    public CheckResult check(HttpServletRequest request) {
        return service.check(uin(request));
    }

    @PostMapping("/execute")
    public DeletionResult execute(HttpServletRequest request) {
        return service.executeAuthorized(uin(request));
    }
}
