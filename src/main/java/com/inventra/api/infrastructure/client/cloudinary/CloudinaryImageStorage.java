package com.inventra.api.infrastructure.client.cloudinary;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.utils.ObjectUtils;
import com.inventra.api.core.service.image.ImageStorage;
import com.inventra.api.core.service.image.StoredImage;
import com.inventra.api.infrastructure.exception.ImageStorageException;

import lombok.RequiredArgsConstructor;

// public_id fixo por recurso (ImagePaths) + overwrite: reenviar a foto substitui a anterior no Cloudinary,
// sem imagem órfã e sem precisar guardar o public_id no banco.
@Component
@RequiredArgsConstructor
public class CloudinaryImageStorage implements ImageStorage {

    private final Cloudinary cloudinary;

    @Override
    public StoredImage upload(byte[] content, String publicId, String transformation) {
        try {
            Map<String, Object> options = new HashMap<>(ObjectUtils.asMap(
                    "public_id", publicId,
                    "resource_type", "image",
                    "overwrite", true,
                    "invalidate", true));
            if (transformation != null) {
                options.put("transformation", new Transformation<>().rawTransformation(transformation));
            }
            Map<?, ?> result = cloudinary.uploader().upload(content, options);
            return new StoredImage((String) result.get("secure_url"), (String) result.get("public_id"));
        } catch (IOException | RuntimeException e) {
            throw new ImageStorageException("Falha ao enviar a imagem para o Cloudinary.", e);
        }
    }

    @Override
    public void delete(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.asMap(
                    "resource_type", "image",
                    "invalidate", true));
        } catch (IOException | RuntimeException e) {
            throw new ImageStorageException("Falha ao remover a imagem do Cloudinary.", e);
        }
    }
}
