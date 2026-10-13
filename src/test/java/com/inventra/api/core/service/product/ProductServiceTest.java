package com.inventra.api.core.service.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.service.image.ImageStorage;
import com.inventra.api.core.service.image.ImageValidator;
import com.inventra.api.core.service.image.StoredImage;
import com.inventra.api.infrastructure.exception.ImageStorageException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.CategoryRepository;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductKitchenParameterRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.ProductSupplierRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.repository.UnitRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final Integer PRODUCT_ID = 7;
    private static final String EXPECTED_PUBLIC_ID = "inventra/products/7";
    private static final String CLOUDINARY_URL =
            "https://res.cloudinary.com/demo/image/upload/v1/inventra/products/7.jpg";
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    @Mock private ProductRepository repository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UnitRepository unitRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private ProductSupplierRepository productSupplierRepository;
    @Mock private ProductKitchenParameterRepository productKitchenParameterRepository;
    @Mock private KitchenRepository kitchenRepository;
    @Mock private StockBatchRepository stockBatchRepository;
    @Mock private KitchenAccessGuard accessGuard;
    @Mock private ImageStorage imageStorage;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private ProductService service;

    private static Product product() {
        return Product.builder().id(PRODUCT_ID).name("Coca-Cola").active(true).build();
    }

    @Test
    void updatePhotoUploadsWithFixedPublicIdAndSavesUrl() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(imageStorage.upload(JPEG_BYTES, EXPECTED_PUBLIC_ID))
                .thenReturn(new StoredImage(CLOUDINARY_URL, EXPECTED_PUBLIC_ID));
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = service.updatePhoto(PRODUCT_ID, JPEG_BYTES);

        assertEquals(CLOUDINARY_URL, result.getPhotoUrl());
        verify(imageValidator).validate(JPEG_BYTES);
        verify(imageStorage).upload(JPEG_BYTES, EXPECTED_PUBLIC_ID);
    }

    @Test
    void updatePhotoDoesNotUploadWhenImageIsInvalid() {
        doThrow(new IllegalArgumentException("Formato inválido"))
                .when(imageValidator).validate(any());

        assertThrows(IllegalArgumentException.class,
                () -> service.updatePhoto(PRODUCT_ID, new byte[]{1, 2, 3}));

        verify(imageStorage, never()).upload(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void updatePhotoFailsWhenProductDoesNotExist() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updatePhoto(PRODUCT_ID, JPEG_BYTES));

        verify(imageStorage, never()).upload(any(), any());
    }

    @Test
    void updatePhotoBubblesStorageFailure() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(imageStorage.upload(any(), eq(EXPECTED_PUBLIC_ID)))
                .thenThrow(new ImageStorageException("falha", new RuntimeException()));

        assertThrows(ImageStorageException.class,
                () -> service.updatePhoto(PRODUCT_ID, JPEG_BYTES));

        verify(repository, never()).save(any());
    }

    @Test
    void removePhotoDeletesFromStorageAndClearsUrl() {
        Product product = product();
        product.setPhotoUrl(CLOUDINARY_URL);
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = service.removePhoto(PRODUCT_ID);

        verify(imageStorage).delete(EXPECTED_PUBLIC_ID);
        assertNull(result.getPhotoUrl());
    }

    @Test
    void removePhotoFailsWhenProductDoesNotExist() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.removePhoto(PRODUCT_ID));

        verify(imageStorage, never()).delete(any());
    }
}
