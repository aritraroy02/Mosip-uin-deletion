package com.example.mosip.repository.hashing;

import com.example.mosip.entity.hashing.UserUinHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserUinHashRepository extends JpaRepository<UserUinHash, String> {
}
