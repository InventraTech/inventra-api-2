package com.inventra.api.core.domain.profile;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tb_perfil")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_perfil")
    @EqualsAndHashCode.Include
    private Integer id;

    @Column(name = "tipo_acesso", nullable = false, unique = true, length = 50)
    @EqualsAndHashCode.Include
    private String accessType;

    @Column(name = "descricao", length = 255)
    private String description;
}
