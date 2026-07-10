package com.inventra.api.core.domain.product;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class ProductKitchenParameterId implements Serializable {

    @Column(name = "id_produto")
    private Long productId;

    @Column(name = "id_cozinha")
    private Integer kitchenId;
}
