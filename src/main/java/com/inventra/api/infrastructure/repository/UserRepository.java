package com.inventra.api.infrastructure.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.user.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByProfileId(Integer profileId);

    @Query("""
            SELECT u FROM User u JOIN FETCH u.profile WHERE u.email = :email
            """)
    Optional<User> findByEmailWithProfile(@Param("email") String email);

}
