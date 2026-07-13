package com.inventra.api.core.domain.product;

import com.inventra.api.core.domain.category.Category;
import com.inventra.api.core.domain.unit.Unit;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "tb_produto")
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
    @Column(name = "id_produto")
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "nome", nullable = false, length = 150)
    @EqualsAndHashCode.Include
    private String name;

    @Column(name = "marca", length = 80)
    private String brand;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_categoria")
    @ToString.Exclude
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_unidade", nullable = false)
    @ToString.Exclude
    private Unit unit;

    @Column(name = "codigo_barras", unique = true, length = 50)
    private String barcode;

    @Column(name = "foto_url", length = 255)
    private String photoUrl;

    @Column(name = "ativo", nullable = false)
    private Boolean active = true;

    @CreatedDate
    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isNew() {
        return id == null;
    }

    public boolean isActive() {
        return active != null && active;
    }
}
