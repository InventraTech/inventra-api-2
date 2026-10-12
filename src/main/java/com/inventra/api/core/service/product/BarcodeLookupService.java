package com.inventra.api.core.service.product;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.service.product.model.response.BarcodeLookupResponse;
import com.inventra.api.core.service.stockbatch.StockBatchUseCase;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.client.openfoodfacts.model.OpenFoodFactsProduct;
import com.inventra.api.infrastructure.exception.ResourceNotFoundException;
import com.inventra.api.infrastructure.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Consulta do scan: o app só manda o código de barras. Procura no catálogo e, se não achar, no
// OpenFoodFacts; se nenhum dos dois conhece o código, 404 (o supervisor cadastra à mão).
@Service
@Slf4j
@RequiredArgsConstructor
public class BarcodeLookupService {

    private final ProductRepository productRepository;
    private final StockBatchUseCase stockBatchUseCase;
    private final OpenFoodFactsClient openFoodFactsClient;

    public BarcodeLookupResponse lookup(String barcode, Integer kitchenId) {
        if (barcode == null || !barcode.matches("\\d{1,50}")) {
            throw new IllegalArgumentException("Código de barras inválido: informe apenas dígitos.");
        }

        Optional<Product> product = productRepository.findByBarcode(barcode);
        if (product.isPresent()) {
            // summarizeProductStock valida o acesso à cozinha informada
            var stock = kitchenId == null ? null : stockBatchUseCase.summarizeProductStock(kitchenId, product.get().getId());
            return BarcodeLookupResponse.catalog(barcode, product.get(), stock);
        }

        return findInOpenFoodFacts(barcode)
            .map(suggestion -> BarcodeLookupResponse.openFoodFacts(barcode, suggestion))
            .orElseThrow(() -> new ResourceNotFoundException("Produto não encontrado para esse código de barras."));
    }

    // OpenFoodFacts fora do ar ou lento conta como "não encontrado": o app segue para o cadastro manual
    // em vez de receber um erro.
    private Optional<OpenFoodFactsProduct> findInOpenFoodFacts(String barcode) {
        try {
            return openFoodFactsClient.findByBarcode(barcode);
        } catch (RuntimeException ex) {
            log.warn("OpenFoodFacts indisponível na consulta do código {}: {}", barcode, ex.toString());
            return Optional.empty();
        }
    }
}
