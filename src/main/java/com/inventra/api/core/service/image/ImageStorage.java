package com.inventra.api.core.service.image;

public interface ImageStorage {

    default StoredImage upload(byte[] content, String publicId) {
        return upload(content, publicId, null);
    }

    // transformation: transformação de entrada do Cloudinary (ex.: "c_fill,g_face,w_400,h_400"), aplicada
    // antes de guardar o arquivo. Nula = guarda a imagem como veio.
    StoredImage upload(byte[] content, String publicId, String transformation);

    void delete(String publicId);
}
