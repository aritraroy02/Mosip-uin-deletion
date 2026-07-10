package com.example.mosip.repository.hashing;

import com.example.mosip.entity.hashing.UinHashSalt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UinHashSaltRepository extends JpaRepository<UinHashSalt, Long> {
}
