package com.inventra.api.infrastructure.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.user.UserUseCase;
import com.inventra.api.infrastructure.exception.ImageStorageException;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerPhotoTest {

    private static final String PHOTO_URL =
            "https://res.cloudinary.com/demo/image/upload/v1/inventra/users/foto.jpg";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserUseCase useCase;

    private static MockMultipartFile jpegPart() {
        return new MockMultipartFile("file", "foto.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
    }

    private static User user(String photoUrl) {
        Profile profile = Profile.builder().id(2).accessType("estoquista").build();
        return User.builder().id(UUID.randomUUID()).name("Ana").email("ana@inventra.com")
                .profile(profile).active(true).photoUrl(photoUrl).build();
    }

    @Test
    void updateMyPhotoReturnsUserWithNewUrl() throws Exception {
        when(useCase.updateCurrentPhoto(any())).thenReturn(user(PHOTO_URL));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/users/me/photo").file(jpegPart()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").value(PHOTO_URL));
    }

    @Test
    void updateMyPhotoReturns400WhenImageIsInvalid() throws Exception {
        when(useCase.updateCurrentPhoto(any()))
                .thenThrow(new IllegalArgumentException("Formato de imagem não suportado."));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/users/me/photo").file(jpegPart()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMyPhotoReturns502WhenStorageFails() throws Exception {
        when(useCase.updateCurrentPhoto(any()))
                .thenThrow(new ImageStorageException("Falha no Cloudinary", new IOException()));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/users/me/photo").file(jpegPart()))
                .andExpect(status().isBadGateway());
    }

    @Test
    void updateMyPhotoReturns400WhenFilePartIsMissing() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/users/me/photo"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeMyPhotoReturnsUserWithoutUrl() throws Exception {
        when(useCase.removeCurrentPhoto()).thenReturn(user(null));

        mockMvc.perform(delete("/api/users/me/photo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").doesNotExist());
    }
}
