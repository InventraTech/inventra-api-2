package com.inventra.api.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.domain.alert.Alert;

// Quem enxerga um alerta da cozinha:
//   - com destinatário (recipient): só aquela pessoa;
//   - com perfil-alvo (targetProfile): só quem tem aquele perfil;
//   - sem nenhum dos dois: toda a cozinha que cuida de estoque (seesKitchenWide = supervisor e estoquista;
//     o comprador só recebe o que é dele).
public interface AlertRepository extends JpaRepository<Alert, Integer> {

    @EntityGraph(attributePaths = {"batch", "product", "kitchen"})
    @Query("""
            SELECT a FROM Alert a
            WHERE a.kitchen.id = :kitchenId
              AND (a.recipient.id = :userId
                   OR (a.recipient IS NULL
                       AND (a.targetProfile.id = :profileId
                            OR (a.targetProfile IS NULL AND :seesKitchenWide = true))))
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<Alert> findVisible(@Param("kitchenId") Integer kitchenId, @Param("userId") UUID userId,
                            @Param("profileId") Integer profileId, @Param("seesKitchenWide") boolean seesKitchenWide);

    // Não lido para este usuário: ninguém resolveu o alerta (is_read) e ele próprio ainda não leu.
    @EntityGraph(attributePaths = {"batch", "product", "kitchen"})
    @Query("""
            SELECT a FROM Alert a
            WHERE a.kitchen.id = :kitchenId
              AND (a.recipient.id = :userId
                   OR (a.recipient IS NULL
                       AND (a.targetProfile.id = :profileId
                            OR (a.targetProfile IS NULL AND :seesKitchenWide = true))))
              AND a.read = false
              AND NOT EXISTS (SELECT 1 FROM AlertRead r WHERE r.id.alertId = a.id AND r.id.userId = :userId)
            ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<Alert> findUnreadVisible(@Param("kitchenId") Integer kitchenId, @Param("userId") UUID userId,
                                  @Param("profileId") Integer profileId,
                                  @Param("seesKitchenWide") boolean seesKitchenWide);

    // Alerta ainda aberto (ninguém leu) com a mesma origem: evita duplicar o aviso.
    @EntityGraph(attributePaths = {"batch", "product", "kitchen"})
    @Query("""
            SELECT a FROM Alert a
            WHERE a.kitchen.id = :kitchenId AND a.type = :type AND a.refKey = :refKey AND a.read = false
              AND NOT EXISTS (SELECT 1 FROM AlertRead r WHERE r.id.alertId = a.id)
            ORDER BY a.id
            """)
    List<Alert> findOpenByOrigin(@Param("kitchenId") Integer kitchenId, @Param("type") String type,
                                 @Param("refKey") String refKey);

    default Optional<Alert> findFirstOpenByOrigin(Integer kitchenId, String type, String refKey) {
        return findOpenByOrigin(kitchenId, type, refKey).stream().findFirst();
    }

    // Marca como resolvido para todo mundo: o que originou o alerta já foi tratado.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Alert a SET a.read = true
            WHERE a.kitchen.id = :kitchenId AND a.type = :type AND a.refKey = :refKey AND a.read = false
            """)
    int resolveByOrigin(@Param("kitchenId") Integer kitchenId, @Param("type") String type,
                        @Param("refKey") String refKey);
}
