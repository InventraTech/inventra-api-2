package com.inventra.api.core.service.image;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ImageValidatorTest {

    private final ImageValidator validator = new ImageValidator();

    @Test
    void acceptsJpeg() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        assertDoesNotThrow(() -> validator.validate(jpeg));
    }

    @Test
    void acceptsPng() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        assertDoesNotThrow(() -> validator.validate(png));
    }

    @Test
    void acceptsWebp() {
        byte[] webp = {'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P'};
        assertDoesNotThrow(() -> validator.validate(webp));
    }

    @Test
    void rejectsNullContent() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
    }

    @Test
    void rejectsEmptyFile() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new byte[0]));
    }

    @Test
    void rejectsPlainText() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("nao sou uma imagem".getBytes()));
    }

    @Test
    void rejectsRiffThatIsNotWebp() {
        byte[] riffAvi = {'R', 'I', 'F', 'F', 0x00, 0x00, 0x00, 0x00, 'A', 'V', 'I', ' '};
        assertThrows(IllegalArgumentException.class, () -> validator.validate(riffAvi));
    }

    @Test
    void rejectsTruncatedFileTooShortForAnyMagicNumber() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new byte[]{(byte) 0xFF}));
    }
}
