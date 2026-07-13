package com.inventra.api.core.domain.category;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tb_categoria")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_categoria")
    @EqualsAndHashCode.Include
    private Integer id;

    @Column(name = "nome", nullable = false, unique = true, length = 80)
    @EqualsAndHashCode.Include
    private String name;

    @Column(name = "descricao", length = 255)
    private String description;
}
