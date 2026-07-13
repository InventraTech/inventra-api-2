package com.inventra.api.core.domain.supplier;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "tb_fornecedor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@EntityListeners(AuditingEntityListener.class)
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_fornecedor")
    @EqualsAndHashCode.Include
    private Integer id;

    @Column(name = "nome_juridico", nullable = false, length = 150)
    private String legalName;

    @Column(name = "cnpj", nullable = false, unique = true, length = 18)
    @EqualsAndHashCode.Include
    private String cnpj;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "whatsapp", length = 20)
    private String whatsapp;

    @Column(name = "nota_avaliacao")
    private Integer rating;

    @Column(name = "ativo", nullable = false)
    private Boolean active = true;

    @CreatedDate
    @Column(name = "criado_em", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
