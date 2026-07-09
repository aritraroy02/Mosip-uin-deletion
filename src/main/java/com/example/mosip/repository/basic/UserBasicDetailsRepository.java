package com.example.mosip.repository.basic;

import com.example.mosip.entity.basic.UserBasicDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserBasicDetailsRepository extends JpaRepository<UserBasicDetails, String> {
}
