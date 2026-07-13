package com.inventra.api.core.domain.inventory;

import com.inventra.api.core.domain.stock.StockBatch;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "tb_inventario_contagem")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class InventoryCount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_contagem")
    @EqualsAndHashCode.Include
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_inventario", nullable = false)
    @ToString.Exclude
    private Inventory inventory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_lote", nullable = false)
    @ToString.Exclude
    private StockBatch batch;

    @Column(name = "qtd_registrada", nullable = false, precision = 12, scale = 3)
    private BigDecimal registeredQuantity;

    @Column(name = "qtd_fisica", nullable = false, precision = 12, scale = 3)
    private BigDecimal physicalQuantity;

    @Column(name = "divergencia", precision = 12, scale = 3)
    private BigDecimal divergence;

    @Column(name = "observacao", length = 255)
    private String note;
}
