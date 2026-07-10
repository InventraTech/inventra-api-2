package com.inventra.api.core.domain.stock;

import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.stock.enums.StockBatchStatus;
import com.inventra.api.core.domain.supplier.Supplier;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "tb_estoque_lote")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class StockBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_lote")
    @EqualsAndHashCode.Include
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_produto", nullable = false)
    @ToString.Exclude
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_cozinha", nullable = false)
    @ToString.Exclude
    private Kitchen kitchen;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_fornecedor")
    @ToString.Exclude
    private Supplier supplier;

    @Column(name = "numero_lote", nullable = false, length = 50)
    private String batchNumber;

    @Column(name = "numero_nota_fiscal", length = 50)
    private String invoiceNumber;

    @Column(name = "qtd_inicial", nullable = false, precision = 12, scale = 3)
    private BigDecimal initialQuantity;

    @Column(name = "qtd_atual", nullable = false, precision = 12, scale = 3)
    private BigDecimal currentQuantity;

    @Column(name = "data_entrada", nullable = false)
    private LocalDate entryDate;

    @Column(name = "data_validade")
    private LocalDate expirationDate;

    @Column(name = "preco_unitario", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StockBatchStatus status = StockBatchStatus.ATIVO;
}
