package com.mosip.deletion.store;

import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Object-store deletions against MinIO. Wraps single-object and prefix
 * deletions and never throws on a missing object (idempotent -- a re-run after
 * partial failure must not fail because an object is already gone).
 */
@Service
public class ObjectStoreService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStoreService.class);
    private final MinioClient client;

    public ObjectStoreService(MinioClient client) { this.client = client; }

    /** Delete one object. Returns 1 if removed, 0 if it was absent. */
    public int deleteObject(String bucket, String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(key).build());
            return 1;
        } catch (Exception e) {
            log.warn("object delete failed {}/{}: {}", bucket, key, e.getMessage());
            return 0;
        }
    }

    /** Delete every object under a prefix (e.g. all packet parts for a RID). */
    public int deletePrefix(String bucket, String prefix) {
        int removed = 0;
        try {
            Iterable<Result<Item>> items = client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build());
            for (Result<Item> r : items) {
                String key = r.get().objectName();
                removed += deleteObject(bucket, key);
            }
        } catch (Exception e) {
            log.warn("prefix delete failed {}/{}: {}", bucket, prefix, e.getMessage());
        }
        return removed;
    }
}
