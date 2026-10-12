package com.inventra.api.infrastructure.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.stock.StockBatch;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.client.openfoodfacts.model.OpenFoodFactsProduct;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.StockBatchRepository;
import com.inventra.api.infrastructure.repository.UnitRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

// Segunda leva de pedidos do time mobile: login com Google, descoberta de cozinhas, notificações por pessoa,
// rascunho de requisição, movimentações de estoque e consulta por código de barras. As procedures só existem
// no Postgres: aqui (H2) ficam os fluxos que não passam por elas.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MobileFeaturesIntegrationTest {

    private static final String RAW_PASSWORD = "SenhaForte123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private KitchenRepository kitchenRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UnitRepository unitRepository;

    @Autowired
    private StockBatchRepository stockBatchRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private OpenFoodFactsClient openFoodFactsClient;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ===== Login com Google =====

    @Test
    void googleFirstAccessAsksForTheAccessTypeAndThenCreatesAPasswordlessAccount() throws Exception {
        String email = "felipe-" + UUID.randomUUID() + "@gmail.com";
        String idToken = "google|uid-" + UUID.randomUUID() + "|" + email + "|Felipe Souza";

        google(idToken, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_REQUIRED"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Felipe Souza"));

        String body = google(idToken, "ESTOQUISTA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.name").value("Felipe Souza"))
                .andExpect(jsonPath("$.user.profile.accessType").value("ESTOQUISTA"))
                .andExpect(jsonPath("$.user.hasPassword").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode session = objectMapper.readTree(body);

        // o JWT do backend e o refresh funcionam como no login por senha
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(session.get("token").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + session.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isOk());

        // segundo acesso: entra direto, sem accessType
        google(idToken, null).andExpect(status().isOk());

        // conta sem senha: o login por e-mail e senha falha com a mensagem de sempre
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"qualquer123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("E-mail ou senha inválidos."));
    }

    @Test
    void googleLoginLinksTheExistingAccountWithTheSameEmail() throws Exception {
        User user = newUser("comprador", null);
        String idToken = "google|uid-" + UUID.randomUUID() + "|" + user.getEmail() + "|Outro Nome";

        google(idToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.user.hasPassword").value(true));

        // a senha antiga continua valendo
        login(user.getEmail());
    }

    @Test
    void googleLoginRefusesInvalidTokenUnverifiedEmailAndDisabledAccount() throws Exception {
        google("token-de-outro-projeto", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_GOOGLE_TOKEN"));

        google("unverified|uid-1|alguem-" + UUID.randomUUID() + "@gmail.com|Alguém", "ESTOQUISTA")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        User disabled = newUser("estoquista", null);
        disabled.setActive(false);
        userRepository.saveAndFlush(disabled);
        google("google|uid-" + UUID.randomUUID() + "|" + disabled.getEmail() + "|X", null)
                .andExpect(status().isForbidden());
    }

    @Test
    void googleAccountCanCreateAPasswordWithoutTheCurrentOne() throws Exception {
        String email = "ana-" + UUID.randomUUID() + "@gmail.com";
        JsonNode session = objectMapper.readTree(google("google|uid-" + UUID.randomUUID() + "|" + email + "|Ana", "COMPRADOR")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(patch("/api/users/" + session.get("user").get("id").asText() + "/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session.get("token").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        login(email);
    }

    // ===== Localização e descoberta de cozinhas =====

    @Test
    void kitchenIsCreatedWithLocationAndRejectsInvalidCoordinates() throws Exception {
        String token = login(newUser("supervisor", null).getEmail());

        send(post("/api/kitchens"), token, "{\"name\":\"Filial Polo\",\"latitude\":91,\"longitude\":0}")
                .andExpect(status().isBadRequest());

        send(post("/api/kitchens"), token, """
                {"name":"Sabor Filial Sul","address":"Av. das Indústrias, 450","city":"São Paulo","state":"sp",
                 "latitude":-23.55052,"longitude":-46.633308}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.city").value("São Paulo"))
                .andExpect(jsonPath("$.state").value("SP"))
                .andExpect(jsonPath("$.latitude").value(-23.55052))
                .andExpect(jsonPath("$.acceptingRequests").value(true));
    }

    @Test
    void discoverListsOnlyKitchensOpenToRequestsWithoutExposingTheCode() throws Exception {
        String marker = "Descoberta-" + UUID.randomUUID();
        Kitchen open = newKitchen(marker + " Aberta", -23.55052, -46.633308, true);
        newKitchen(marker + " Fechada", -23.56, -46.64, false);
        Kitchen inactive = newKitchen(marker + " Inativa", -23.57, -46.65, true);
        inactive.setActive(false);
        kitchenRepository.saveAndFlush(inactive);
        String token = login(newUser("estoquista", null).getEmail());

        send(get("/api/kitchens/discover").param("q", marker.toLowerCase()), token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(open.getId()))
                .andExpect(jsonPath("$.content[0].state").value("SP"))
                // coordenadas arredondadas para 4 casas
                .andExpect(jsonPath("$.content[0].latitude").value(-23.5505))
                .andExpect(jsonPath("$.content[0].code").doesNotExist())
                .andExpect(jsonPath("$.content[0].distanceKm").doesNotExist())
                .andExpect(jsonPath("$.content[0].myRequestStatus").doesNotExist());

        send(get("/api/kitchens/discover").param("size", "51"), token, null).andExpect(status().isBadRequest());
        send(get("/api/kitchens/discover").param("lat", "-23.5"), token, null).andExpect(status().isBadRequest());
    }

    @Test
    void discoverOrdersByDistanceAndLimitsToTheRadius() throws Exception {
        String marker = "Raio-" + UUID.randomUUID();
        Kitchen near = newKitchen(marker + " Perto", -23.5614, -46.6559, true);
        Kitchen nearer = newKitchen(marker + " Colada", -23.5506, -46.6334, true);
        newKitchen(marker + " Rio", -22.9068, -43.1729, true);
        Kitchen noPin = newKitchen(marker + " Sem pin", null, null, true);
        String token = login(newUser("estoquista", null).getEmail());

        send(get("/api/kitchens/discover").param("q", marker)
                .param("lat", "-23.55052").param("lng", "-46.633308").param("radiusKm", "50"), token, null)
                .andExpect(status().isOk())
                // o Rio (~360 km) fica fora; a cozinha sem coordenadas vem no fim, sem distância
                .andExpect(jsonPath("$.content[*].id").value(contains(nearer.getId(), near.getId(), noPin.getId())))
                .andExpect(jsonPath("$.content[1].distanceKm").value(2.6))
                .andExpect(jsonPath("$.content[2].distanceKm").doesNotExist());
    }

    @Test
    void accessRequestByKitchenIdShowsUpAsPendingInDiscoveryAndNotifiesTheSupervisor() throws Exception {
        String marker = "Pedido-" + UUID.randomUUID();
        Kitchen kitchen = newKitchen(marker, null, null, true);
        Kitchen closed = newKitchen(marker + " fechada", null, null, false);
        String supervisorToken = login(newUser("supervisor", kitchen).getEmail());
        User applicant = newUser("estoquista", null);
        String token = login(applicant.getEmail());

        send(post("/api/kitchen-access-requests"), token, "{}").andExpect(status().isBadRequest());
        send(post("/api/kitchen-access-requests"), token,
                "{\"kitchenId\":" + kitchen.getId() + ",\"code\":\"" + kitchen.getCode() + "\"}")
                .andExpect(status().isBadRequest());
        send(post("/api/kitchen-access-requests"), token, "{\"kitchenId\":" + closed.getId() + "}")
                .andExpect(status().isConflict());

        send(post("/api/kitchen-access-requests"), token, "{\"kitchenId\":" + kitchen.getId() + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        send(get("/api/kitchens/discover").param("q", marker), token, null)
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].myRequestStatus").value("PENDING"));

        // o supervisor é avisado; o aviso some dos não lidos quando ele decide
        String alerts = send(get("/api/alerts").param("kitchenId", String.valueOf(kitchen.getId())).param("unread", "true"),
                supervisorToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("ACCESS_REQUEST"))
                .andExpect(jsonPath("$[0].message").value(applicant.getName() + " pediu para entrar na cozinha."))
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(alerts).get(0).get("refKey").asText();

        send(post("/api/kitchen-access-requests/" + requestId + "/reject"), supervisorToken, "{\"reason\":\"Equipe completa\"}")
                .andExpect(status().isOk());
        send(get("/api/alerts").param("kitchenId", String.valueOf(kitchen.getId())).param("unread", "true"),
                supervisorToken, null)
                .andExpect(jsonPath("$", hasSize(0)));
        send(get("/api/kitchens/discover").param("q", marker), token, null)
                .andExpect(jsonPath("$.content[0].myRequestStatus").value("REJECTED"));
    }

    // ===== Notificações =====

    @Test
    void readingAnAlertOnlyCountsForWhoReadIt() throws Exception {
        Kitchen kitchen = newKitchen("Alertas-" + UUID.randomUUID(), null, null, true);
        String supervisorToken = login(newUser("supervisor", kitchen).getEmail());
        String stockistToken = login(newUser("estoquista", kitchen).getEmail());
        String kitchenId = String.valueOf(kitchen.getId());

        int first = createAlert(supervisorToken, kitchen, "Estoque de arroz baixo");
        createAlert(supervisorToken, kitchen, "Estoque de feijão baixo");

        send(patch("/api/alerts/" + first + "/read"), stockistToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        // o estoquista leu um; para o supervisor os dois continuam não lidos
        send(get("/api/alerts").param("kitchenId", kitchenId).param("unread", "true"), stockistToken, null)
                .andExpect(jsonPath("$", hasSize(1)));
        send(get("/api/alerts").param("kitchenId", kitchenId).param("unread", "true"), supervisorToken, null)
                .andExpect(jsonPath("$", hasSize(2)));
        send(get("/api/alerts/" + first), supervisorToken, null).andExpect(jsonPath("$.read").value(false));

        send(patch("/api/alerts/read-all").param("kitchenId", kitchenId), supervisorToken, null)
                .andExpect(status().isNoContent());
        send(get("/api/alerts").param("kitchenId", kitchenId).param("unread", "true"), supervisorToken, null)
                .andExpect(jsonPath("$", hasSize(0)));
        send(get("/api/alerts").param("kitchenId", kitchenId), supervisorToken, null)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].read").value(true));
    }

    @Test
    void unregisteredProductWarnsOnlyTheSupervisorsAndIsNotDuplicated() throws Exception {
        Kitchen kitchen = newKitchen("Scan-" + UUID.randomUUID(), null, null, true);
        String supervisorToken = login(newUser("supervisor", kitchen).getEmail());
        String stockistToken = login(newUser("estoquista", kitchen).getEmail());
        String buyerToken = login(newUser("comprador", kitchen).getEmail());
        String kitchenId = String.valueOf(kitchen.getId());
        String barcode = "789" + (System.nanoTime() % 1_000_000_000L);
        when(openFoodFactsClient.findByBarcode(barcode))
                .thenReturn(Optional.of(new OpenFoodFactsProduct("Leite Integral Italac 1L", "Italac", null, "1 L")));
        String payload = "{\"kitchenId\":" + kitchenId + ",\"barcode\":\"" + barcode + "\"}";

        send(post("/api/alerts/unregistered-product"), buyerToken, payload).andExpect(status().isForbidden());

        String created = send(post("/api/alerts/unregistered-product"), stockistToken, payload)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("UNREGISTERED_PRODUCT"))
                .andExpect(jsonPath("$.severity").value("MEDIUM"))
                .andExpect(jsonPath("$.message").value("Produto não cadastrado: Leite Integral Italac 1L (" + barcode + ")"))
                .andReturn().getResponse().getContentAsString();
        int alertId = objectMapper.readTree(created).get("id").asInt();

        // mesmo código de novo: devolve o aviso que já existe
        send(post("/api/alerts/unregistered-product"), stockistToken, payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alertId));

        send(get("/api/alerts").param("kitchenId", kitchenId), supervisorToken, null)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].refKey").value(barcode));
        // é um aviso para o supervisor: estoquista e comprador não o recebem
        send(get("/api/alerts").param("kitchenId", kitchenId), stockistToken, null).andExpect(jsonPath("$", hasSize(0)));
        send(get("/api/alerts").param("kitchenId", kitchenId), buyerToken, null).andExpect(jsonPath("$", hasSize(0)));
    }

    // ===== Requisições =====

    @Test
    void requisitionStartsAsDraftAndOnlyReachesTheSupervisorAfterSubmit() throws Exception {
        Kitchen kitchen = newKitchen("Requisicoes-" + UUID.randomUUID(), null, null, true);
        String supervisorToken = login(newUser("supervisor", kitchen).getEmail());
        User buyer = newUser("comprador", kitchen);
        String buyerToken = login(buyer.getEmail());
        String kitchenId = String.valueOf(kitchen.getId());
        Product milk = newProduct("Leite Integral", null);
        Product rice = newProduct("Arroz Camil", null);

        String created = send(post("/api/requisitions"), buyerToken,
                "{\"type\":\"PURCHASE\",\"origin\":\"Compras\",\"kitchenId\":" + kitchenId + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.itemCount").value(0))
                .andReturn().getResponse().getContentAsString();
        int id = objectMapper.readTree(created).get("id").asInt();

        send(patch("/api/requisitions/" + id + "/submit"), buyerToken, null).andExpect(status().isConflict());

        send(post("/api/requisitions/" + id + "/items"), buyerToken,
                "{\"productId\":" + milk.getId() + ",\"quantity\":6,\"estimatedPrice\":4.50}")
                .andExpect(status().isOk());
        send(post("/api/requisitions/" + id + "/items"), buyerToken,
                "{\"productId\":" + rice.getId() + ",\"quantity\":2,\"estimatedPrice\":22.10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.estimatedTotal").value(71.20));

        // rascunho: o comprador vê, o supervisor não
        send(get("/api/requisitions").param("kitchenId", kitchenId), buyerToken, null).andExpect(jsonPath("$", hasSize(1)));
        send(get("/api/requisitions").param("kitchenId", kitchenId), supervisorToken, null).andExpect(jsonPath("$", hasSize(0)));

        send(patch("/api/requisitions/" + id + "/submit"), buyerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        // filtros combinados e resumo dos itens no card
        send(get("/api/requisitions").param("kitchenId", kitchenId).param("status", "UNDER_REVIEW")
                .param("requesterId", buyer.getId().toString()), supervisorToken, null)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].itemCount").value(2))
                .andExpect(jsonPath("$[0].itemNames").value(contains("Leite Integral", "Arroz Camil")));
        send(get("/api/requisitions").param("kitchenId", kitchenId).param("status", "APPROVED"), supervisorToken, null)
                .andExpect(jsonPath("$", hasSize(0)));
        send(get("/api/requisitions/" + id), supervisorToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedTotal").value(71.20));

        // depois de enviada, os itens não mudam mais
        send(post("/api/requisitions/" + id + "/items"), buyerToken,
                "{\"productId\":" + milk.getId() + ",\"quantity\":1}")
                .andExpect(status().isConflict());

        send(get("/api/alerts").param("kitchenId", kitchenId).param("unread", "true"), supervisorToken, null)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("REQUISITION_SUBMITTED"))
                .andExpect(jsonPath("$[0].message").value("Nova requisição #" + id + " para análise."))
                .andExpect(jsonPath("$[0].refKey").value(String.valueOf(id)));
        // o comprador consulta os alertas, mas esse não é para ele
        send(get("/api/alerts").param("kitchenId", kitchenId), buyerToken, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ===== Movimentações e scan =====

    @Test
    void batchEntryAndAdjustmentAreRecordedAsMovements() throws Exception {
        Kitchen kitchen = newKitchen("Movimentos-" + UUID.randomUUID(), null, null, true);
        User stockist = newUser("estoquista", kitchen);
        String token = login(stockist.getEmail());
        String kitchenId = String.valueOf(kitchen.getId());
        Product milk = newProduct("Leite Integral", null);

        String created = send(post("/api/stock-batches"), token, """
                {"productId":%d,"kitchenId":%s,"batchNumber":"QA-01","initialQuantity":10,"entryDate":"%s"}"""
                .formatted(milk.getId(), kitchenId, LocalDate.now()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int batchId = objectMapper.readTree(created).get("id").asInt();

        send(patch("/api/stock-batches/" + batchId + "/adjust"), token, "{\"newQuantity\":7.5}")
                .andExpect(status().isOk());

        send(get("/api/stock-movements").param("kitchenId", kitchenId).param("productId", String.valueOf(milk.getId())),
                token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].type").value(contains("ADJUSTMENT", "ENTRY")))
                .andExpect(jsonPath("$[0].quantity").value(-2.5))
                .andExpect(jsonPath("$[0].balanceAfter").value(7.5))
                .andExpect(jsonPath("$[0].batch.batchNumber").value("QA-01"))
                .andExpect(jsonPath("$[0].product.name").value("Leite Integral"))
                .andExpect(jsonPath("$[0].user.id").value(stockist.getId().toString()));

        send(get("/api/stock-movements").param("kitchenId", kitchenId).param("type", "ENTRY"), token, null)
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].quantity").value(10));

        // a baixa por produto recusa o motivo reservado à aprovação de requisição
        send(post("/api/stock-batches/consume"), token,
                "{\"kitchenId\":" + kitchenId + ",\"productId\":" + milk.getId() + ",\"quantity\":1,\"reason\":\"REQUISITION\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void barcodeLookupChecksTheCatalogFirstAndThenOpenFoodFacts() throws Exception {
        Kitchen kitchen = newKitchen("Barcode-" + UUID.randomUUID(), null, null, true);
        String token = login(newUser("estoquista", kitchen).getEmail());
        String barcode = "7898" + (System.nanoTime() % 1_000_000_000L);
        Product milk = newProduct("Leite Integral", barcode);
        addBatch(milk, kitchen, "12", LocalDate.now().plusDays(20));
        addBatch(milk, kitchen, "6", LocalDate.now().plusDays(5));
        addBatch(milk, kitchen, "30", LocalDate.now().minusDays(1));

        send(get("/api/products/by-barcode/" + barcode).param("kitchenId", String.valueOf(kitchen.getId())), token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CATALOG"))
                .andExpect(jsonPath("$.product.id").value(milk.getId()))
                // o lote vencido não conta
                .andExpect(jsonPath("$.kitchenStock.totalQuantity").value(18))
                .andExpect(jsonPath("$.kitchenStock.activeBatches").value(2))
                .andExpect(jsonPath("$.kitchenStock.nextExpirationDate").value(LocalDate.now().plusDays(5).toString()))
                .andExpect(jsonPath("$.suggestion").doesNotExist());

        send(get("/api/products/by-barcode/" + barcode), token, null)
                .andExpect(jsonPath("$.kitchenStock").doesNotExist());

        when(openFoodFactsClient.findByBarcode("111222333"))
                .thenReturn(Optional.of(new OpenFoodFactsProduct("Achocolatado", "Marca", null, "400 g")));
        send(get("/api/products/by-barcode/111222333"), token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("OPEN_FOOD_FACTS"))
                .andExpect(jsonPath("$.suggestion.name").value("Achocolatado"))
                .andExpect(jsonPath("$.product").doesNotExist());

        // base pública fora do ar conta como "não encontrado", para o app seguir para o cadastro manual
        when(openFoodFactsClient.findByBarcode(anyString())).thenThrow(new IllegalStateException("fora do ar"));
        send(get("/api/products/by-barcode/999888777"), token, null).andExpect(status().isNotFound());
        send(get("/api/products/by-barcode/abc"), token, null).andExpect(status().isBadRequest());
    }

    // ===== helpers =====

    private ResultActions google(String idToken, String accessType) throws Exception {
        String accessTypeField = accessType == null ? "" : ",\"accessType\":\"" + accessType + "\"";
        return mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"" + idToken + "\"" + accessTypeField + "}"));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private int createAlert(String token, Kitchen kitchen, String message) throws Exception {
        String body = send(post("/api/alerts"), token,
                "{\"type\":\"STOCK\",\"severity\":\"HIGH\",\"kitchenId\":" + kitchen.getId() + ",\"message\":\"" + message + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asInt();
    }

    private Product newProduct(String name, String barcode) {
        Unit unit = unitRepository.saveAndFlush(Unit.builder()
                .symbol("u" + UUID.randomUUID().toString().substring(0, 4)).description("Litro").build());
        return productRepository.saveAndFlush(Product.builder().name(name).unit(unit).barcode(barcode).build());
    }

    private void addBatch(Product product, Kitchen kitchen, String quantity, LocalDate expiration) {
        stockBatchRepository.saveAndFlush(StockBatch.builder()
                .product(product)
                .kitchen(kitchen)
                .batchNumber("L-" + UUID.randomUUID().toString().substring(0, 8))
                .initialQuantity(new BigDecimal(quantity))
                .currentQuantity(new BigDecimal(quantity))
                .entryDate(LocalDate.now().minusDays(40))
                .expirationDate(expiration)
                .build());
    }

    private Kitchen newKitchen(String name, Double latitude, Double longitude, boolean acceptingRequests) {
        return kitchenRepository.saveAndFlush(Kitchen.builder()
                .name(name)
                .code("K" + UUID.randomUUID().toString().substring(0, 7).toUpperCase())
                .city(latitude == null ? null : "São Paulo")
                .state(latitude == null ? null : "SP")
                .latitude(latitude)
                .longitude(longitude)
                .acceptingRequests(acceptingRequests)
                .build());
    }

    private Profile profile(String accessType) {
        return profileRepository.findByAccessType(accessType)
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType(accessType).build()));
    }

    private User newUser(String accessType, Kitchen kitchen) {
        return userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID())
                .name("Usuário " + accessType)
                .email(accessType + "-" + UUID.randomUUID() + "@inventra.com")
                .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                .profile(profile(accessType))
                .kitchen(kitchen)
                .active(true)
                .build());
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
