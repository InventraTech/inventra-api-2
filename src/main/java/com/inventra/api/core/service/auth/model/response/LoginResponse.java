package com.inventra.api.core.service.auth.model.response;

import com.inventra.api.core.service.user.model.response.UserResponse;

// refreshToken e refreshExpiresIn vêm nulos se o armazenamento de refresh tokens estiver fora do ar no login:
// o access token continua valendo, só não há renovação até o próximo login.
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        String refreshToken,
        Long refreshExpiresIn,
        UserResponse user
) {

    public LoginResponse(String token, String tokenType, long expiresIn, UserResponse user) {
        this(token, tokenType, expiresIn, null, null, user);
    }
}
