package com.inventra.api.core.service.supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.service.image.ImageStorage;
import com.inventra.api.core.service.image.ImageValidator;
import com.inventra.api.core.service.image.StoredImage;
import com.inventra.api.core.service.supplier.model.request.CreateSupplierRequest;
import com.inventra.api.core.service.supplier.model.request.UpdateSupplierRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ImageStorageException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    private static final String CNPJ = "12.345.678/0001-90";
    private static final String EXPECTED_PUBLIC_ID = "inventra/suppliers/1";
    private static final String CLOUDINARY_URL =
            "https://res.cloudinary.com/demo/image/upload/v1/inventra/suppliers/1.jpg";
    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    @Mock private SupplierRepository repository;
    @Mock private KitchenAccessGuard accessGuard;
    @Mock private ImageStorage imageStorage;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private SupplierService service;

    private static Supplier supplier() {
        return Supplier.builder().id(1).legalName("Distribuidora Ltda").cnpj(CNPJ)
                .email("contato@dist.com").rating(4).active(true).build();
    }

    @Test
    void createSavesActiveSupplier() {
        when(repository.existsByCnpj(CNPJ)).thenReturn(false);
        when(repository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier result = service.create(new CreateSupplierRequest("Distribuidora Ltda", CNPJ, "a@b.com", null, 5));

        assertEquals(CNPJ, result.getCnpj());
        assertEquals(5, result.getRating());
        assertTrue(result.getActive());
    }

    @Test
    void createWithoutOptionalFieldsLeavesThemNull() {
        when(repository.existsByCnpj(CNPJ)).thenReturn(false);
        when(repository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier result = service.create(new CreateSupplierRequest("X", CNPJ, null, null, null));

        assertNull(result.getEmail());
        assertNull(result.getRating());
    }

    @Test
    void createRejectsDuplicateCnpj() {
        when(repository.existsByCnpj(CNPJ)).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.create(new CreateSupplierRequest("X", CNPJ, null, null, null)));
        verify(repository, never()).save(any());
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(9));
    }

    @Test
    void listActiveReturnsRepositoryActiveSuppliers() {
        List<Supplier> active = List.of(supplier());
        when(repository.findByActiveTrue()).thenReturn(active);

        assertSame(active, service.listActive());
    }

    @Test
    void listActiveByKitchenReturnsTheKitchenSuppliers() {
        List<Supplier> suppliers = List.of(supplier());
        when(repository.findActiveByKitchen(7)).thenReturn(suppliers);

        assertSame(suppliers, service.listActiveByKitchen(7));
        verify(accessGuard).assertAccess(7);
    }

    @Test
    void listActiveByKitchenDeniesAnotherUsersKitchen() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(8);

        assertThrows(AccessDeniedException.class, () -> service.listActiveByKitchen(8));
        verify(repository, never()).findActiveByKitchen(any());
    }

    @Test
    void updateChangesOnlyProvidedFieldsAndKeepsCnpj() {
        when(repository.findById(1)).thenReturn(Optional.of(supplier()));
        when(repository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier result = service.update(1, new UpdateSupplierRequest(null, null, "11999999999", 2));

        assertEquals("Distribuidora Ltda", result.getLegalName());
        assertEquals(CNPJ, result.getCnpj());
        assertEquals("contato@dist.com", result.getEmail());
        assertEquals("11999999999", result.getWhatsapp());
        assertEquals(2, result.getRating());
    }

    @Test
    void updateThrowsWhenSupplierIsMissing() {
        when(repository.findById(9)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(9, new UpdateSupplierRequest("Novo", null, null, null)));
    }

    @Test
    void deactivateAndActivateToggleTheFlag() {
        Supplier existing = supplier();
        when(repository.findById(1)).thenReturn(Optional.of(existing));

        service.deactivate(1);
        assertFalse(existing.getActive());

        service.activate(1);
        assertTrue(existing.getActive());
    }

    @Test
    void updatePhotoUploadsWithFixedPublicIdAndSavesUrl() {
        when(repository.findById(1)).thenReturn(Optional.of(supplier()));
        when(imageStorage.upload(JPEG_BYTES, EXPECTED_PUBLIC_ID))
                .thenReturn(new StoredImage(CLOUDINARY_URL, EXPECTED_PUBLIC_ID));
        when(repository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier result = service.updatePhoto(1, JPEG_BYTES);

        assertEquals(CLOUDINARY_URL, result.getPhotoUrl());
        verify(imageValidator).validate(JPEG_BYTES);
    }

    @Test
    void updatePhotoDoesNotUploadWhenImageIsInvalid() {
        doThrow(new IllegalArgumentException("Formato inválido")).when(imageValidator).validate(any());

        assertThrows(IllegalArgumentException.class, () -> service.updatePhoto(1, new byte[]{1, 2, 3}));

        verify(imageStorage, never()).upload(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void updatePhotoFailsWhenSupplierDoesNotExist() {
        when(repository.findById(1)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.updatePhoto(1, JPEG_BYTES));

        verify(imageStorage, never()).upload(any(), any());
    }

    @Test
    void updatePhotoBubblesStorageFailure() {
        when(repository.findById(1)).thenReturn(Optional.of(supplier()));
        when(imageStorage.upload(any(), eq(EXPECTED_PUBLIC_ID)))
                .thenThrow(new ImageStorageException("falha", new RuntimeException()));

        assertThrows(ImageStorageException.class, () -> service.updatePhoto(1, JPEG_BYTES));

        verify(repository, never()).save(any());
    }

    @Test
    void removePhotoDeletesFromStorageAndClearsUrl() {
        Supplier supplier = supplier();
        supplier.setPhotoUrl(CLOUDINARY_URL);
        when(repository.findById(1)).thenReturn(Optional.of(supplier));
        when(repository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier result = service.removePhoto(1);

        verify(imageStorage).delete(EXPECTED_PUBLIC_ID);
        assertNull(result.getPhotoUrl());
    }

    @Test
    void removePhotoFailsWhenSupplierDoesNotExist() {
        when(repository.findById(1)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.removePhoto(1));

        verify(imageStorage, never()).delete(any());
    }
}
