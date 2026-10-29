package com.inventra.api.core.service.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.inventra.api.core.service.image.ImagePaths;
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

    // Produto não nasce sem fornecedor: o produto e o vínculo são gravados na mesma transação.
    @Override
    @Transactional
    public Product create(CreateProductRequest request) {
        if (request.barcode() != null && repository.existsByBarcode(request.barcode())) {
            throw new BusinessRuleException("Já existe um produto com esse código de barras.");
        }

        // a fila de cadastro por código de barras chama este método sem a validação do controller
        if (request.supplierId() == null) {
            throw new BusinessRuleException("Informe o fornecedor do produto.");
        }
        Supplier supplier = supplierRepository.findById(request.supplierId())
            .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado."));
        if (!Boolean.TRUE.equals(supplier.getActive())) {
            throw new BusinessRuleException("Fornecedor desativado: escolha um fornecedor ativo para o produto.");
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
            .active(true)
            .build();

        Product saved = repository.save(product);
        saveLink(saved, supplier, request.supplierCode(), request.referencePrice(), request.leadTimeDays());
        return saved;
    }

    @Override
    public Product findById(Integer id) {
        return repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado."));
    }

    @Override
    public Page<Product> search(String name, Integer categoryId, Boolean active, Pageable pageable) {
        // nome vazio = sem filtro (a query não aceita parâmetro nulo, ver ProductRepository.search)
        return repository.search(name == null ? "" : name, categoryId, active, pageable);
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

        StoredImage image = imageStorage.upload(content, ImagePaths.product(id));
        product.setPhotoUrl(image.url());
        return repository.save(product);
    }

    @Override
    public Product removePhoto(Integer id) {
        Product product = findById(id);

        imageStorage.delete(ImagePaths.product(id));
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
    @Transactional
    public void linkSupplier(Integer productId, LinkSupplierRequest request) {
        Product product = findById(productId);
        Supplier supplier = supplierRepository.findById(request.supplierId())
            .orElseThrow(() -> new ResourceNotFoundException("Fornecedor não encontrado."));

        saveLink(product, supplier, request.supplierCode(), request.referencePrice(), request.leadTimeDays());
    }

    // Grava ou regrava o vínculo. Regravar só troca os campos que vieram preenchidos: o app regrava o vínculo a
    // cada edição do produto mandando só o fornecedor, e isso não pode apagar o preço de referência que as
    // requisições usam como sugestão.
    private void saveLink(Product product, Supplier supplier, String supplierCode, BigDecimal referencePrice,
                          Integer leadTimeDays) {
        ProductSupplierId id = new ProductSupplierId(product.getId(), supplier.getId());
        ProductSupplier link = productSupplierRepository.findById(id)
            .orElseGet(() -> ProductSupplier.builder().id(id).product(product).supplier(supplier).build());

        if (supplierCode != null) {
            link.setSupplierCode(supplierCode);
        }
        if (referencePrice != null) {
            link.setReferencePrice(referencePrice);
        }
        if (leadTimeDays != null) {
            link.setLeadTimeDays(leadTimeDays);
        }
        // regravar com os mesmos dados também conta como "mais recente": sem um campo diferente, o merge
        // não gera UPDATE e o trigger do banco não renova a data
        link.setUpdatedAt(LocalDateTime.now());

        productSupplierRepository.save(link);
    }

    @Override
    public void setKitchenParameters(Integer productId, SetKitchenParametersRequest request) {
        accessGuard.assertAccess(request.kitchenId());
        BigDecimal minStock = Objects.requireNonNullElse(request.minStock(), BigDecimal.ZERO);
        if (request.maxStock() != null && request.maxStock().compareTo(minStock) < 0) {
            throw new IllegalArgumentException("O estoque máximo não pode ser menor que o estoque mínimo.");
        }
        Product product = findById(productId);
        Kitchen kitchen = kitchenRepository.findById(request.kitchenId())
            .orElseThrow(() -> new ResourceNotFoundException("Cozinha não encontrada."));

        ProductKitchenParameter parameter = ProductKitchenParameter.builder()
            .id(new ProductKitchenParameterId(productId, kitchen.getId()))
            .product(product)
            .kitchen(kitchen)
            .minStock(minStock)
            .maxStock(request.maxStock())
            .averageDailyConsumption(request.averageDailyConsumption())
            .build();

        productKitchenParameterRepository.save(parameter);
    }

    @Override
    public List<ProductSupplierResponse> listSuppliers(Integer productId) {
        return productSupplierRepository.findByProductIdNewestFirst(productId).stream()
            .map(ProductSupplierResponse::fromEntity)
            .toList();
    }

    @Override
    public List<ProductKitchenParameterResponse> listKitchenParameters(Integer productId) {
        Integer kitchenId = accessGuard.currentKitchenId();
        if (kitchenId == null) {
            return List.of();
        }
        return productKitchenParameterRepository.findByProduct_IdAndKitchen_Id(productId, kitchenId).stream()
            .map(parameter -> {
                BigDecimal current = stockBatchRepository.sumActiveQuantity(productId, parameter.getKitchen().getId());
                return ProductKitchenParameterResponse.from(parameter, current);
            })
            .toList();
    }
}
