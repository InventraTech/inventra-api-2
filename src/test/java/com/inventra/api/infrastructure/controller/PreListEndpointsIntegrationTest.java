package com.inventra.api.infrastructure.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.prelist.PreList;
import com.inventra.api.core.domain.prelist.PreListItem;
import com.inventra.api.core.domain.product.Product;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.supplier.Supplier;
import com.inventra.api.core.domain.unit.Unit;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.auth.model.request.LoginRequest;
import com.inventra.api.core.service.auth.model.response.LoginResponse;
import com.inventra.api.infrastructure.client.openfoodfacts.OpenFoodFactsClient;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.PreListItemRepository;
import com.inventra.api.infrastructure.repository.PreListRepository;
import com.inventra.api.infrastructure.repository.ProductRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.SupplierRepository;
import com.inventra.api.infrastructure.repository.UnitRepository;
import com.inventra.api.infrastructure.repository.UserRepository;

// Leitura das pré-listas de ponta a ponta (segurança + controller + service + JPA). As listas são gravadas
// direto pelos repositories, como o RPA faz no banco.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PreListEndpointsIntegrationTest {

    private static final String RAW_PASSWORD = "SenhaForte123";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private KitchenRepository kitchenRepository;
    @Autowired private PreListRepository preListRepository;
    @Autowired private PreListItemRepository preListItemRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private UnitRepository unitRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private OpenFoodFactsClient openFoodFactsClient;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/pre-lists")).andExpect(status().isUnauthorized());
    }

    @Test
    void compradorListsPreListsNewestFirstWithoutItems() throws Exception {
        PreList older = preListRepository.saveAndFlush(PreList.builder().listDate(LocalDate.of(2026, 10, 1)).build());
        PreList newer = preListRepository.saveAndFlush(
                PreList.builder().listDate(LocalDate.of(2026, 10, 20)).sourceId(15).build());

        mockMvc.perform(get("/api/pre-lists").header(HttpHeaders.AUTHORIZATION, bearer("comprador")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(newer.getId()))
                .andExpect(jsonPath("$[0].sourceId").value(15))
                .andExpect(jsonPath("$[0].items").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(older.getId()))
                .andExpect(jsonPath("$[1].sourceId").doesNotExist());
    }

    @Test
    void supervisorGetsPreListWithItems() throws Exception {
        PreList preList = preListRepository.saveAndFlush(PreList.builder().listDate(LocalDate.of(2026, 10, 20)).build());
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.builder()
                .legalName("Distribuidora Ltda").cnpj("12.345.678/" + UUID.randomUUID().toString().substring(0, 6)).build());
        Unit unit = unitRepository.saveAndFlush(Unit.builder()
                .symbol(UUID.randomUUID().toString().substring(0, 4)).description("Quilo").build());
        Product product = productRepository.saveAndFlush(Product.builder().name("Arroz").unit(unit).build());
        preListItemRepository.saveAndFlush(PreListItem.builder()
                .preList(preList).supplier(supplier).product(product).quantity(new BigDecimal("12.500")).build());

        mockMvc.perform(get("/api/pre-lists/" + preList.getId()).header(HttpHeaders.AUTHORIZATION, bearer("supervisor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(preList.getId()))
                .andExpect(jsonPath("$.listDate").value("2026-10-20"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].product.name").value("Arroz"))
                .andExpect(jsonPath("$.items[0].supplier.legalName").value("Distribuidora Ltda"))
                .andExpect(jsonPath("$.items[0].quantity").value(12.5));
    }

    @Test
    void unknownPreListReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/pre-lists/999999").header(HttpHeaders.AUTHORIZATION, bearer("supervisor")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listFiltersByKitchenOfTheLoggedUser() throws Exception {
        Kitchen own = newKitchen();
        Kitchen other = newKitchen();
        PreList ownList = preListRepository.saveAndFlush(
                PreList.builder().listDate(LocalDate.of(2026, 10, 20)).kitchen(own).build());
        preListRepository.saveAndFlush(PreList.builder().listDate(LocalDate.of(2026, 10, 21)).kitchen(other).build());
        preListRepository.saveAndFlush(PreList.builder().listDate(LocalDate.of(2026, 10, 22)).build());
        String token = bearer("comprador", own);

        mockMvc.perform(get("/api/pre-lists").param("kitchenId", own.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ownList.getId()))
                .andExpect(jsonPath("$[0].kitchenId").value(own.getId()));
    }

    @Test
    void listWithKitchenOfAnotherUserIsForbidden() throws Exception {
        Kitchen other = newKitchen();

        mockMvc.perform(get("/api/pre-lists").param("kitchenId", other.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("comprador", newKitchen())))
                .andExpect(status().isForbidden());
    }

    @Test
    void preListOfAnotherKitchenIsForbidden() throws Exception {
        PreList preList = preListRepository.saveAndFlush(
                PreList.builder().listDate(LocalDate.of(2026, 10, 20)).kitchen(newKitchen()).build());

        mockMvc.perform(get("/api/pre-lists/" + preList.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer("supervisor", newKitchen())))
                .andExpect(status().isForbidden());
    }

    @Test
    void estoquistaCannotReadPreLists() throws Exception {
        mockMvc.perform(get("/api/pre-lists").header(HttpHeaders.AUTHORIZATION, bearer("estoquista")))
                .andExpect(status().isForbidden());
    }

    private Kitchen newKitchen() {
        return kitchenRepository.saveAndFlush(Kitchen.builder()
                .name("Cozinha Teste").code("K-" + UUID.randomUUID().toString().substring(0, 8)).build());
    }

    private String bearer(String accessType) throws Exception {
        return bearer(accessType, newKitchen());
    }

    private String bearer(String accessType, Kitchen kitchen) throws Exception {
        Profile profile = profileRepository.findByAccessType(accessType)
                .orElseGet(() -> profileRepository.saveAndFlush(Profile.builder().accessType(accessType).build()));
        User user = userRepository.saveAndFlush(User.builder()
                .id(UUID.randomUUID()).name(accessType)
                .email(accessType + "-" + UUID.randomUUID() + "@inventra.com")
                .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                .profile(profile).kitchen(kitchen).active(true).build());
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(user.getEmail(), RAW_PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readValue(body, LoginResponse.class).token();
    }
}
