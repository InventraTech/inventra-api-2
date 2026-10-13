package com.inventra.api.core.domain.alert;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// Leitura de um alerta por uma pessoa: quem leu não tira o alerta de "não lido" dos outros.
@Entity
@Table(name = "tb_alert_read")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class AlertRead {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private AlertReadId id;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
