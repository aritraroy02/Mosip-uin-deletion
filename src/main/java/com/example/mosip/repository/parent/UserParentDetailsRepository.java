package com.example.mosip.repository.parent;

import com.example.mosip.entity.parent.UserParentDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserParentDetailsRepository extends JpaRepository<UserParentDetails, String> {
}
