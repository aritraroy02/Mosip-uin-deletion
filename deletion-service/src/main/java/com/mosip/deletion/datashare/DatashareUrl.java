package com.mosip.deletion.datashare;

/**
 * Shared datashare-URL parser used by every object-deletion path (design doc
 * section 15). All datashare URLs follow:
 *
 *     http://datashare.datashare/v1/datashare/get/{policyId}/{partnerId}/{objectKey}
 *
 * which maps to object store: bucket = {policyId}, key = {objectKey}.
 */
public record DatashareUrl(String bucket, String objectKey) {

    private static final String MARKER = "/datashare/get/";

    /** Returns null if the URL is null/blank or not in the expected shape. */
    public static DatashareUrl parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int idx = url.indexOf(MARKER);
        if (idx < 0) {
            return null;
        }
        String rest = url.substring(idx + MARKER.length());
        String[] parts = rest.split("/");
        if (parts.length < 3) {
            return null;
        }
        String policyId = parts[0];
        // objectKey is everything after policyId/partnerId (it contains no '/'
        // in practice, but join defensively).
        StringBuilder key = new StringBuilder(parts[2]);
        for (int i = 3; i < parts.length; i++) {
            key.append('/').append(parts[i]);
        }
        return new DatashareUrl(policyId, key.toString());
    }
}
