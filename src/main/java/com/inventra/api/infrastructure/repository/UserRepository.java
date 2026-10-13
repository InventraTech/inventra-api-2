package com.inventra.api.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.inventra.api.core.domain.user.User;

// E-mail é comparado sem diferenciar maiúsculas: "A@x.com" e "a@x.com" são a mesma conta.
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    @Query("""
            SELECT u FROM User u JOIN FETCH u.profile LEFT JOIN FETCH u.kitchen WHERE u.firebaseUid = :firebaseUid
            """)
    Optional<User> findByFirebaseUidWithProfile(@Param("firebaseUid") String firebaseUid);

    boolean existsByProfileId(Integer profileId);

    @EntityGraph(attributePaths = {"kitchen", "profile"})
    List<User> findByKitchenId(Integer kitchenId);

    @Query("""
            SELECT COUNT(u) FROM User u
            WHERE u.kitchen.id = :kitchenId AND u.active = true AND LOWER(u.profile.accessType) = LOWER(:accessType)
            """)
    long countActiveByKitchenAndAccessType(@Param("kitchenId") Integer kitchenId, @Param("accessType") String accessType);

    // Todas as contas com esse e-mail, ignorando maiúsculas. O banco compartilhado pode ter duplicatas
    // históricas (ex.: "A@x.com" e "a@x.com"); quem chama decide como desempatar.
    @Query("""
            SELECT u FROM User u JOIN FETCH u.profile WHERE LOWER(u.email) = LOWER(:email)
            """)
    List<User> findAllByEmailWithProfile(@Param("email") String email);

    @Query("""
            SELECT u FROM User u JOIN FETCH u.profile WHERE u.id = :id
            """)
    Optional<User> findByIdWithProfile(@Param("id") UUID id);

}
