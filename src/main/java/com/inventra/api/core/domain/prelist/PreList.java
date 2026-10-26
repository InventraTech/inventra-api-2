package com.inventra.api.core.domain.prelist;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

// Pré-lista de compra (intenção de compra herdada do banco do 1º ano). Não mexe em estoque nem vira
// requisição sozinha; a integração RPA grava as listas importadas e usa sourceId para não repeti-las.
@Entity
@Table(name = "tb_pre_list")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PreList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_pre_list")
    @EqualsAndHashCode.Include
    private Integer id;

    @Column(name = "list_date", nullable = false)
    @Builder.Default
    private LocalDate listDate = LocalDate.now();

    // id da lista no banco do 1º ano; nulo nas listas criadas direto no Inventra
    @Column(name = "id_source", unique = true)
    private Integer sourceId;
}
