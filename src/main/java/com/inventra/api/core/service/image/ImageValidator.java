package com.inventra.api.core.service.image;

import java.util.Arrays;

import org.springframework.stereotype.Component;

// Valida a partir dos bytes reais do arquivo — Content-Type do multipart é enviado pelo cliente
// e pode ser forjado. Cobre JPEG, PNG e WebP (RIFF...WEBP).
@Component
public class ImageValidator {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP = {'W', 'E', 'B', 'P'};

    public void validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("O arquivo de imagem está vazio.");
        }
        boolean supported = hasPrefix(content, 0, JPEG)
                || hasPrefix(content, 0, PNG)
                || (hasPrefix(content, 0, RIFF) && hasPrefix(content, 8, WEBP));
        if (!supported) {
            throw new IllegalArgumentException("Formato de imagem não suportado. Use JPEG, PNG ou WebP.");
        }
    }

    private static boolean hasPrefix(byte[] content, int offset, byte[] expected) {
        if (content.length < offset + expected.length) {
            return false;
        }
        return Arrays.equals(content, offset, offset + expected.length, expected, 0, expected.length);
    }
}
