package com.inventra.api.core.domain.alert;

import com.inventra.api.core.domain.alert.enums.AlertSeverity;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "tb_alert")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@EntityListeners(AuditingEntityListener.class)
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_alert")
    @EqualsAndHashCode.Include
    private Integer id;

    @Column(name = "type", nullable = false, length = 30)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private AlertSeverity severity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_batch")
    @ToString.Exclude
    private StockBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_product")
    @ToString.Exclude
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_kitchen", nullable = false)
    @ToString.Exclude
    private Kitchen kitchen;

    // Destinatário: recipient e targetProfile nulos = toda a cozinha; recipient = só aquela pessoa;
    // targetProfile = só quem tem aquele perfil na cozinha.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_user")
    @ToString.Exclude
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_target_profile")
    @ToString.Exclude
    private Profile targetProfile;

    // o que originou o alerta: id da requisição, id do pedido de entrada ou código de barras
    @Column(name = "ref_key", length = 100)
    private String refKey;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    // true = resolvido para todo mundo (leitura antiga, do alerta inteiro); a leitura por pessoa fica em AlertRead
    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean read = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
