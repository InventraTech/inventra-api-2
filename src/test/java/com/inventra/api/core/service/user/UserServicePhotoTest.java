package com.inventra.api.core.service.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.core.service.image.ImageStorage;
import com.inventra.api.core.service.image.ImageValidator;
import com.inventra.api.core.service.image.StoredImage;
import com.inventra.api.infrastructure.repository.KitchenRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;
import com.inventra.api.infrastructure.repository.UserRepository;
import com.inventra.api.infrastructure.security.KitchenAccessGuard;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServicePhotoTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final String CLOUDINARY_URL =
            "https://res.cloudinary.com/demo/image/upload/v2/inventra/users/foto.jpg";
    private static final String GOOGLE_URL = "https://lh3.googleusercontent.com/a/foto=s96-c";

    @Mock private UserRepository repository;
    @Mock private KitchenRepository kitchenRepository;
    @Mock private ProfileRepository profileRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private KitchenAccessGuard accessGuard;
    @Mock private ImageStorage imageStorage;
    @Mock private ImageValidator imageValidator;

    @InjectMocks private UserService service;

    private User current;

    @BeforeEach
    void setUp() {
        Profile profile = Profile.builder().id(2).accessType("estoquista").build();
        current = User.builder().id(UUID.randomUUID()).name("Ana").email("ana@inventra.com")
                .profile(profile).active(true).build();
        when(accessGuard.currentUser()).thenReturn(current);
        when(repository.findById(current.getId())).thenReturn(Optional.of(current));
        when(repository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(profileRepository.findById(2)).thenReturn(Optional.of(profile));
    }

    @Test
    void updateCurrentPhotoUploadsToTheUserFolderCroppedOnTheFace() {
        String publicId = "inventra/users/" + current.getId();
        when(imageStorage.upload(JPEG, publicId, "c_fill,g_face,w_400,h_400"))
                .thenReturn(new StoredImage(CLOUDINARY_URL, publicId));

        User updated = service.updateCurrentPhoto(JPEG);

        assertEquals(CLOUDINARY_URL, updated.getPhotoUrl());
        verify(imageValidator).validate(JPEG);
    }

    @Test
    void updateCurrentPhotoRejectsInvalidImageWithoutUploading() {
        doThrow(new IllegalArgumentException("Formato de imagem não suportado."))
                .when(imageValidator).validate(any());

        assertThrows(IllegalArgumentException.class, () -> service.updateCurrentPhoto(new byte[]{1, 2, 3}));
        verify(imageStorage, never()).upload(any(), anyString(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void removeCurrentPhotoDeletesTheUploadedFile() {
        current.setPhotoUrl(CLOUDINARY_URL);

        User updated = service.removeCurrentPhoto();

        assertNull(updated.getPhotoUrl());
        verify(imageStorage).delete("inventra/users/" + current.getId());
    }

    @Test
    void removeCurrentPhotoOnlyClearsTheGooglePicture() {
        current.setPhotoUrl(GOOGLE_URL);

        User updated = service.removeCurrentPhoto();

        assertNull(updated.getPhotoUrl());
        verify(imageStorage, never()).delete(anyString());
    }

    @Test
    void removeCurrentPhotoWithoutPhotoDoesNothing() {
        User updated = service.removeCurrentPhoto();

        assertNull(updated.getPhotoUrl());
        verify(imageStorage, never()).delete(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void registerWithGoogleKeepsTheGooglePicture() {
        User created = service.registerWithGoogle("Lia", "lia@gmail.com", "uid-1", GOOGLE_URL, 2);

        assertEquals(GOOGLE_URL, created.getPhotoUrl());
    }

    @Test
    void registerWithGoogleIgnoresPictureLongerThanTheColumn() {
        String tooLong = "https://lh3.googleusercontent.com/" + "a".repeat(255);

        User created = service.registerWithGoogle("Lia", "lia@gmail.com", "uid-1", tooLong, 2);

        assertNull(created.getPhotoUrl());
    }
}
