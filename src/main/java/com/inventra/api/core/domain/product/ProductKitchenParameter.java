package com.inventra.api.core.domain.product;

import com.inventra.api.core.domain.kitchen.Kitchen;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "tb_produto_parametro_cozinha")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ProductKitchenParameter {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private ProductKitchenParameterId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("productId")
    @JoinColumn(name = "id_produto")
    @ToString.Exclude
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("kitchenId")
    @JoinColumn(name = "id_cozinha")
    @ToString.Exclude
    private Kitchen kitchen;

    @Column(name = "estoque_minimo", nullable = false, precision = 12, scale = 3)
    private BigDecimal minStock = BigDecimal.ZERO;

    @Column(name = "estoque_maximo", precision = 12, scale = 3)
    private BigDecimal maxStock;

    @Column(name = "consumo_medio_dia", precision = 12, scale = 3)
    private BigDecimal averageDailyConsumption;
}
