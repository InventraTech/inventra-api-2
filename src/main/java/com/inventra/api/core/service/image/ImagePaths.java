package com.inventra.api.core.service.image;

import java.util.UUID;

// public_id no Cloudinary: inventra/{recurso}/{id}. Fixo por recurso, então reenviar a foto sobrescreve a anterior.
public final class ImagePaths {

    private static final String ROOT = "inventra/";

    private ImagePaths() {
    }

    public static String product(Integer id) {
        return ROOT + "products/" + id;
    }

    public static String supplier(Integer id) {
        return ROOT + "suppliers/" + id;
    }

    public static String user(UUID id) {
        return ROOT + "users/" + id;
    }
}
