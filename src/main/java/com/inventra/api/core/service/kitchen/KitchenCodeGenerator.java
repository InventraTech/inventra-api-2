package com.inventra.api.core.service.kitchen;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

// Código curto e legível para compartilhar a cozinha: 6 caracteres de um alfabeto sem O/0 e I/1,
// que se confundem ao ler ou digitar (32 símbolos, ~1 bilhão de combinações).
@Component
public class KitchenCodeGenerator {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
