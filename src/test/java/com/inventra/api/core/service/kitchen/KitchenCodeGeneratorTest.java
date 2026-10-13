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

    @Test
    void codesAreNotRepeatedInAShortSample() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSize(2_000);
    }
}
