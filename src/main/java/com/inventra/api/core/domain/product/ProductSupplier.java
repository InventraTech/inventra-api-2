package com.inventra.api.core.domain.product;

import com.inventra.api.core.domain.supplier.Supplier;
import org.hibernate.annotations.ColumnDefault;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tb_product_supplier")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ProductSupplier {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private ProductSupplierId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("productId")
    @JoinColumn(name = "id_product")
    @ToString.Exclude
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("supplierId")
    @JoinColumn(name = "id_supplier")
    @ToString.Exclude
    private Supplier supplier;

    @Column(name = "supplier_code", length = 50)
    private String supplierCode;

    @Column(name = "reference_price", precision = 12, scale = 2)
    private BigDecimal referencePrice;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    // A hora é sempre a do banco: o INSERT não manda a coluna (vale o DEFAULT) e o trg_product_supplier_updated_at
    // a renova em todo UPDATE. O Java só a preenche para o merge achar o vínculo alterado e gerar o UPDATE.
    // @ColumnDefault repete o DEFAULT da V020 para o schema que o Hibernate gera no H2 dos testes.
    @Column(name = "updated_at", nullable = false, insertable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}
