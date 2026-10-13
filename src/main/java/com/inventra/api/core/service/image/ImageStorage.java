package com.inventra.api.core.service.image;

public interface ImageStorage {

    StoredImage upload(byte[] content, String publicId);

    void delete(String publicId);
}
