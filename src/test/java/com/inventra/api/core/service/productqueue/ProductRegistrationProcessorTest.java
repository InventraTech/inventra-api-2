package com.inventra.api.core.service.productqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.service.productqueue.model.BarcodeRegistrationRequest;
import com.inventra.api.core.service.productqueue.model.ProductRegistrationJob;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.ProductSupplierRepository;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.repository.UnitRepository;

@SpringBootTest
@ActiveProfiles("test")
class ProductRegistrationProcessorTest {
    @Autowired private ProductRegistrationProcessor processor;
    @Autowired private ProductRepository products;
    @Autowired private UnitRepository units;
    @Autowired private SupplierRepository suppliers;
    @Autowired private ProductSupplierRepository links;

    private Unit unit;
    private Supplier supplier;

    @BeforeEach void seed() {
        unit = units.save(Unit.builder().symbol("qtest").description("Unidade para teste da fila").build());
        supplier = suppliers.save(Supplier.builder().legalName("Fornecedor da fila").cnpj("77.777.777/0001-77")
                .active(true).build());
    }

    @AfterEach void cleanUp() {
        products.findByBarcode("7891234500001").ifPresent(product -> {
            links.deleteAll(links.findByProductIdNewestFirst(product.getId()));
            products.delete(product);
        });
        suppliers.deleteById(supplier.getId());
        units.deleteById(unit.getId());
    }

    @Test void persistsInRelationalDatabaseAndReplayDoesNotCreateDuplicate() {
        var job = ProductRegistrationJob.queued(UUID.randomUUID(), new BarcodeRegistrationRequest("Arroz fila",
                "Inventra", null, unit.getId(), "7891234500001", supplier.getId(), "ARZ-F", new BigDecimal("9.90"), 2));
        Integer firstId = processor.process(job);
        Integer replayId = processor.process(job);
        assertThat(replayId).isEqualTo(firstId);
        var saved = products.findByBarcode(job.request().barcode()).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Arroz fila");
        assertThat(saved.getCreatedAt()).isNotNull();
        // o produto nasce vinculado ao fornecedor do pedido, com as condições informadas
        assertThat(links.findByProductIdNewestFirst(firstId)).singleElement().satisfies(link -> {
            assertThat(link.getSupplier().getId()).isEqualTo(supplier.getId());
            assertThat(link.getReferencePrice()).isEqualByComparingTo("9.90");
        });
        var duplicate = ProductRegistrationJob.queued(UUID.randomUUID(), job.request());
        assertThat(processor.process(duplicate)).isEqualTo(firstId);
    }

    @Test void invalidReferenceRollsBackWithoutSavingProduct() {
        var job = ProductRegistrationJob.queued(UUID.randomUUID(), new BarcodeRegistrationRequest("Arroz fila",
                null, null, Integer.MAX_VALUE, "7891234500002", supplier.getId(), null, null, null));
        assertThrows(ResourceNotFoundException.class, () -> processor.process(job));
        assertThat(products.findByBarcode(job.request().barcode())).isEmpty();
    }

    // job enfileirado antes do fornecedor virar obrigatório: falha como regra de negócio, sem produto órfão
    @Test void jobWithoutSupplierFailsWithoutSavingProduct() {
        var job = ProductRegistrationJob.queued(UUID.randomUUID(), new BarcodeRegistrationRequest("Arroz fila",
                null, null, unit.getId(), "7891234500003", null, null, null, null));
        assertThrows(BusinessRuleException.class, () -> processor.process(job));
        assertThat(products.findByBarcode(job.request().barcode())).isEmpty();
    }
}
