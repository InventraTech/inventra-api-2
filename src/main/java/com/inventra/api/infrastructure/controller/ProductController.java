package com.inventra.api.infrastructure.controller;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.service.product.BarcodeLookupService;
import com.inventra.api.core.service.product.ProductUseCase;
import com.inventra.api.core.service.product.model.request.CreateProductRequest;
import com.inventra.api.core.service.product.model.request.LinkSupplierRequest;
import com.inventra.api.core.service.product.model.request.SetKitchenParametersRequest;
import com.inventra.api.core.service.product.model.request.UpdateProductRequest;
import com.inventra.api.core.service.product.model.response.BarcodeLookupResponse;
import com.inventra.api.core.service.product.model.response.ProductKitchenParameterResponse;
import com.inventra.api.core.service.product.model.response.ProductResponse;
import com.inventra.api.core.service.product.model.response.ProductSupplierResponse;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.client.openfoodfacts.model.OpenFoodFactsProduct;
import com.inventra.api.infrastructure.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@Tag(name = "Produtos", description = "Catálogo de produtos, fornecedores e parâmetros por cozinha")
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductUseCase useCase;
    private final OpenFoodFactsClient openFoodFactsClient;
    private final BarcodeLookupService barcodeLookupService;

    @Operation(summary = "Cria um produto")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        Product created = useCase.create(request);
        ProductResponse response = ProductResponse.fromEntity(created);
        return ResponseEntity.created(URI.create("/api/products/" + response.id())).body(response);
    }

    @Operation(summary = "Consulta dados de um produto pelo código de barras no OpenFoodFacts")
    @GetMapping("/barcode-lookup")
    public ResponseEntity<OpenFoodFactsProduct> lookupByBarcode(@RequestParam String barcode) {
        return openFoodFactsClient.findByBarcode(barcode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Consulta um código de barras escaneado: catálogo primeiro, depois OpenFoodFacts",
            description = "source = CATALOG traz o produto e, com kitchenId, o saldo dele na cozinha. "
                    + "source = OPEN_FOOD_FACTS traz a sugestão para preencher o cadastro. Código desconhecido: 404.")
    @GetMapping("/by-barcode/{barcode}")
    public ResponseEntity<BarcodeLookupResponse> findByBarcode(@PathVariable String barcode,
                                                                @RequestParam(required = false) Integer kitchenId) {
        return ResponseEntity.ok(barcodeLookupService.lookup(barcode, kitchenId));
    }

    @Operation(summary = "Busca um produto pelo id")
    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> findById(@PathVariable Integer id) {
        return ResponseEntity.ok(ProductResponse.fromEntity(useCase.findById(id)));
    }

    @Operation(summary = "Pesquisa produtos com filtros e paginação")
    @GetMapping
    public ResponseEntity<Page<ProductResponse>> search(@RequestParam(required = false) String name,
                                                          @RequestParam(required = false) Integer categoryId,
                                                          @RequestParam(required = false) Boolean active,
                                                          Pageable pageable) {
        Page<ProductResponse> page = useCase.search(name, categoryId, active, pageable)
                .map(ProductResponse::fromEntity);
        return ResponseEntity.ok(page);
    }

    @Operation(summary = "Atualiza um produto")
    @PreAuthorize(Roles.SUPERVISOR)
    @PutMapping("/{id}")
    public ResponseEntity<ProductResponse> update(@PathVariable Integer id,
                                                   @Valid @RequestBody UpdateProductRequest request) {
        Product updated = useCase.update(id, request);
        return ResponseEntity.ok(ProductResponse.fromEntity(updated));
    }

    @Operation(summary = "Envia ou substitui a foto do produto (Cloudinary)")
    @PreAuthorize(Roles.SUPERVISOR)
    @PutMapping("/{id}/photo")
    public ResponseEntity<ProductResponse> updatePhoto(@PathVariable Integer id,
                                                        @RequestParam("file") MultipartFile file) throws IOException {
        Product updated = useCase.updatePhoto(id, file.getBytes());
        return ResponseEntity.ok(ProductResponse.fromEntity(updated));
    }

    @Operation(summary = "Remove a foto do produto")
    @PreAuthorize(Roles.SUPERVISOR)
    @DeleteMapping("/{id}/photo")
    public ResponseEntity<ProductResponse> removePhoto(@PathVariable Integer id) {
        Product updated = useCase.removePhoto(id);
        return ResponseEntity.ok(ProductResponse.fromEntity(updated));
    }

    @Operation(summary = "Ativa um produto")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable Integer id) {
        useCase.activate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Desativa um produto")
    @PreAuthorize(Roles.SUPERVISOR)
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable Integer id) {
        useCase.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Vincula um fornecedor ao produto")
    @PreAuthorize(Roles.SUPERVISOR)
    @PostMapping("/{id}/suppliers")
    public ResponseEntity<Void> linkSupplier(@PathVariable Integer id, @Valid @RequestBody LinkSupplierRequest request) {
        useCase.linkSupplier(id, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Lista os fornecedores do produto")
    @GetMapping("/{id}/suppliers")
    public ResponseEntity<List<ProductSupplierResponse>> listSuppliers(@PathVariable Integer id) {
        return ResponseEntity.ok(useCase.listSuppliers(id));
    }

    @Operation(summary = "Define estoque mínimo e máximo do produto em uma cozinha")
    @PreAuthorize(Roles.SUPERVISOR)
    @PutMapping("/{id}/kitchen-parameters")
    public ResponseEntity<Void> setKitchenParameters(@PathVariable Integer id,
                                                      @Valid @RequestBody SetKitchenParametersRequest request) {
        useCase.setKitchenParameters(id, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Lista os parâmetros do produto por cozinha")
    @GetMapping("/{id}/kitchen-parameters")
    public ResponseEntity<List<ProductKitchenParameterResponse>> listKitchenParameters(@PathVariable Integer id) {
        return ResponseEntity.ok(useCase.listKitchenParameters(id));
    }
}
