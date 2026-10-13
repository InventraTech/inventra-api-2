package com.inventra.api.core.service.productqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.UnitRepository;

@SpringBootTest
@ActiveProfiles("test")
class ProductRegistrationProcessorTest {
    @Autowired private ProductRegistrationProcessor processor;
    @Autowired private ProductRepository products;
    @Autowired private UnitRepository units;

    @Test void persistsInRelationalDatabaseAndReplayDoesNotCreateDuplicate() {
        var unit = units.save(Unit.builder().symbol("qtest").description("Unidade para teste da fila").build());
        var job = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz fila", "Inventra", null, unit.getId(), "7891234500001", null));
        Integer firstId = processor.process(job);
        Integer replayId = processor.process(job);
        assertThat(replayId).isEqualTo(firstId);
        var saved = products.findByBarcode(job.request().barcode()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Arroz fila");
        assertThat(saved.getCreatedAt()).isNotNull();
        var duplicate = ProductRegistrationJob.queued(UUID.randomUUID(), job.request());
        assertThrows(com.inventra.api.infrastructure.exception.BusinessRuleException.class,
                () -> processor.process(duplicate));
        products.deleteById(firstId);
        units.deleteById(unit.getId());
    }

    @Test void invalidReferenceRollsBackWithoutSavingProduct() {
        var job = ProductRegistrationJob.queued(UUID.randomUUID(),
                new BarcodeRegistrationRequest("Arroz fila", null, null, Integer.MAX_VALUE, "7891234500002", null));
        assertThrows(ResourceNotFoundException.class, () -> processor.process(job));
        assertThat(products.findByBarcode(job.request().barcode())).isEmpty();
    }
}
