package com.example.mosip.enums;

/**
 * Enumeration representing supported image types in MinIO object storage
 * with default folder paths, max size limits, and human-readable names.
 */
public enum ImageType {
    PROFILE_PICTURE("profile-pictures/", "Profile Picture", 5 * 1024 * 1024L),
    AADHAR_CARD("aadhar-cards/", "Aadhar Card", 10 * 1024 * 1024L),
    DOCUMENT("documents/", "Document", 10 * 1024 * 1024L);

    private final String folderPrefix;
    private final String displayName;
    private final long maxSizeBytes;

    ImageType(String folderPrefix, String displayName, long maxSizeBytes) {
        this.folderPrefix = folderPrefix;
        this.displayName = displayName;
        this.maxSizeBytes = maxSizeBytes;
    }

    public String getFolderPrefix() {
        return folderPrefix;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    /**
     * Resolves ImageType from string case-insensitively.
     */
    public static ImageType fromString(String text) {
        if (text == null || text.trim().isEmpty()) {
            return PROFILE_PICTURE;
        }
        String clean = text.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        for (ImageType b : ImageType.values()) {
            if (b.name().equals(clean) || b.folderPrefix.replace("/", "").equalsIgnoreCase(clean)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown ImageType: " + text + ". Valid types: PROFILE_PICTURE, AADHAR_CARD, DOCUMENT");
    }
}
