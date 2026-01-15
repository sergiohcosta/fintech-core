package com.fintech.api.repository;

import com.fintech.api.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    // O Spring é inteligente: ele lê o nome do método e cria o SQL (Magic Method)
    // SELECT * FROM users WHERE email = ?
    Optional<User> findByEmail(String email);
}