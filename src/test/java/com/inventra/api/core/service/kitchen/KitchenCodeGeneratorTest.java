package com.inventra.api.core.service.kitchen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class KitchenCodeGeneratorTest {

    private final KitchenCodeGenerator generator = new KitchenCodeGenerator();

    @Test
    void codeHasSixCharactersWithoutAmbiguousOnes() {
        for (int i = 0; i < 2_000; i++) {
            assertThat(generator.generate()).matches("[A-HJ-NP-Z2-9]{6}");
        }
    }

    // Com 32^6 combinações, 2.000 sorteios repetem um código em ~0,2% das execuções (paradoxo do
    // aniversário), então exigir 100% de códigos distintos deixava o teste instável. Colisão é
    // tratada no KitchenService, que gera outro código; aqui só garantimos que os códigos variam.
    @Test
    void codesAreAlmostAlwaysDistinct() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSizeGreaterThanOrEqualTo(1_990);
    }
}
