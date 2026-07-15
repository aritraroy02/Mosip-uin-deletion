package com.example.mosip.repository.basic;

import com.example.mosip.entity.basic.UserDataLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserDataLocationRepository extends JpaRepository<UserDataLocation, String> {

    /** Locates a user's data-footprint record by the salt-modulo hash of their UIN. */
    Optional<UserDataLocation> findByUinSaltedHash(String uinSaltedHash);
}
