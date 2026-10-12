package com.inventra.api.core.domain.kitchen;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "tb_kitchen")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@EntityListeners(AuditingEntityListener.class)
public class Kitchen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_kitchen")
    @EqualsAndHashCode.Include
    private Integer id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "code", nullable = false, unique = true, length = 20)
    @EqualsAndHashCode.Include
    private String code;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "city", length = 120)
    private String city;

    // UF; a coluna é CHAR(2)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "state", length = 2)
    private String state;

    // sem coordenadas a cozinha aparece na lista, mas não no mapa
    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    // false: some da descoberta de cozinhas e só aceita pedido de entrada pelo código de convite
    @Column(name = "accepting_requests", nullable = false)
    @Builder.Default
    private Boolean acceptingRequests = true;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
