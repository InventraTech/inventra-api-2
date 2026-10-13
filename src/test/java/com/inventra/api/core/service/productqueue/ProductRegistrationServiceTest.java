package com.inventra.api.core.service.productqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.redis.queue.RedisProductRegistrationQueue;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

class ProductRegistrationServiceTest {
    private final RedisProductRegistrationQueue queue = mock(RedisProductRegistrationQueue.class);
    private final KitchenAccessGuard guard = mock(KitchenAccessGuard.class);
    private final com.inventra.api.infrastructure.repository.ProductRepository products = mock(com.inventra.api.infrastructure.repository.ProductRepository.class);
    private final ProductRegistrationService service = new ProductRegistrationService(queue, guard, products);

    @Test void duplicateIsRejectedBeforeEnqueue() {
        when(products.existsByBarcode(request.barcode())).thenReturn(true);
        assertThrows(com.inventra.api.infrastructure.exception.BusinessRuleException.class, () -> service.enqueue(request));
        org.mockito.Mockito.verifyNoInteractions(queue);
    }
    private final UUID userId = UUID.randomUUID();
    private final BarcodeRegistrationRequest request = new BarcodeRegistrationRequest("Arroz", null, null, 1, "7891234567890", null);

    @BeforeEach void authenticate() { when(guard.currentUser()).thenReturn(User.builder().id(userId).build()); }

    @Test void producerStoresAuthenticatedOwnerAndReturnsQueuedStatus() {
        var response = service.enqueue(request);
        var captor = ArgumentCaptor.forClass(ProductRegistrationJob.class);
        verify(queue).enqueue(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId);
        assertThat(captor.getValue().request()).isEqualTo(request);
        assertThat(response.eventId()).isEqualTo(captor.getValue().eventId());
        assertThat(response.status()).isEqualTo(ProductRegistrationJob.Status.QUEUED);
    }

    @Test void rejectsStatusReadByAnotherUser() {
        var job = ProductRegistrationJob.queued(UUID.randomUUID(), request);
        when(queue.find(job.eventId())).thenReturn(Optional.of(job));
        assertThrows(AccessDeniedException.class, () -> service.find(job.eventId()));
    }

    @Test void returnsStatusOnlyForOwner() {
        var job = ProductRegistrationJob.queued(userId, request).processing().completed(42);
        when(queue.find(job.eventId())).thenReturn(Optional.of(job));
        assertThat(service.find(job.eventId()).productId()).isEqualTo(42);
    }

    @Test void unknownJobReturnsNotFound() {
        when(queue.find(any())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.find(UUID.randomUUID()));
    }
}
