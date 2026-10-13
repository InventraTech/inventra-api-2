package com.inventra.api.core.service.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.inventra.api.core.service.image.ImageStorage;
import com.inventra.api.core.service.image.ImageValidator;
import com.inventra.api.core.service.image.StoredImage;
import com.inventra.api.core.service.product.model.request.CreateProductRequest;
import com.inventra.api.core.service.product.model.request.LinkSupplierRequest;
import com.inventra.api.core.service.product.model.request.SetKitchenParametersRequest;
import com.inventra.api.core.service.product.model.request.UpdateProductRequest;
import com.inventra.api.core.service.product.model.response.ProductKitchenParameterResponse;
import com.inventra.api.core.service.product.model.response.ProductSupplierResponse;
import com.inventra.api.core.domain.category.Category;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.product.ProductKitchenParameter;
import com.inventra.api.core.domain.product.ProductKitchenParameterId;
import com.inventra.api.core.domain.product.ProductSupplier;
import com.inventra.api.core.domain.product.ProductSupplierId;
import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.domain.unit.Unit;
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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductService implements ProductUseCase {

    private static final String PHOTO_PUBLIC_ID_PREFIX = "inventra/products/";

    private final ProductRepository repository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;
    private final SupplierRepository supplierRepository;
    private final ProductSupplierRepository productSupplierRepository;
    private final ProductKitchenParameterRepository productKitchenParameterRepository;
    private final KitchenRepository kitchenRepository;
    private final StockBatchRepository stockBatchRepository;
    private final KitchenAccessGuard accessGuard;
    private final ImageStorage imageStorage;
    private final ImageValidator imageValidator;

    @Override
    public Product create(CreateProductRequest request) {
        if (request.barcode() != null && repository.existsByBarcode(request.barcode())) {
            throw new BusinessRuleException("Já existe um produto com esse código de barras.");
        }

        Unit unit = unitRepository.findById(request.unitId())
            .orElseThrow(() -> new ResourceNotFoundException("Unidade de medida não encontrada."));

        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Categoria não encontrada."));
        }

        Product product = Product.builder()
            .name(request.name())
            .brand(request.brand())
            .category(category)
            .unit(unit)
            .barcode(request.barcode())
            .photoUrl(request.photoUrl())
            .active(true)
            .build();

        return repository.save(product);
    }

    @Override
    public Product findById(Integer id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado."));
    }

    @Override
    public Page<Product> search(String name, Integer categoryId, Boolean active, Pageable pageable) {
        return repository.search(name, categoryId, active, pageable);
    }

    @Override
    public Product update(Integer id, UpdateProductRequest request) {
        Product product = findById(id);

        if (request.name() != null) {
            product.setName(request.name());
        }
        if (request.brand() != null) {
            product.setBrand(request.brand());
        }
        if (request.categoryId() != null) {
            Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Categoria não encontrada."));
            product.setCategory(category);
        }
        if (request.unitId() != null) {
            Unit unit = unitRepository.findById(request.unitId())
                .orElseThrow(() -> new ResourceNotFoundException("Unidade de medida não encontrada."));
            product.setUnit(unit);
        }
        if (request.barcode() != null && !request.barcode().equals(product.getBarcode())) {
            if (repository.existsByBarcode(request.barcode())) {
                throw new BusinessRuleException("Já existe um produto com esse código de barras.");
            }
            product.setBarcode(request.barcode());
        }

        return repository.save(product);
    }

    @Override
    public Product updatePhoto(Integer id, byte[] content) {
        imageValidator.validate(content);
        Product product = findById(id);

        StoredImage image = imageStorage.upload(content, PHOTO_PUBLIC_ID_PREFIX + id);
        product.setPhotoUrl(image.url());
        return repository.save(product);
    }

    @Override
    public Product removePhoto(Integer id) {
        Product product = findById(id);

        imageStorage.delete(PHOTO_PUBLIC_ID_PREFIX + id);
        product.setPhotoUrl(null);
        return repository.save(product);
    }

    @Override
    public void activate(Integer id) {
        Product product = findById(id);
        product.setActive(true);
        repository.save(product);
    }

    @Override
    public void deactivate(Integer id) {
        Product product = findById(id);
        product.setActive(false);
        repository.save(product);
    }

    @Override
    public void linkSupplier(Integer productId, LinkSupplierRequest request) {
        Product product = findById(productId);
        Supplier supplier = supplierRepository.findById(request.supplierId())
            .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado."));

        ProductSupplier link = ProductSupplier.builder()
            .id(new ProductSupplierId(productId, supplier.getId()))
            .product(product)
            .supplier(supplier)
            .supplierCode(request.supplierCode())
            .referencePrice(request.referencePrice())
            .leadTimeDays(request.leadTimeDays())
            .build();

        productSupplierRepository.save(link);
    }

    @Override
    public void setKitchenParameters(Integer productId, SetKitchenParametersRequest request) {
        accessGuard.assertAccess(request.kitchenId());
        Product product = findById(productId);
        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));

        ProductKitchenParameter parameter = ProductKitchenParameter.builder()
            .id(new ProductKitchenParameterId(productId, kitchen.getId()))
            .product(product)
            .kitchen(kitchen)
            .minStock(Objects.requireNonNullElse(request.minStock(), BigDecimal.ZERO))
            .maxStock(request.maxStock())
            .averageDailyConsumption(request.averageDailyConsumption())
            .build();

        productKitchenParameterRepository.save(parameter);
    }

    @Override
    public List<ProductSupplierResponse> listSuppliers(Integer productId) {
        return productSupplierRepository.findByProduct_Id(productId).stream()
            .map(ProductSupplierResponse::fromEntity)
            .toList();
    }

    @Override
    public List<ProductKitchenParameterResponse> listKitchenParameters(Integer productId) {
        return productKitchenParameterRepository.findByProduct_Id(productId).stream()
            .filter(parameter -> accessGuard.hasAccess(parameter.getKitchen().getId()))
            .map(parameter -> {
                BigDecimal current = stockBatchRepository.sumActiveQuantity(productId, parameter.getKitchen().getId());
                return ProductKitchenParameterResponse.from(parameter, current);
            })
            .toList();
    }
}
