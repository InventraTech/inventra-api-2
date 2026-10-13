package com.inventra.api.infrastructure.controller;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.service.product.ProductUseCase;
import com.inventra.api.core.service.product.model.request.CreateProductRequest;
import com.inventra.api.core.service.product.model.request.LinkSupplierRequest;
import com.inventra.api.core.service.product.model.request.SetKitchenParametersRequest;
import com.inventra.api.core.service.product.model.request.UpdateProductRequest;
import com.inventra.api.core.service.product.model.response.ProductKitchenParameterResponse;
import com.inventra.api.core.service.product.model.response.ProductResponse;
import com.inventra.api.core.service.product.model.response.ProductSupplierResponse;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.client.openfoodfacts.model.OpenFoodFactsProduct;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductUseCase useCase;
    private final OpenFoodFactsClient openFoodFactsClient;

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        Product created = useCase.create(request);
        ProductResponse response = ProductResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/products/" + response.id())).body(response);
    }

    @GetMapping("/barcode-lookup")
    public ResponseEntity<OpenFoodFactsProduct> lookupByBarcode(@RequestParam String barcode) {
        return openFoodFactsClient.findByBarcode(barcode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(ProductResponse.fromEntity(useCase.findById(id)));
    }

    @GetMapping
    public ResponseEntity<Page<ProductResponse>> search(@RequestParam(required = false) String name,
                                                          @RequestParam(required = false) Integer categoryId,
                                                          @RequestParam(required = false) Boolean active,
                                                          Pageable pageable) {
        Page<ProductResponse> page = useCase.search(name, categoryId, active, pageable)
                .map(ProductResponse::fromEntity);
        return ResponseEntity.ok(page);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> update(@PathVariable Integer id,
                                                   @Valid @RequestBody UpdateProductRequest request) {
        Product updated = useCase.update(id, request);
        return ResponseEntity.ok(ProductResponse.fromEntity(updated));
    }

    @PutMapping("/{id}/photo")
    public ResponseEntity<ProductResponse> updatePhoto(@PathVariable Integer id,
                                                        @RequestParam("file") MultipartFile file) throws IOException {
        Product updated = useCase.updatePhoto(id, file.getBytes());
        return ResponseEntity.ok(ProductResponse.fromEntity(updated));
    }

    @DeleteMapping("/{id}/photo")
    public ResponseEntity<ProductResponse> removePhoto(@PathVariable Integer id) {
        Product updated = useCase.removePhoto(id);
        return ResponseEntity.ok(ProductResponse.fromEntity(updated));
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable Integer id) {
        useCase.activate(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Integer id) {
        useCase.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/suppliers")
    public ResponseEntity<Void> linkSupplier(@PathVariable Integer id, @Valid @RequestBody LinkSupplierRequest request) {
        useCase.linkSupplier(id, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/suppliers")
    public ResponseEntity<List<ProductSupplierResponse>> listSuppliers(@PathVariable Integer id) {
        return ResponseEntity.ok(useCase.listSuppliers(id));
    }

    @PutMapping("/{id}/kitchen-parameters")
    public ResponseEntity<Void> setKitchenParameters(@PathVariable Integer id,
                                                      @Valid @RequestBody SetKitchenParametersRequest request) {
        useCase.setKitchenParameters(id, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/kitchen-parameters")
    public ResponseEntity<List<ProductKitchenParameterResponse>> listKitchenParameters(@PathVariable Integer id) {
        return ResponseEntity.ok(useCase.listKitchenParameters(id));
    }
}
