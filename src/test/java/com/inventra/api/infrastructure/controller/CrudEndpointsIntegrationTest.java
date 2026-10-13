package com.inventra.api.infrastructure.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

// CRUD pela API de ponta a ponta (controller + validação + service + JPA + tratamento de erros) com um
// supervisor logado. As chamadas que disparam procedures do Postgres (aprovar requisição, baixa, fechar
// inventário) não rodam no H2 e ficam de fora.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CrudEndpointsIntegrationTest {

    private static final String RAW_PASSWORD = "SenhaForte123";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private KitchenRepository kitchenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private OpenFoodFactsClient openFoodFactsClient;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private String auth;
    private Kitchen kitchen;

    @BeforeEach
    void setUp() throws Exception {
        kitchen = kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Cozinha Teste").code("K-" + UUID.randomUUID().toString().substring(0, 8)).build());
        User supervisor = userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID()).name("Supervisor")
                .email("sup-" + UUID.randomUUID() + "@inventra.com")
                .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                .profile(profile("supervisor")).kitchen(kitchen).active(true).build());
        String body = mockMvc.perform(post("/api/auth/login").contentType(JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(supervisor.getEmail(), RAW_PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        auth = "Bearer " + objectMapper.readValue(body, LoginResponse.class).token();
    }

    private Profile profile(String accessType) {
        return profileRepository.findByAccessType(accessType)
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType(accessType).build()));
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, auth);
        if (body != null) {
            request.contentType(JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private int createAndGetId(String url, String body) throws Exception {
        String response = send(post(url), body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asInt();
    }

    // ===== Autenticação obrigatória =====

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/units").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    // ===== Categorias =====

    @Test
    void categoryFullCrudCycle() throws Exception {
        int id = createAndGetId("/api/categories", "{\"name\":\"Grãos\",\"description\":\"Arroz e feijão\"}");

        send(get("/api/categories/" + id), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Grãos"));
        send(get("/api/categories"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());
        send(put("/api/categories/" + id), "{\"description\":\"Nova descrição\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Nova descrição"))
                .andExpect(jsonPath("$.name").value("Grãos"));
        send(delete("/api/categories/" + id), null).andExpect(status().isNoContent());
        send(get("/api/categories/" + id), null).andExpect(status().isNotFound());
    }

    @Test
    void categoryCreationReturnsLocationHeader() throws Exception {
        send(post("/api/categories"), "{\"name\":\"Bebidas\"}")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/categories/")));
    }

    @Test
    void categoryValidationAndConflictErrors() throws Exception {
        send(post("/api/categories"), "{\"name\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        createAndGetId("/api/categories", "{\"name\":\"Duplicada\"}");
        send(post("/api/categories"), "{\"name\":\"Duplicada\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void validationErrorListsTheInvalidFieldsPerField() throws Exception {
        send(post("/api/categories"), "{\"name\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Um ou mais campos são inválidos."))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ===== Unidades =====

    @Test
    void unitFullCrudCycleAndDuplicateSymbol() throws Exception {
        int id = createAndGetId("/api/units", "{\"symbol\":\"kgx\",\"description\":\"Quilograma\"}");

        send(get("/api/units/" + id), null).andExpect(status().isOk()).andExpect(jsonPath("$.symbol").value("kgx"));
        send(put("/api/units/" + id), "{\"description\":\"Quilo\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("Quilo"));
        send(post("/api/units"), "{\"symbol\":\"kgx\",\"description\":\"Outra\"}").andExpect(status().isConflict());
        send(post("/api/units"), "{\"symbol\":\"muito-longo-demais\",\"description\":\"x\"}")
                .andExpect(status().isBadRequest());
        send(delete("/api/units/" + id), null).andExpect(status().isNoContent());
    }

    // ===== Fornecedores =====

    @Test
    void supplierCrudAndActivation() throws Exception {
        int id = createAndGetId("/api/suppliers",
                "{\"legalName\":\"Distribuidora\",\"cnpj\":\"12.345.678/0001-90\",\"email\":\"d@x.com\",\"rating\":4}");

        send(get("/api/suppliers/" + id), null).andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(4));
        send(put("/api/suppliers/" + id), "{\"rating\":5}").andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(5));
        send(patch("/api/suppliers/" + id + "/deactivate"), null).andExpect(status().isNoContent());
        send(get("/api/suppliers"), null).andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());
        send(patch("/api/suppliers/" + id + "/activate"), null).andExpect(status().isNoContent());
        send(get("/api/suppliers"), null).andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());
    }

    @Test
    void supplierRejectsInvalidInputAndDuplicateCnpj() throws Exception {
        send(post("/api/suppliers"), "{\"legalName\":\"X\",\"cnpj\":\"1\",\"email\":\"nao-e-email\",\"rating\":9}")
                .andExpect(status().isBadRequest());

        createAndGetId("/api/suppliers", "{\"legalName\":\"A\",\"cnpj\":\"98.765.432/0001-10\"}");
        send(post("/api/suppliers"), "{\"legalName\":\"B\",\"cnpj\":\"98.765.432/0001-10\"}").andExpect(status().isConflict());
    }

    // ===== Perfis =====

    @Test
    void profileCrudAndBaseProfileProtection() throws Exception {
        int id = createAndGetId("/api/profiles", "{\"accessType\":\"auditor\",\"description\":\"Leitura\"}");

        send(get("/api/profiles/" + id), null).andExpect(status().isOk());
        send(put("/api/profiles/" + id), "{\"description\":\"Só leitura\"}").andExpect(status().isOk());
        send(delete("/api/profiles/" + id), null).andExpect(status().isNoContent());

        int supervisorProfileId = profile("supervisor").getId();
        send(delete("/api/profiles/" + supervisorProfileId), null).andExpect(status().isConflict());
    }

    // ===== Cozinhas =====

    @Test
    void kitchenReadUpdateAndDeactivateOwnKitchen() throws Exception {
        send(get("/api/kitchens/" + kitchen.getId()), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Cozinha Teste"));
        send(put("/api/kitchens/" + kitchen.getId()), "{\"address\":\"Rua Nova, 1\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.address").value("Rua Nova, 1"));
        send(get("/api/kitchens/by-code/" + kitchen.getCode()), null).andExpect(status().isOk());
        send(patch("/api/kitchens/" + kitchen.getId() + "/deactivate"), null).andExpect(status().isNoContent());
        send(get("/api/kitchens/by-code/" + kitchen.getCode()), null).andExpect(status().isNotFound());
    }

    @Test
    void kitchenOfAnotherUserIsForbidden() throws Exception {
        Kitchen other = kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Outra").code("O-" + UUID.randomUUID().toString().substring(0, 8)).build());

        send(get("/api/kitchens/" + other.getId()), null).andExpect(status().isForbidden());
        send(put("/api/kitchens/" + other.getId()), "{\"name\":\"Invadida\"}").andExpect(status().isForbidden());
    }

    // ===== Produtos =====

    @Test
    void productFullFlowWithSupplierAndKitchenParameters() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"un\",\"description\":\"Unidade\"}");
        int categoryId = createAndGetId("/api/categories", "{\"name\":\"Mercearia\"}");
        int supplierId = createAndGetId("/api/suppliers", "{\"legalName\":\"Forn\",\"cnpj\":\"11.111.111/0001-11\"}");

        int productId = createAndGetId("/api/products",
                "{\"name\":\"Arroz Branco\",\"brand\":\"Tio João\",\"categoryId\":" + categoryId
                        + ",\"unitId\":" + unitId + ",\"barcode\":\"7891234567895\"}");

        send(get("/api/products/" + productId), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Arroz Branco"));
        send(get("/api/products").param("name", "arroz"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.id == " + productId + ")]").exists());
        send(put("/api/products/" + productId), "{\"brand\":\"Camil\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.brand").value("Camil"));

        send(post("/api/products/" + productId + "/suppliers"),
                "{\"supplierId\":" + supplierId + ",\"supplierCode\":\"ARZ-1\",\"referencePrice\":25.90,\"leadTimeDays\":3}")
                .andExpect(status().isNoContent());
        send(get("/api/products/" + productId + "/suppliers"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        send(put("/api/products/" + productId + "/kitchen-parameters"),
                "{\"kitchenId\":" + kitchen.getId() + ",\"minStock\":10,\"maxStock\":50}")
                .andExpect(status().isNoContent());
        send(put("/api/products/" + productId + "/kitchen-parameters"),
                "{\"kitchenId\":" + kitchen.getId() + ",\"minStock\":10,\"maxStock\":5}")
                .andExpect(status().isBadRequest());

        send(patch("/api/products/" + productId + "/deactivate"), null).andExpect(status().isNoContent());
        send(get("/api/products").param("active", "true"), null)
                .andExpect(jsonPath("$.content[?(@.id == " + productId + ")]").doesNotExist());
    }

    @Test
    void productRejectsDuplicateBarcodeAndUnknownUnit() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"cx\",\"description\":\"Caixa\"}");
        createAndGetId("/api/products", "{\"name\":\"A\",\"unitId\":" + unitId + ",\"barcode\":\"123\"}");

        send(post("/api/products"), "{\"name\":\"B\",\"unitId\":" + unitId + ",\"barcode\":\"123\"}")
                .andExpect(status().isConflict());
        send(post("/api/products"), "{\"name\":\"C\",\"unitId\":99999}").andExpect(status().isNotFound());
        send(get("/api/products/99999"), null).andExpect(status().isNotFound());
    }

    // ===== Lotes de estoque =====

    @Test
    void stockBatchEntryListAndAdjust() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"kg2\",\"description\":\"Kilo\"}");
        int productId = createAndGetId("/api/products", "{\"name\":\"Feijão\",\"unitId\":" + unitId + "}");

        int batchId = createAndGetId("/api/stock-batches",
                "{\"productId\":" + productId + ",\"kitchenId\":" + kitchen.getId()
                        + ",\"batchNumber\":\"L1\",\"initialQuantity\":20,\"entryDate\":\"2026-10-01\","
                        + "\"expirationDate\":\"2026-12-31\",\"unitPrice\":4.50}");

        send(get("/api/stock-batches").param("kitchenId", String.valueOf(kitchen.getId())), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + batchId + ")]").exists());
        send(patch("/api/stock-batches/" + batchId + "/adjust"), "{\"newQuantity\":12}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentQuantity").value(12));
        send(patch("/api/stock-batches/" + batchId + "/adjust"), "{\"newQuantity\":-1}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void stockBatchEntryRejectsExpirationBeforeEntryDate() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"l2\",\"description\":\"Litro\"}");
        int productId = createAndGetId("/api/products", "{\"name\":\"Leite\",\"unitId\":" + unitId + "}");

        send(post("/api/stock-batches"),
                "{\"productId\":" + productId + ",\"kitchenId\":" + kitchen.getId()
                        + ",\"batchNumber\":\"L2\",\"initialQuantity\":5,\"entryDate\":\"2026-10-10\","
                        + "\"expirationDate\":\"2026-10-01\"}")
                .andExpect(status().isBadRequest());
    }

    // ===== Telas do app: estoque da cozinha, lotes por produto, fornecedores da cozinha =====

    private int batchOf(int productId, Integer supplierId, String batchNumber, int quantity, String expiration)
            throws Exception {
        return createAndGetId("/api/stock-batches",
                "{\"productId\":" + productId + ",\"kitchenId\":" + kitchen.getId()
                        + (supplierId != null ? ",\"supplierId\":" + supplierId : "")
                        + ",\"batchNumber\":\"" + batchNumber + "\",\"initialQuantity\":" + quantity
                        + ",\"entryDate\":\"2026-10-01\",\"expirationDate\":\"" + expiration + "\"}");
    }

    @Test
    void kitchenStockSummaryListsOnlyProductsLinkedToTheKitchen() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"ltr\",\"description\":\"Litro\"}");
        int dairyId = createAndGetId("/api/categories", "{\"name\":\"Laticínios\"}");
        int grainId = createAndGetId("/api/categories", "{\"name\":\"Grãos\"}");
        int withBatches = createAndGetId("/api/products",
                "{\"name\":\"Leite Integral\",\"brand\":\"Italac\",\"categoryId\":" + dairyId + ",\"unitId\":" + unitId + "}");
        int parameterOnly = createAndGetId("/api/products",
                "{\"name\":\"Arroz Parametro\",\"categoryId\":" + grainId + ",\"unitId\":" + unitId + "}");
        int expiredOnly = createAndGetId("/api/products",
                "{\"name\":\"Creme Vencido\",\"categoryId\":" + dairyId + ",\"unitId\":" + unitId + "}");
        createAndGetId("/api/products", "{\"name\":\"Nunca Usado\",\"unitId\":" + unitId + "}");

        batchOf(withBatches, null, "LEITE1", 12, "2099-12-31");
        batchOf(withBatches, null, "LEITE2", 6, "2099-06-30");
        batchOf(expiredOnly, null, "VELHO", 9, "2026-10-02");
        send(put("/api/products/" + parameterOnly + "/kitchen-parameters"),
                "{\"kitchenId\":" + kitchen.getId() + ",\"minStock\":10,\"maxStock\":50}")
                .andExpect(status().isNoContent());
        send(put("/api/products/" + withBatches + "/kitchen-parameters"),
                "{\"kitchenId\":" + kitchen.getId() + ",\"minStock\":10}")
                .andExpect(status().isNoContent());

        // ordenado por nome; produto que nunca foi usado na cozinha fica de fora
        send(get("/api/kitchens/" + kitchen.getId() + "/stock"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].product.name").value("Arroz Parametro"))
                .andExpect(jsonPath("$[0].totalQuantity").value(0))
                .andExpect(jsonPath("$[0].activeBatches").value(0))
                .andExpect(jsonPath("$[0].nextExpirationDate").doesNotExist())
                .andExpect(jsonPath("$[0].minStock").value(10))
                .andExpect(jsonPath("$[0].lowStock").value(true))
                .andExpect(jsonPath("$[0].category.name").value("Grãos"))
                .andExpect(jsonPath("$[1].product.name").value("Creme Vencido"))
                .andExpect(jsonPath("$[1].totalQuantity").value(0))
                .andExpect(jsonPath("$[1].minStock").doesNotExist())
                .andExpect(jsonPath("$[1].lowStock").value(false))
                .andExpect(jsonPath("$[2].product.name").value("Leite Integral"))
                .andExpect(jsonPath("$[2].product.brand").value("Italac"))
                .andExpect(jsonPath("$[2].unit.symbol").value("ltr"))
                .andExpect(jsonPath("$[2].totalQuantity").value(18))
                .andExpect(jsonPath("$[2].activeBatches").value(2))
                .andExpect(jsonPath("$[2].nextExpirationDate").value("2099-06-30"))
                .andExpect(jsonPath("$[2].lowStock").value(false));

        send(get("/api/kitchens/" + kitchen.getId() + "/stock").param("categoryId", String.valueOf(dairyId)), null)
                .andExpect(jsonPath("$.length()").value(2));
        send(get("/api/kitchens/" + kitchen.getId() + "/stock").param("name", "LEITE"), null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].product.id").value(withBatches));

        // produto desativado some do resumo
        send(patch("/api/products/" + withBatches + "/deactivate"), null).andExpect(status().isNoContent());
        send(get("/api/kitchens/" + kitchen.getId() + "/stock").param("name", "leite"), null)
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void kitchenStockSummaryOfAnotherKitchenIsForbidden() throws Exception {
        Kitchen other = kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Outra").code("O-" + UUID.randomUUID().toString().substring(0, 8)).build());

        send(get("/api/kitchens/" + other.getId() + "/stock"), null).andExpect(status().isForbidden());
    }

    @Test
    void productSearchWorksWithoutTheNameFilter() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"pct\",\"description\":\"Pacote\"}");
        int productId = createAndGetId("/api/products", "{\"name\":\"Macarrão\",\"unitId\":" + unitId + "}");

        send(get("/api/products").param("active", "true").param("size", "200"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + productId + ")]").exists());
    }

    @Test
    void stockBatchListFiltersByProductWhenKitchenIsAlsoGiven() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"und\",\"description\":\"Unidade\"}");
        int first = createAndGetId("/api/products", "{\"name\":\"Óleo\",\"unitId\":" + unitId + "}");
        int second = createAndGetId("/api/products", "{\"name\":\"Vinagre\",\"unitId\":" + unitId + "}");
        int firstBatch = batchOf(first, null, "O1", 5, "2099-01-01");
        int secondBatch = batchOf(second, null, "V1", 5, "2099-01-01");
        String kitchenId = String.valueOf(kitchen.getId());

        send(get("/api/stock-batches").param("kitchenId", kitchenId).param("productId", String.valueOf(first)), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstBatch));
        send(get("/api/stock-batches").param("kitchenId", kitchenId), null)
                .andExpect(jsonPath("$[?(@.id == " + secondBatch + ")]").exists());
    }

    @Test
    void supplierListFiltersByKitchenBatches() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"cxa\",\"description\":\"Caixa\"}");
        int productId = createAndGetId("/api/products", "{\"name\":\"Ovo\",\"unitId\":" + unitId + "}");
        int delivered = createAndGetId("/api/suppliers", "{\"legalName\":\"Zeta Granja\",\"cnpj\":\"22.222.222/0001-22\"}");
        int alsoDelivered = createAndGetId("/api/suppliers", "{\"legalName\":\"Alfa Aves\",\"cnpj\":\"33.333.333/0001-33\"}");
        int neverDelivered = createAndGetId("/api/suppliers", "{\"legalName\":\"Sem Entrega\",\"cnpj\":\"44.444.444/0001-44\"}");
        int inactive = createAndGetId("/api/suppliers", "{\"legalName\":\"Inativo\",\"cnpj\":\"55.555.555/0001-55\"}");
        batchOf(productId, delivered, "E1", 3, "2099-01-01");
        batchOf(productId, delivered, "E2", 3, "2099-01-01");
        batchOf(productId, alsoDelivered, "E3", 3, "2099-01-01");
        batchOf(productId, inactive, "E4", 3, "2099-01-01");
        send(patch("/api/suppliers/" + inactive + "/deactivate"), null).andExpect(status().isNoContent());

        // sem filtro continua devolvendo todos os ativos
        send(get("/api/suppliers"), null)
                .andExpect(jsonPath("$[?(@.id == " + neverDelivered + ")]").exists());
        // com kitchenId: só quem tem lote na cozinha, sem repetir, ordenado por razão social
        send(get("/api/suppliers").param("kitchenId", String.valueOf(kitchen.getId())), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].legalName").value("Alfa Aves"))
                .andExpect(jsonPath("$[1].legalName").value("Zeta Granja"));
    }

    @Test
    void supplierListOfAnotherKitchenIsForbidden() throws Exception {
        Kitchen other = kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Outra").code("O-" + UUID.randomUUID().toString().substring(0, 8)).build());

        send(get("/api/suppliers").param("kitchenId", String.valueOf(other.getId())), null)
                .andExpect(status().isForbidden());
    }

    // ===== Alertas =====

    @Test
    void alertCreateReadAndDelete() throws Exception {
        int id = createAndGetId("/api/alerts",
                "{\"type\":\"LOW_STOCK\",\"severity\":\"HIGH\",\"kitchenId\":" + kitchen.getId() + ",\"message\":\"Estoque baixo\"}");

        send(get("/api/alerts").param("kitchenId", String.valueOf(kitchen.getId())).param("unread", "true"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());
        send(patch("/api/alerts/" + id + "/read"), null).andExpect(status().isOk());
        send(get("/api/alerts").param("kitchenId", String.valueOf(kitchen.getId())).param("unread", "true"), null)
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());
        send(delete("/api/alerts/" + id), null).andExpect(status().isNoContent());
        send(get("/api/alerts/" + id), null).andExpect(status().isNotFound());
    }

    @Test
    void alertValidationRejectsMissingFields() throws Exception {
        send(post("/api/alerts"), "{\"type\":\"\",\"kitchenId\":" + kitchen.getId() + "}")
                .andExpect(status().isBadRequest());
    }

    // ===== Inventário =====

    @Test
    void inventoryOpenCountListAndCancel() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"pc\",\"description\":\"Peça\"}");
        int productId = createAndGetId("/api/products", "{\"name\":\"Sal\",\"unitId\":" + unitId + "}");
        int batchId = createAndGetId("/api/stock-batches",
                "{\"productId\":" + productId + ",\"kitchenId\":" + kitchen.getId()
                        + ",\"batchNumber\":\"L3\",\"initialQuantity\":10,\"entryDate\":\"2026-10-01\"}");

        int inventoryId = createAndGetId("/api/inventories", "{\"kitchenId\":" + kitchen.getId() + ",\"note\":\"Mensal\"}");

        // só um inventário aberto por cozinha
        send(post("/api/inventories"), "{\"kitchenId\":" + kitchen.getId() + "}").andExpect(status().isConflict());

        send(post("/api/inventories/" + inventoryId + "/counts"),
                "{\"batchId\":" + batchId + ",\"physicalQuantity\":8}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.divergence").value(-2));
        // lote já contado
        send(post("/api/inventories/" + inventoryId + "/counts"),
                "{\"batchId\":" + batchId + ",\"physicalQuantity\":9}").andExpect(status().isConflict());
        send(get("/api/inventories/" + inventoryId + "/counts"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        send(patch("/api/inventories/" + inventoryId + "/cancel"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        // inventário cancelado não aceita nova contagem
        send(post("/api/inventories/" + inventoryId + "/counts"),
                "{\"batchId\":" + batchId + ",\"physicalQuantity\":7}").andExpect(status().isConflict());
    }

    // ===== Requisições =====

    @Test
    void requisitionCreateAddItemAndSubmit() throws Exception {
        int unitId = createAndGetId("/api/units", "{\"symbol\":\"sc\",\"description\":\"Saco\"}");
        int productId = createAndGetId("/api/products", "{\"name\":\"Açúcar\",\"unitId\":" + unitId + "}");

        int requisitionId = createAndGetId("/api/requisitions",
                "{\"type\":\"PURCHASE\",\"origin\":\"Cozinha Teste\",\"kitchenId\":" + kitchen.getId() + "}");

        // sem itens não pode enviar
        send(patch("/api/requisitions/" + requisitionId + "/submit"), null).andExpect(status().isConflict());

        send(post("/api/requisitions/" + requisitionId + "/items"),
                "{\"productId\":" + productId + ",\"quantity\":3,\"estimatedPrice\":9.90}")
                .andExpect(status().isOk());
        send(get("/api/requisitions/" + requisitionId + "/items"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        send(patch("/api/requisitions/" + requisitionId + "/submit"), null).andExpect(status().isOk());
        send(get("/api/requisitions").param("kitchenId", String.valueOf(kitchen.getId())), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + requisitionId + ")]").exists());
    }

    @Test
    void requisitionRejectsInvalidType() throws Exception {
        send(post("/api/requisitions"), "{\"type\":\"INVALIDO\",\"origin\":\"x\",\"kitchenId\":" + kitchen.getId() + "}")
                .andExpect(status().isBadRequest());
    }

    // ===== Usuários =====

    @Test
    void userCreationByTheSupervisor() throws Exception {
        int profileId = profile("estoquista").getId();
        String email = "novo-" + UUID.randomUUID() + "@inventra.com";

        send(post("/api/users"),
                "{\"name\":\"Novo\",\"email\":\"" + email + "\",\"password\":\"Senha@1234\",\"kitchenId\":"
                        + kitchen.getId() + ",\"profileId\":" + profileId + "}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value(email));
        send(post("/api/users"),
                "{\"name\":\"Novo\",\"email\":\"" + email + "\",\"password\":\"Senha@1234\",\"profileId\":" + profileId + "}")
                .andExpect(status().isConflict());
        send(post("/api/users"),
                "{\"name\":\"Novo\",\"email\":\"x@x.com\",\"password\":\"curta\",\"profileId\":" + profileId + "}")
                .andExpect(status().isBadRequest());
        send(get("/api/users/me"), null).andExpect(status().isOk());
    }
}
