package com.example.mosip.service;

import com.example.mosip.enums.ImageType;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced MinIO storage service supporting multi-image categorization,
 * folder structures, file size/type validation, presigned URLs, and batch/cascading deletion.
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;
    private final String bucket;
    private final int urlExpirySeconds;

    @Value("${minio.folder.profile-picture:profile-pictures/}")
    private String profileFolder;

    @Value("${minio.folder.aadhar-card:aadhar-cards/}")
    private String aadharFolder;

    @Value("${minio.folder.document:documents/}")
    private String documentFolder;

    @Value("${minio.allowed-extensions:jpg,jpeg,png,webp,pdf}")
    private String allowedExtensions;

    public MinioStorageService(MinioClient minioClient,
                               @Value("${minio.bucket}") String bucket,
                               @Value("${minio.url-expiry-seconds:604800}") int urlExpirySeconds) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.urlExpirySeconds = urlExpirySeconds;
    }

    /**
     * Ensures configured MinIO bucket exists on startup.
     */
    @PostConstruct
    public void ensureBucketExists() {
        try {
            boolean found = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            } else {
                log.info("MinIO bucket already exists: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to verify/create MinIO bucket '{}': {}", bucket, e.getMessage());
        }
    }

    /**
     * Resolves folder prefix for a given image type.
     */
    public String getFolderPrefix(ImageType imageType) {
        if (imageType == null) {
            return profileFolder;
        }
        switch (imageType) {
            case AADHAR_CARD:
                return aadharFolder;
            case DOCUMENT:
                return documentFolder;
            case PROFILE_PICTURE:
            default:
                return profileFolder;
        }
    }

    /**
     * Validates file size and file extension.
     */
    public void validateFile(MultipartFile file, ImageType imageType) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty or missing.");
        }

        long maxAllowed = (imageType != null) ? imageType.getMaxSizeBytes() : 5 * 1024 * 1024L;
        if (file.getSize() > maxAllowed) {
            throw new IllegalArgumentException("File size (" + file.getSize()
                    + " bytes) exceeds maximum limit of " + maxAllowed + " bytes for "
                    + (imageType != null ? imageType.getDisplayName() : "Image"));
        }

        String extension = extractExtension(file.getOriginalFilename()).replace(".", "").toLowerCase();
        if (!extension.isEmpty() && !allowedExtensions.toLowerCase().contains(extension)) {
            throw new IllegalArgumentException("File format ." + extension + " is not allowed. Supported formats: " + allowedExtensions);
        }
    }

    /**
     * Uploads an image of a specific type under its corresponding folder structure.
     *
     * @param file      the uploaded image file
     * @param userId    user ID for object namespacing
     * @param imageType categorization of the image
     * @return object key (filepath in MinIO)
     */
    public String uploadImage(MultipartFile file, String userId, ImageType imageType) throws Exception {
        validateFile(file, imageType);

        String folder = getFolderPrefix(imageType);
        String extension = extractExtension(file.getOriginalFilename());
        String objectName = folder + userId + "-" + UUID.randomUUID() + extension;

        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(is, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());
        }
        log.info("Uploaded {} for user {} to MinIO path: {}", imageType, userId, objectName);
        return objectName;
    }

    /**
     * Backwards-compatible method for profile pictures.
     */
    public String uploadProfileImage(MultipartFile file, String userId) throws Exception {
        return uploadImage(file, userId, ImageType.PROFILE_PICTURE);
    }

    /**
     * Generates a temporary presigned URL for viewing a stored MinIO object.
     */
    public String getPresignedUrl(String objectName) throws Exception {
        return minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucket)
                        .object(objectName)
                        .expiry(urlExpirySeconds, TimeUnit.SECONDS)
                        .build());
    }

    /**
     * Gets presigned URL for a user's image of a specific ImageType.
     */
    public String getImagePresignedUrl(String userId, ImageType imageType) {
        try {
            String folder = getFolderPrefix(imageType);
            String prefix = folder + userId;

            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .build());

            for (Result<Item> result : results) {
                Item item = result.get();
                return getPresignedUrl(item.objectName());
            }

            // Fallback for legacy profile picture prefix
            if (imageType == ImageType.PROFILE_PICTURE) {
                Iterable<Result<Item>> legacyResults = minioClient.listObjects(
                        ListObjectsArgs.builder()
                                .bucket(bucket)
                                .prefix("profiles/" + userId)
                                .build());
                for (Result<Item> result : legacyResults) {
                    Item item = result.get();
                    return getPresignedUrl(item.objectName());
                }
            }
        } catch (Exception e) {
            log.error("Error fetching presigned URL for user {} and type {}: {}", userId, imageType, e.getMessage());
        }
        return null;
    }

    /**
     * Backwards-compatible profile picture presigned URL method.
     */
    public String getProfileImagePresignedUrl(String userId) {
        return getImagePresignedUrl(userId, ImageType.PROFILE_PICTURE);
    }

    /**
     * Retrieves presigned URLs for all image types of a user.
     */
    public Map<ImageType, String> getAllImagePresignedUrls(String userId) {
        Map<ImageType, String> map = new EnumMap<>(ImageType.class);
        for (ImageType type : ImageType.values()) {
            String url = getImagePresignedUrl(userId, type);
            if (url != null) {
                map.put(type, url);
            }
        }
        return map;
    }

    /**
     * Deletes a specific image type for a given user.
     * Returns a list of deleted object file paths.
     */
    public List<String> deleteImage(String userId, ImageType imageType) throws Exception {
        List<String> deletedPaths = new ArrayList<>();
        String folder = getFolderPrefix(imageType);
        String prefix = folder + userId;

        deletedPaths.addAll(deleteObjectsByPrefix(prefix));

        // Legacy check for profile pictures
        if (imageType == ImageType.PROFILE_PICTURE) {
            deletedPaths.addAll(deleteObjectsByPrefix("profiles/" + userId));
        }

        return deletedPaths;
    }

    /**
     * Deletes a specific MinIO object by exact object key/filepath.
     */
    public boolean deleteImageByObjectKey(String objectKey) throws Exception {
        if (objectKey == null || objectKey.trim().isEmpty()) {
            return false;
        }
        minioClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey.trim())
                        .build());
        log.info("Deleted object from MinIO: {}", objectKey);
        return true;
    }

    /**
     * Cascading deletion: Deletes ALL image types (profile picture, aadhar card, document, legacy)
     * for the specified user and returns a list of all deleted object file paths for audit logging.
     */
    public List<String> deleteAllUserImages(String userId) throws Exception {
        List<String> allDeletedPaths = new ArrayList<>();

        for (ImageType type : ImageType.values()) {
            allDeletedPaths.addAll(deleteImage(userId, type));
        }

        log.info("Completed cascading image purge for user {}. Total files deleted: {}", userId, allDeletedPaths.size());
        return allDeletedPaths;
    }

    /**
     * Backwards-compatible profile picture delete method.
     */
    public void deleteProfileImage(String userId) throws Exception {
        deleteImage(userId, ImageType.PROFILE_PICTURE);
    }

    private List<String> deleteObjectsByPrefix(String prefix) throws Exception {
        List<String> deleted = new ArrayList<>();
        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .build());

        for (Result<Item> result : results) {
            Item item = result.get();
            String objectName = item.objectName();
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .build());
            deleted.add(objectName);
            log.info("Deleted object from MinIO: {}", objectName);
        }
        return deleted;
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        return "";
    }
}
