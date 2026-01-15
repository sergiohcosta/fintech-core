package com.fintech.api.repository;

import com.fintech.api.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    // Magic Method: O Spring cria o SQL automaticamente baseado no nome
    Optional<User> findByEmail(String email);

    // Para validar se já existe antes de tentar cadastrar
    boolean existsByEmail(String email);
}