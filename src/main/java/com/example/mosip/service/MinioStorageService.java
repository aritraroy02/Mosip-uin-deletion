package com.example.mosip.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Handles storage of user profile images in a MinIO (S3-compatible) bucket.
 */
@Service
public class MinioStorageService {

    private final MinioClient minioClient;
    private final String bucket;
    private final int urlExpirySeconds;

    public MinioStorageService(MinioClient minioClient,
                               @Value("${minio.bucket}") String bucket,
                               @Value("${minio.url-expiry-seconds:604800}") int urlExpirySeconds) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.urlExpirySeconds = urlExpirySeconds;
    }

    /**
     * Creates the configured bucket on startup if it does not already exist.
     */
    @PostConstruct
    public void ensureBucketExists() {
        try {
            boolean found = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                System.out.println("Created MinIO bucket: " + bucket);
            } else {
                System.out.println("MinIO bucket already exists: " + bucket);
            }
        } catch (Exception e) {
            System.err.println("Failed to verify/create MinIO bucket '" + bucket + "': " + e.getMessage());
        }
    }

    /**
     * Uploads the given file to the bucket and returns the stored object name (key).
     *
     * @param file   the uploaded profile image
     * @param userId used to namespace the object for easy identification
     * @return the object key under which the image was stored
     */
    public String uploadProfileImage(MultipartFile file, String userId) throws Exception {
        String extension = extractExtension(file.getOriginalFilename());
        String objectName = "profiles/" + userId + "-" + UUID.randomUUID() + extension;

        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(is, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());
        }
        return objectName;
    }

    /**
     * Builds a temporary presigned URL that can be used to view a stored object.
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

    private String extractExtension(String originalFilename) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        return "";
    }

    /**
     * Gets the presigned URL for the profile image of a given userId, if it exists.
     */
    public String getProfileImagePresignedUrl(String userId) {
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix("profiles/" + userId)
                            .build());
            for (Result<Item> result : results) {
                Item item = result.get();
                return getPresignedUrl(item.objectName());
            }
        } catch (Exception e) {
            System.err.println("Error fetching profile image URL: " + e.getMessage());
        }
        return null;
    }

    /**
     * Deletes the profile image associated with the given userId.
     */
    public void deleteProfileImage(String userId) throws Exception {
        Iterable<Result<Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(bucket)
                        .prefix("profiles/" + userId)
                        .build());
        for (Result<Item> result : results) {
            Item item = result.get();
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(item.objectName())
                            .build());
            System.out.println("Deleted object from MinIO: " + item.objectName());
        }
    }
}
