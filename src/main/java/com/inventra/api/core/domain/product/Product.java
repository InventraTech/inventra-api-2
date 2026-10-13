package com.inventra.api.core.domain.product;

import com.inventra.api.core.domain.category.Category;
import com.inventra.api.core.domain.unit.Unit;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "tb_product")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@EntityListeners(AuditingEntityListener.class)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_product")
    @EqualsAndHashCode.Include
    private Integer id;

    @Column(name = "name", nullable = false, length = 150)
    @EqualsAndHashCode.Include
    private String name;

    @Column(name = "brand", length = 80)
    private String brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_category")
    @ToString.Exclude
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_unit", nullable = false)
    @ToString.Exclude
    private Unit unit;

    @Column(name = "barcode", unique = true, length = 50)
    private String barcode;

    @Column(name = "registration_event_id", unique = true)
    private java.util.UUID registrationEventId;

    @Column(name = "photo_url", length = 255)
    private String photoUrl;

    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isNew() {
        return id == null;
    }

    public boolean isActive() {
        return active != null && active;
    }
}
