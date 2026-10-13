package com.inventra.api.core.service.auth;

// Porta de validação do ID token do Firebase. Em produção é o Firebase Admin SDK
// (FirebaseIdentityVerifier); os testes usam uma implementação falsa.
public interface GoogleIdentityVerifier {

    // Confere assinatura, expiração e projeto do token. Token inválido lança ApiProblemException (401).
    GoogleIdentity verify(String idToken);
}
