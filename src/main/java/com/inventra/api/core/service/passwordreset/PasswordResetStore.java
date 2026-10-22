package com.inventra.api.core.service.passwordreset;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

// Porta de armazenamento da redefinição de senha. Em produção é o Redis (RedisPasswordResetStore, contrato em
// inventra-database-2: redis/commands/auth/password-reset.redis); os testes usam uma implementação em memória.
// Código, token e e-mail chegam aqui já como hash.
public interface PasswordResetStore {

    // Conta mais um pedido para o e-mail na janela e devolve o total da janela.
    long countRequest(String emailHash, Duration window);

    // Substitui o código pendente do usuário (zera as tentativas).
    void saveCode(UUID userId, String codeHash, Duration ttl);

    // Confere o código e conta a tentativa, de forma atômica.
    CodeCheck checkCode(UUID userId, String codeHash, int maxAttempts);

    void deleteCode(UUID userId);

    void saveToken(String tokenHash, ResetTokenData data, Duration ttl);

    // Lê e apaga o token de uma vez: cada token vale uma vez só.
    Optional<ResetTokenData> consumeToken(String tokenHash);

    enum CodeCheck {
        // bateu: o código foi consumido
        MATCH,
        // não bateu e ainda há tentativas
        MISMATCH,
        // não bateu e acabaram as tentativas: o código foi apagado
        EXHAUSTED,
        // não há código pendente (expirou, foi usado ou nunca existiu)
        MISSING
    }
}
