package com.inventra.api.core.service.alert;

import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.alert.Alert;
import com.inventra.api.core.domain.alert.enums.AlertSeverity;
import com.inventra.api.core.domain.kitchen.Kitchen;
import com.inventra.api.core.domain.profile.Profile;
import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.repository.AlertRepository;
import com.inventra.api.infrastructure.repository.ProfileRepository;

import lombok.RequiredArgsConstructor;

// Alertas gerados pelos fluxos do app (pedido de entrada, requisição, produto não cadastrado). Os de
// estoque e validade nascem no banco, pelas triggers e procedures.
@Service
@RequiredArgsConstructor
public class AlertPublisher {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final AlertRepository repository;
    private final ProfileRepository profileRepository;

    // Para quem tem o perfil informado (ex.: "supervisor") na cozinha.
    public Alert notifyProfile(Kitchen kitchen, String profileCode, String type, AlertSeverity severity,
                               String message, String refKey) {
        Profile profile = profileRepository.findByAccessType(profileCode).orElse(null);
        return repository.save(base(kitchen, type, severity, message, refKey).targetProfile(profile).build());
    }

    // Só para uma pessoa.
    public Alert notifyUser(Kitchen kitchen, User recipient, String type, AlertSeverity severity, String message,
                            String refKey) {
        return repository.save(base(kitchen, type, severity, message, refKey).recipient(recipient).build());
    }

    // O que originou o alerta foi tratado: ele deixa de aparecer como não lido para todo mundo.
    public void resolve(Integer kitchenId, String type, String refKey) {
        repository.resolveByOrigin(kitchenId, type, refKey);
    }

    private static Alert.AlertBuilder base(Kitchen kitchen, String type, AlertSeverity severity, String message,
                                           String refKey) {
        return Alert.builder()
            .kitchen(kitchen)
            .type(type)
            .severity(severity)
            .message(message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message)
            .refKey(refKey)
            .read(false);
    }
}
