package com.inventra.api.infrastructure.security;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.inventra.api.core.domain.user.User;
import com.inventra.api.infrastructure.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);

    private final UserRepository userRepository;

    // Login: e-mail sem diferenciar maiúsculas. Se o banco tiver contas duplicadas só na caixa
    // (dado histórico), vale a que bate exatamente com o que foi digitado; sem desempate, nenhuma.
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        List<User> candidates = userRepository.findAllByEmailWithProfile(email);
        if (candidates.size() == 1) {
            return new UserPrincipal(candidates.get(0));
        }
        if (candidates.size() > 1) {
            log.warn("Há {} contas com o mesmo e-mail ignorando maiúsculas; usando só correspondência exata", candidates.size());
            return candidates.stream()
                    .filter(user -> user.getEmail().equals(email))
                    .findFirst()
                    .map(UserPrincipal::new)
                    .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
        }
        throw new UsernameNotFoundException("Usuário não encontrado.");
    }

    // Requisições autenticadas: o token carrega o id (único), então não depende do e-mail.
    public UserDetails loadUserById(UUID id) throws UsernameNotFoundException {
        User user = userRepository.findByIdWithProfile(id)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado."));
        return new UserPrincipal(user);
    }
}
