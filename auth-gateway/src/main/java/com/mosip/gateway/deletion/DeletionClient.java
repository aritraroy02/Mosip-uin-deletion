package com.mosip.gateway.deletion;

import com.mosip.gateway.config.GatewayProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Calls the JWT-secured deletion service. Sends the gateway token as a Bearer
 * credential and maps the returned overall status to the page's job status
 * (COMPLETED / FAILED). A 401 means the token was rejected (e.g. expired).
 */
@Service
public class DeletionClient {

    public static class Unauthorized extends RuntimeException {}

    public record Outcome(String pageStatus, Map<?, ?> raw) {}

    private final GatewayProperties props;
    private final RestClient http = RestClient.create();

    public DeletionClient(GatewayProperties props) {
        this.props = props;
    }

    public Outcome execute(String gatewayJwt) {
        Map<?, ?> result;
        try {
            result = http.post().uri(props.getDeletionService().getExecuteUrl())
                    .header("Authorization", "Bearer " + gatewayJwt)
                    .retrieve()
                    .onStatus(s -> s.value() == 401 || s.value() == 403,
                            (req, res) -> { throw new Unauthorized(); })
                    .body(Map.class);
        } catch (Unauthorized e) {
            throw e;
        } catch (Exception e) {
            return new Outcome("FAILED", Map.of("error", e.getMessage()));
        }

        String overall = result == null ? null : String.valueOf(result.get("overall"));
        // DELETED / NOT_FOUND (nothing to delete) -> done; anything else -> failed.
        String page = ("DELETED".equals(overall) || "NOT_FOUND".equals(overall))
                ? "COMPLETED" : "FAILED";
        return new Outcome(page, result);
    }
}
