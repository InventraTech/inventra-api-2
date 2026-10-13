package com.inventra.api.core.domain.stock;

import com.inventra.api.core.domain.requisition.Requisition;
import com.inventra.api.core.domain.stock.enums.StockMovementReason;
import com.inventra.api.core.domain.stock.enums.StockMovementType;
import com.inventra.api.core.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Histórico do lote: quem mexeu, quando, quanto e por quê. As baixas e as entradas em lote existente são
// gravadas pelas procedures (sp_write_off_stock, sp_register_stock_entry); a criação do lote e o ajuste
// de saldo são gravados pela API.
@Entity
@Table(name = "tb_stock_movement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@EntityListeners(AuditingEntityListener.class)
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_movement")
    @EqualsAndHashCode.Include
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_batch", nullable = false)
    @ToString.Exclude
    private StockBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_user")
    @ToString.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_requisition")
    @ToString.Exclude
    private Requisition requisition;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private StockMovementType type;

    // só nas baixas
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 20)
    private StockMovementReason reason;

    // entrada e baixa: quantidade movimentada (positiva); ajuste: diferença, com sinal
    @Column(name = "quantity", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "balance_after", nullable = false, precision = 12, scale = 3)
    private BigDecimal balanceAfter;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
