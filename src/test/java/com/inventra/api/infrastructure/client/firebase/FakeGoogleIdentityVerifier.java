package com.inventra.api.infrastructure.client.firebase;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.inventra.api.core.service.auth.GoogleIdentity;
import com.inventra.api.core.service.auth.GoogleIdentityVerifier;
import com.inventra.api.infrastructure.exception.ApiProblemException;

// Substitui o Firebase nos testes (perfil "test"). O "token" descreve a identidade:
// "google|<uid>|<email>|<nome>" (e-mail verificado) ou "unverified|<uid>|<email>|<nome>"; qualquer outra
// coisa é um token inválido.
@Component
@Profile("test")
public class FakeGoogleIdentityVerifier implements GoogleIdentityVerifier {

    @Override
    public GoogleIdentity verify(String idToken) {
        String[] parts = idToken.split("\\|", -1);
        if (parts.length != 4 || !(parts[0].equals("google") || parts[0].equals("unverified"))) {
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                    "Token do Google inválido ou expirado.");
        }
        return new GoogleIdentity(parts[1], parts[2], parts[0].equals("google"), parts[3].isEmpty() ? null : parts[3]);
    }
}
