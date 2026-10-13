package com.inventra.api.core.domain.product;

import com.inventra.api.core.domain.kitchen.Kitchen;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "tb_product_kitchen_parameter")
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
    @JoinColumn(name = "id_product")
    @ToString.Exclude
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("kitchenId")
    @JoinColumn(name = "id_kitchen")
    @ToString.Exclude
    private Kitchen kitchen;

    @Column(name = "min_stock", nullable = false, precision = 12, scale = 3)
    @Builder.Default
    private BigDecimal minStock = BigDecimal.ZERO;

    @Column(name = "max_stock", precision = 12, scale = 3)
    private BigDecimal maxStock;

    @Column(name = "average_daily_consumption", precision = 12, scale = 3)
    private BigDecimal averageDailyConsumption;
}
