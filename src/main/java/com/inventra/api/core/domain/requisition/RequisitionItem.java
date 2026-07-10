package com.inventra.api.core.domain.requisition;

import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.supplier.Supplier;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "tb_requisicao_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class RequisitionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_requisicao_item")
    @EqualsAndHashCode.Include
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_requisicao", nullable = false)
    @ToString.Exclude
    private Requisition requisition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_produto", nullable = false)
    @ToString.Exclude
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_fornecedor_sugerido")
    @ToString.Exclude
    private Supplier suggestedSupplier;

    @Column(name = "quantidade", nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "preco_estimado", precision = 12, scale = 2)
    private BigDecimal estimatedPrice;

    @Column(name = "observacao", length = 255)
    private String note;
}
