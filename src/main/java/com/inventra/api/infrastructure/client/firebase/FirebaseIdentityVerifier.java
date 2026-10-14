package com.inventra.api.infrastructure.client.firebase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.inventra.api.core.service.auth.GoogleIdentity;
import com.inventra.api.core.service.auth.GoogleIdentityVerifier;
import com.inventra.api.infrastructure.exception.ApiProblemException;

// Valida o ID token com o Firebase Admin SDK: assinatura do Google, expiração e projeto (aud).
// A chave da conta de serviço vem de FIREBASE_CREDENTIALS_JSON (o JSON puro ou em base64) e nunca vai
// para o repositório. Sem ela a API sobe normalmente e só o login com Google responde 503.
@Component
@Profile("!test")
public class FirebaseIdentityVerifier implements GoogleIdentityVerifier {

    private static final Logger log = LoggerFactory.getLogger(FirebaseIdentityVerifier.class);
    private static final String APP_NAME = "inventra";

    private final String credentialsJson;
    private FirebaseAuth firebaseAuth;

    public FirebaseIdentityVerifier(@Value("${firebase.credentials-json:}") String credentialsJson) {
        this.credentialsJson = credentialsJson == null ? "" : credentialsJson.trim();
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        FirebaseToken token;
        try {
            token = auth().verifyIdToken(idToken);
        } catch (FirebaseAuthException | IllegalArgumentException ex) {
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                    "Token do Google inválido ou expirado.");
        }
        return new GoogleIdentity(token.getUid(), token.getEmail(), token.isEmailVerified(), token.getName());
    }

    // O FirebaseApp é criado uma vez, no primeiro login com Google.
    private synchronized FirebaseAuth auth() {
        if (firebaseAuth != null) {
            return firebaseAuth;
        }
        if (credentialsJson.isEmpty()) {
            throw unavailable();
        }
        try {
            byte[] json = credentialsJson.startsWith("{")
                    ? credentialsJson.getBytes(StandardCharsets.UTF_8)
                    : Base64.getDecoder().decode(credentialsJson);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setHttpTransport(new NetHttpTransport())
                    .setCredentials(GoogleCredentials.fromStream(new ByteArrayInputStream(json)))
                    .build();
            firebaseAuth = FirebaseAuth.getInstance(FirebaseApp.initializeApp(options, APP_NAME));
            return firebaseAuth;
        } catch (IOException | IllegalArgumentException ex) {
            // não registra a exceção inteira: a mensagem pode conter trechos da credencial
            log.error("FIREBASE_CREDENTIALS_JSON inválida: {}", ex.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private static ApiProblemException unavailable() {
        return new ApiProblemException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_LOGIN_UNAVAILABLE",
                "Login com Google não está configurado neste ambiente.");
    }
}
