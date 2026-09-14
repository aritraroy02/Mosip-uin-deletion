package com.mosip.deletion.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mosip.deletion.config.Databases;
import com.mosip.deletion.model.DeletionResult;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * The new audit table (design doc section 13). Stores only the HASHED UIN --
 * never the plaintext -- plus the deletion time, overall and per-module status.
 * It is also the source of truth for the "already deleted" check: if a completed
 * (DELETED) record exists for a UIN, a later request is answered ALREADY_DELETED
 * even though the identity rows themselves are already gone.
 */
@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditRepository(Databases db) {
        this.jdbc = db.db("audit");
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS deletion");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS deletion.uin_deletion_audit (
                id                 varchar(36)  NOT NULL,
                uin_hash_prefixed  varchar(128),
                uin_hash_bare      varchar(128) NOT NULL,
                overall_status     varchar(20)  NOT NULL,
                module_status      jsonb,
                cr_dtimes          timestamp    NOT NULL DEFAULT now(),
                CONSTRAINT pk_uin_deletion_audit PRIMARY KEY (id)
            )
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_uin_deletion_audit_bare
            ON deletion.uin_deletion_audit (uin_hash_bare)
            """);
    }

    /** Returns the completion time of a prior fully-completed deletion, if any. */
    public Optional<String> findCompletedDeletion(String uinHashBare) {
        return jdbc.query("""
                SELECT cr_dtimes FROM deletion.uin_deletion_audit
                WHERE uin_hash_bare = ? AND overall_status = 'DELETED'
                ORDER BY cr_dtimes DESC LIMIT 1
                """,
                rs -> {
                    if (rs.next()) {
                        Timestamp ts = rs.getTimestamp("cr_dtimes");
                        return Optional.of(ts.toLocalDateTime()
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    }
                    return Optional.empty();
                }, uinHashBare);
    }

    public void record(DeletionResult result) {
        String moduleJson;
        try {
            moduleJson = mapper.writeValueAsString(result.modules());
        } catch (Exception e) {
            moduleJson = "[]";
        }
        jdbc.update("""
                INSERT INTO deletion.uin_deletion_audit
                    (id, uin_hash_prefixed, uin_hash_bare, overall_status, module_status, cr_dtimes)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """,
                result.requestId(),
                result.uinHashPrefixed(),
                result.uinHashBare(),
                result.overall().name(),
                moduleJson,
                Timestamp.from(result.completedAt()));
    }
}
