package com.inventra.api.core.domain.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class AlertReadId implements Serializable {

    @Column(name = "id_alert")
    private Integer alertId;

    @Column(name = "id_user")
    private UUID userId;
}
