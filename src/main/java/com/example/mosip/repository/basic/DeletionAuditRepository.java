package com.example.mosip.repository.basic;

import com.example.mosip.entity.basic.DeletionAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeletionAuditRepository extends JpaRepository<DeletionAudit, Long> {

    /** All deletion attempts, newest first. */
    List<DeletionAudit> findAllByOrderByAttemptedAtDesc();

    /** Deletion attempts for a given user id (case-insensitive contains), newest first. */
    List<DeletionAudit> findByUserIdContainingIgnoreCaseOrderByAttemptedAtDesc(String userId);

    /** Deletion attempts matching a specific UIN salt-modulo hash, newest first. */
    List<DeletionAudit> findByUinSaltedHashOrderByAttemptedAtDesc(String uinSaltedHash);
}
