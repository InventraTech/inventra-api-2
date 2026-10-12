package com.inventra.api.core.service.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import com.inventra.api.core.domain.category.Category;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.product.ProductKitchenParameter;
import com.inventra.api.core.domain.product.ProductSupplier;
import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.service.image.ImageStorage;
import com.inventra.api.core.service.image.ImageValidator;
import com.inventra.api.core.service.product.model.request.CreateProductRequest;
import com.inventra.api.core.service.product.model.request.LinkSupplierRequest;
import com.inventra.api.core.service.product.model.request.SetKitchenParametersRequest;
import com.inventra.api.core.service.product.model.request.UpdateProductRequest;
import com.inventra.api.infrastructure.exception.BusinessRuleException;
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

// Complementa o ProductServiceTest (que cobre só a foto): CRUD, vínculo com fornecedor e parâmetros por cozinha.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceCrudTest {

    private static final Integer PRODUCT_ID = 7;
    private static final Integer KITCHEN_ID = 1;

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

    private Unit unit;
    private Category category;

    @BeforeEach
    void setUp() {
        unit = Unit.builder().id(1).symbol("kg").build();
        category = Category.builder().id(2).name("Grãos").build();
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Product product() {
        return Product.builder().id(PRODUCT_ID).name("Arroz").barcode("111").unit(unit).active(true).build();
    }

    // ---------- create ----------

    @Test
    void createSavesActiveProductWithUnitAndCategory() {
        when(unitRepository.findById(1)).thenReturn(Optional.of(unit));
        when(categoryRepository.findById(2)).thenReturn(Optional.of(category));

        Product result = service.create(new CreateProductRequest("Arroz", "Tio João", 2, 1, "789"));

        assertEquals("Arroz", result.getName());
        assertSame(unit, result.getUnit());
        assertSame(category, result.getCategory());
        assertTrue(result.getActive());
    }

    @Test
    void createWithoutCategoryIsAllowed() {
        when(unitRepository.findById(1)).thenReturn(Optional.of(unit));

        Product result = service.create(new CreateProductRequest("Arroz", null, null, 1, null));

        assertEquals(null, result.getCategory());
        verify(categoryRepository, never()).findById(any());
    }

    @Test
    void createRejectsDuplicateBarcode() {
        when(repository.existsByBarcode("789")).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.create(new CreateProductRequest("Arroz", null, null, 1, "789")));
        verify(repository, never()).save(any());
    }

    @Test
    void createFailsForUnknownUnitOrCategory() {
        when(unitRepository.findById(1)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.create(new CreateProductRequest("Arroz", null, null, 1, null)));

        when(unitRepository.findById(1)).thenReturn(Optional.of(unit));
        when(categoryRepository.findById(2)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.create(new CreateProductRequest("Arroz", null, 2, 1, null)));
    }

    // ---------- leitura ----------

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(404)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findById(404));
    }

    @Test
    void searchDelegatesFiltersAndPagingToTheRepository() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Product> page = new PageImpl<>(List.of(product()));
        when(repository.search("arr", 2, true, pageable)).thenReturn(page);

        assertSame(page, service.search("arr", 2, true, pageable));
    }

    @Test
    void searchWithoutNameFiltersWithEmptyTextInsteadOfNull() {
        Pageable pageable = PageRequest.of(0, 200);
        Page<Product> page = new PageImpl<>(List.of(product()));
        when(repository.search("", null, true, pageable)).thenReturn(page);

        assertSame(page, service.search(null, null, true, pageable));
    }

    // ---------- update ----------

    @Test
    void updateChangesOnlyProvidedFields() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));

        Product result = service.update(PRODUCT_ID, new UpdateProductRequest(null, "Camil", null, null, null));

        assertEquals("Arroz", result.getName());
        assertEquals("Camil", result.getBrand());
        assertEquals("111", result.getBarcode());
    }

    @Test
    void updateRejectsBarcodeUsedByAnotherProduct() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(repository.existsByBarcode("999")).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> service.update(PRODUCT_ID, new UpdateProductRequest(null, null, null, null, "999")));
        verify(repository, never()).save(any());
    }

    @Test
    void updateKeepingTheSameBarcodeDoesNotCheckDuplicates() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));

        service.update(PRODUCT_ID, new UpdateProductRequest(null, null, null, null, "111"));

        verify(repository, never()).existsByBarcode(any());
    }

    @Test
    void updateChangesCategoryAndUnit() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        Unit liter = Unit.builder().id(5).symbol("l").build();
        when(categoryRepository.findById(2)).thenReturn(Optional.of(category));
        when(unitRepository.findById(5)).thenReturn(Optional.of(liter));

        Product result = service.update(PRODUCT_ID, new UpdateProductRequest(null, null, 2, 5, null));

        assertSame(category, result.getCategory());
        assertSame(liter, result.getUnit());
    }

    @Test
    void updateFailsForUnknownCategory() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(categoryRepository.findById(2)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(PRODUCT_ID, new UpdateProductRequest(null, null, 2, null, null)));
    }

    // ---------- activate / deactivate ----------

    @Test
    void deactivateAndActivateToggleTheFlag() {
        Product existing = product();
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

        service.deactivate(PRODUCT_ID);
        assertFalse(existing.getActive());

        service.activate(PRODUCT_ID);
        assertTrue(existing.getActive());
    }

    // ---------- fornecedores ----------

    @Test
    void linkSupplierSavesLinkWithCompositeId() {
        Supplier supplier = Supplier.builder().id(3).legalName("Dist").build();
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(supplierRepository.findById(3)).thenReturn(Optional.of(supplier));

        service.linkSupplier(PRODUCT_ID, new LinkSupplierRequest(3, "ARZ-1", new BigDecimal("25.90"), 4));

        ArgumentCaptor<ProductSupplier> captor = forClass(ProductSupplier.class);
        verify(productSupplierRepository).save(captor.capture());
        assertEquals(PRODUCT_ID, captor.getValue().getId().getProductId());
        assertEquals(3, captor.getValue().getId().getSupplierId());
        assertEquals(new BigDecimal("25.90"), captor.getValue().getReferencePrice());
        assertEquals(4, captor.getValue().getLeadTimeDays());
    }

    @Test
    void linkSupplierFailsForUnknownSupplier() {
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(supplierRepository.findById(3)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.linkSupplier(PRODUCT_ID, new LinkSupplierRequest(3, null, null, null)));
        verify(productSupplierRepository, never()).save(any());
    }

    @Test
    void listSuppliersIsEmptyWhenProductHasNone() {
        when(productSupplierRepository.findByProduct_Id(PRODUCT_ID)).thenReturn(List.of());

        assertTrue(service.listSuppliers(PRODUCT_ID).isEmpty());
    }

    // ---------- parâmetros por cozinha ----------

    @Test
    void setKitchenParametersSavesMinMaxAndConsumption() {
        Kitchen kitchen = Kitchen.builder().id(KITCHEN_ID).active(true).build();
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));

        service.setKitchenParameters(PRODUCT_ID, new SetKitchenParametersRequest(
                KITCHEN_ID, new BigDecimal("10"), new BigDecimal("50"), new BigDecimal("2.5")));

        ArgumentCaptor<ProductKitchenParameter> captor = forClass(ProductKitchenParameter.class);
        verify(productKitchenParameterRepository).save(captor.capture());
        assertEquals(new BigDecimal("10"), captor.getValue().getMinStock());
        assertEquals(new BigDecimal("50"), captor.getValue().getMaxStock());
        verify(accessGuard).assertAccess(KITCHEN_ID);
    }

    @Test
    void setKitchenParametersDefaultsMinimumToZero() {
        Kitchen kitchen = Kitchen.builder().id(KITCHEN_ID).active(true).build();
        when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));
        when(kitchenRepository.findById(KITCHEN_ID)).thenReturn(Optional.of(kitchen));

        service.setKitchenParameters(PRODUCT_ID, new SetKitchenParametersRequest(KITCHEN_ID, null, null, null));

        ArgumentCaptor<ProductKitchenParameter> captor = forClass(ProductKitchenParameter.class);
        verify(productKitchenParameterRepository).save(captor.capture());
        assertEquals(BigDecimal.ZERO, captor.getValue().getMinStock());
    }

    @Test
    void setKitchenParametersRejectsMaximumBelowMinimum() {
        assertThrows(IllegalArgumentException.class, () -> service.setKitchenParameters(PRODUCT_ID,
                new SetKitchenParametersRequest(KITCHEN_ID, new BigDecimal("10"), new BigDecimal("5"), null)));
        verify(productKitchenParameterRepository, never()).save(any());
    }

    @Test
    void setKitchenParametersIsBlockedWithoutAccessToKitchen() {
        doThrow(new AccessDeniedException("sem acesso")).when(accessGuard).assertAccess(KITCHEN_ID);

        assertThrows(AccessDeniedException.class, () -> service.setKitchenParameters(PRODUCT_ID,
                new SetKitchenParametersRequest(KITCHEN_ID, null, null, null)));
        verify(productKitchenParameterRepository, never()).save(any());
    }

    @Test
    void listKitchenParametersIsEmptyForUserWithoutKitchen() {
        when(accessGuard.currentKitchenId()).thenReturn(null);

        assertTrue(service.listKitchenParameters(PRODUCT_ID).isEmpty());
        verify(productKitchenParameterRepository, never()).findByProduct_IdAndKitchen_Id(any(), any());
    }
}
