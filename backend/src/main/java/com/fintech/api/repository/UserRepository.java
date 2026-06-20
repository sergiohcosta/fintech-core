package com.fintech.api.repository;

import com.fintech.api.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    // JOIN FETCH do tenant: o User autenticado é carregado no SecurityFilter (um servlet
    // filter que roda ANTES da sessão Open-in-View ser aberta). Sem o fetch aqui, tenant
    // fica como proxy preso à sessão já fechada do filtro → LazyInitializationException ao
    // acessar qualquer campo do tenant em controllers/services. O fetch resolve a classe
    // inteira de bugs na origem, mantendo a associação LAZY (padrão do projeto).
    @Query("SELECT u FROM User u JOIN FETCH u.tenant WHERE u.email = :email")
    Optional<User> findByEmail(@Param("email") String email);

    // Para validar se já existe antes de tentar cadastrar
    boolean existsByEmail(String email);

    // Retorna todos os usuários do tenant, ordenados por nome — isola dados por tenant
    List<User> findAllByTenantIdOrderByNameAsc(UUID tenantId);
}