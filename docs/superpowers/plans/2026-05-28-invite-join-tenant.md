# Convite para Ingresso em Tenant Existente — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que um ADMIN convide usuários por email para ingressar no seu tenant via link de token single-use.

**Architecture:** Token opaco (UUID) persistido na tabela `invitations`. Admin gera o convite via `POST /invites`, recebe o link; o convidado acessa o link no frontend, valida o token e completa o cadastro via `POST /auth/accept-invite`, recebendo um JWT para auto-login.

**Tech Stack:** Java 21, Spring Boot 4, Spring Security, JPA/Hibernate, Flyway, Angular 21 (Zoneless + Signals), Angular Material 3, Vitest.

---

## Mapa de Arquivos

### Backend — criar
| Arquivo | Responsabilidade |
|---|---|
| `db/migration/V6__create_invitations.sql` | Tabela `invitations` |
| `domain/invitation/Invitation.java` | Entidade JPA do convite |
| `exception/InviteAlreadyUsedException.java` | 410 Gone — convite já usado |
| `exception/InviteExpiredException.java` | 410 Gone — convite expirado |
| `exception/BusinessConflictException.java` | 409 Conflict — email duplicado etc. |
| `dto/CreateInvitationDTO.java` | `{ email }` — entrada do admin |
| `dto/InvitationResponseDTO.java` | `{ token, link, email, expiresAt }` — resposta ao admin |
| `dto/InvitationInfoDTO.java` | `{ email, tenantName }` — resposta pública para o convidado |
| `dto/AcceptInviteDTO.java` | `{ token, name, password }` — entrada do convidado |
| `repository/InvitationRepository.java` | Queries JPA para `invitations` |
| `service/InvitationService.java` | Regras de negócio: criar, validar, aceitar |
| `controller/InvitationController.java` | `POST /invites` e `GET /invites/{token}` |

### Backend — modificar
| Arquivo | O que muda |
|---|---|
| `controller/AuthController.java` | + `POST /auth/accept-invite` + injeção de `InvitationService` |
| `exception/GlobalExceptionHandler.java` | + handlers para `InviteAlreadyUsedException`, `InviteExpiredException`, `BusinessConflictException` |
| `config/SecurityConfigurations.java` | + rotas públicas + restrição ADMIN em `POST /invites` |

### Frontend — criar
| Arquivo | Responsabilidade |
|---|---|
| `core/services/invitation.ts` | Chamadas HTTP: validar token, aceitar convite |
| `core/services/invitation.spec.ts` | Testes do InvitationService |
| `features/auth/accept-invite/accept-invite.ts` | Componente standalone de aceite de convite |
| `features/auth/accept-invite/accept-invite.html` | Template com estados: loading / erro / formulário |
| `features/auth/accept-invite/accept-invite.scss` | Estilos (reutiliza padrão do register) |
| `features/auth/accept-invite/accept-invite.spec.ts` | Testes do componente |

### Frontend — modificar
| Arquivo | O que muda |
|---|---|
| `core/services/auth.ts` | + método `setToken(token)` público |
| `app.routes.ts` | + rota lazy `/accept-invite` |

---

## Tarefa 1: Migration V6 + Entidade Invitation

**Arquivos:**
- Criar: `backend/src/main/resources/db/migration/V6__create_invitations.sql`
- Criar: `backend/src/main/java/com/fintech/api/domain/invitation/Invitation.java`

- [ ] **Passo 1: Criar a migration**

```sql
-- backend/src/main/resources/db/migration/V6__create_invitations.sql
CREATE TABLE invitations (
    id          UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    tenant_id   UUID NOT NULL,
    email       VARCHAR(255) NOT NULL,
    token       VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_invitations_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT uk_invitations_token  UNIQUE (token)
);

CREATE INDEX idx_invitations_token        ON invitations(token);
CREATE INDEX idx_invitations_tenant_email ON invitations(tenant_id, email);
```

- [ ] **Passo 2: Criar a entidade JPA**

```java
// backend/src/main/java/com/fintech/api/domain/invitation/Invitation.java
package com.fintech.api.domain.invitation;

import com.fintech.api.domain.tenant.Tenant;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "invitations")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

- [ ] **Passo 3: Verificar que o backend sobe sem erros**

```bash
cd backend && ./mvnw spring-boot:run
```
Esperado: log do Flyway mostrando `V6__create_invitations.sql` aplicado com sucesso. Encerrar com `Ctrl+C`.

- [ ] **Passo 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V6__create_invitations.sql \
        backend/src/main/java/com/fintech/api/domain/invitation/Invitation.java
git commit -m "feat(invitation): adiciona migration e entidade Invitation"
```

---

## Tarefa 2: Exceções + DTOs + Repository

**Arquivos:**
- Criar: `exception/InviteAlreadyUsedException.java`
- Criar: `exception/InviteExpiredException.java`
- Criar: `exception/BusinessConflictException.java`
- Criar: `dto/CreateInvitationDTO.java`
- Criar: `dto/InvitationResponseDTO.java`
- Criar: `dto/InvitationInfoDTO.java`
- Criar: `dto/AcceptInviteDTO.java`
- Criar: `repository/InvitationRepository.java`

- [ ] **Passo 1: Criar as exceções**

```java
// backend/src/main/java/com/fintech/api/exception/InviteAlreadyUsedException.java
package com.fintech.api.exception;

public class InviteAlreadyUsedException extends RuntimeException {
    public InviteAlreadyUsedException() {
        super("Este convite já foi utilizado");
    }
}
```

```java
// backend/src/main/java/com/fintech/api/exception/InviteExpiredException.java
package com.fintech.api.exception;

public class InviteExpiredException extends RuntimeException {
    public InviteExpiredException() {
        super("Este convite expirou");
    }
}
```

```java
// backend/src/main/java/com/fintech/api/exception/BusinessConflictException.java
package com.fintech.api.exception;

public class BusinessConflictException extends RuntimeException {
    public BusinessConflictException(String message) {
        super(message);
    }
}
```

- [ ] **Passo 2: Criar os DTOs**

```java
// backend/src/main/java/com/fintech/api/dto/CreateInvitationDTO.java
package com.fintech.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateInvitationDTO(
    @NotBlank(message = "Email é obrigatório")
    @Email(message = "Email inválido")
    String email
) {}
```

```java
// backend/src/main/java/com/fintech/api/dto/InvitationResponseDTO.java
package com.fintech.api.dto;

import java.time.LocalDateTime;

public record InvitationResponseDTO(
    String token,
    String link,
    String email,
    LocalDateTime expiresAt
) {}
```

```java
// backend/src/main/java/com/fintech/api/dto/InvitationInfoDTO.java
package com.fintech.api.dto;

public record InvitationInfoDTO(String email, String tenantName) {}
```

```java
// backend/src/main/java/com/fintech/api/dto/AcceptInviteDTO.java
package com.fintech.api.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptInviteDTO(
    @NotBlank(message = "Token é obrigatório")   String token,
    @NotBlank(message = "Nome é obrigatório")    String name,
    @NotBlank(message = "Senha é obrigatória")   String password
) {}
```

- [ ] **Passo 3: Criar o repository**

```java
// backend/src/main/java/com/fintech/api/repository/InvitationRepository.java
package com.fintech.api.repository;

import com.fintech.api.domain.invitation.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {
    Optional<Invitation> findByToken(String token);
    boolean existsByEmailAndTenantIdAndUsedFalseAndExpiresAtAfter(
            String email, UUID tenantId, LocalDateTime now);
}
```

- [ ] **Passo 4: Compilar para verificar que tudo está correto**

```bash
cd backend && ./mvnw compile -q
```
Esperado: BUILD SUCCESS sem erros.

- [ ] **Passo 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/exception/InviteAlreadyUsedException.java \
        backend/src/main/java/com/fintech/api/exception/InviteExpiredException.java \
        backend/src/main/java/com/fintech/api/exception/BusinessConflictException.java \
        backend/src/main/java/com/fintech/api/dto/CreateInvitationDTO.java \
        backend/src/main/java/com/fintech/api/dto/InvitationResponseDTO.java \
        backend/src/main/java/com/fintech/api/dto/InvitationInfoDTO.java \
        backend/src/main/java/com/fintech/api/dto/AcceptInviteDTO.java \
        backend/src/main/java/com/fintech/api/repository/InvitationRepository.java
git commit -m "feat(invitation): adiciona exceções, DTOs e repository de convites"
```

---

## Tarefa 3: InvitationService — criar convite (TDD)

**Arquivos:**
- Criar: `service/InvitationService.java`
- Criar: `src/test/java/com/fintech/api/service/InvitationServiceTest.java`

- [ ] **Passo 1: Criar o arquivo de teste com os testes de criação**

```java
// backend/src/test/java/com/fintech/api/service/InvitationServiceTest.java
package com.fintech.api.service;

import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.invitation.Invitation;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationInfoDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.exception.InviteAlreadyUsedException;
import com.fintech.api.exception.InviteExpiredException;
import com.fintech.api.repository.InvitationRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock InvitationRepository invitationRepository;
    @Mock UserRepository userRepository;
    @Mock TokenService tokenService;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks InvitationService service;

    private User admin;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Família Silva");

        admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setEmail("admin@silva.com");
        admin.setRole(UserRole.ADMIN);
        admin.setTenant(tenant);
    }

    // --- CRIAR CONVITE ---

    @Test
    @DisplayName("Cria convite e retorna token + link quando email não existe")
    void create_happyPath() {
        CreateInvitationDTO dto = new CreateInvitationDTO("novo@silva.com");
        when(userRepository.existsByEmail("novo@silva.com")).thenReturn(false);
        when(invitationRepository.existsByEmailAndTenantIdAndUsedFalseAndExpiresAtAfter(
                eq("novo@silva.com"), eq(tenant.getId()), any())).thenReturn(false);
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        InvitationResponseDTO result = service.create(dto, admin);

        assertThat(result.email()).isEqualTo("novo@silva.com");
        assertThat(result.token()).isNotBlank();
        assertThat(result.link()).contains(result.token());
        assertThat(result.expiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("Lança BusinessConflictException quando email já tem conta")
    void create_emailAlreadyExists() {
        CreateInvitationDTO dto = new CreateInvitationDTO("existente@silva.com");
        when(userRepository.existsByEmail("existente@silva.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto, admin))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage("Este email já possui uma conta");
    }

    @Test
    @DisplayName("Lança BusinessConflictException quando já existe convite pendente")
    void create_pendingInviteExists() {
        CreateInvitationDTO dto = new CreateInvitationDTO("pendente@silva.com");
        when(userRepository.existsByEmail("pendente@silva.com")).thenReturn(false);
        when(invitationRepository.existsByEmailAndTenantIdAndUsedFalseAndExpiresAtAfter(
                eq("pendente@silva.com"), eq(tenant.getId()), any())).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto, admin))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage("Já existe um convite pendente para este email");
    }
}
```

- [ ] **Passo 2: Executar os testes e confirmar que falham (InvitationService não existe)**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvitationServiceTest -q 2>&1 | tail -5
```
Esperado: ERRORS — `InvitationService` não encontrado.

- [ ] **Passo 3: Criar o InvitationService com a lógica de criar convite**

```java
// backend/src/main/java/com/fintech/api/service/InvitationService.java
package com.fintech.api.service;

import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.invitation.Invitation;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationInfoDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.exception.BusinessConflictException;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.exception.InviteAlreadyUsedException;
import com.fintech.api.exception.InviteExpiredException;
import com.fintech.api.repository.InvitationRepository;
import com.fintech.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl;

    @Transactional
    public InvitationResponseDTO create(CreateInvitationDTO dto, User admin) {
        if (userRepository.existsByEmail(dto.email())) {
            throw new BusinessConflictException("Este email já possui uma conta");
        }
        if (invitationRepository.existsByEmailAndTenantIdAndUsedFalseAndExpiresAtAfter(
                dto.email(), admin.getTenant().getId(), LocalDateTime.now())) {
            throw new BusinessConflictException("Já existe um convite pendente para este email");
        }

        Invitation invitation = new Invitation();
        invitation.setTenant(admin.getTenant());
        invitation.setEmail(dto.email());
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(LocalDateTime.now().plusDays(7));
        invitationRepository.save(invitation);

        String link = frontendUrl + "/accept-invite?token=" + invitation.getToken();
        return new InvitationResponseDTO(invitation.getToken(), link, invitation.getEmail(), invitation.getExpiresAt());
    }

    // validate() e accept() serão adicionados na Tarefa 4
}
```

- [ ] **Passo 4: Executar os testes e confirmar que passam**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvitationServiceTest#create* -q 2>&1 | tail -5
```
Esperado: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Passo 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/InvitationService.java \
        backend/src/test/java/com/fintech/api/service/InvitationServiceTest.java
git commit -m "feat(invitation): implementa InvitationService.create com testes"
```

---

## Tarefa 4: InvitationService — validar e aceitar convite (TDD)

**Arquivos:**
- Modificar: `service/InvitationService.java`
- Modificar: `src/test/java/com/fintech/api/service/InvitationServiceTest.java`

- [ ] **Passo 1: Adicionar os testes de validate e accept ao InvitationServiceTest**

Adicionar ao final da classe `InvitationServiceTest`, antes do fechamento `}`:

```java
    // --- helper compartilhado pelos testes abaixo ---

    private Invitation buildInvitation(boolean used, LocalDateTime expiresAt) {
        Invitation inv = new Invitation();
        inv.setId(UUID.randomUUID());
        inv.setTenant(tenant);
        inv.setEmail("convidado@silva.com");
        inv.setToken("valid-token");
        inv.setUsed(used);
        inv.setExpiresAt(expiresAt);
        return inv;
    }

    // --- VALIDAR TOKEN ---

    @Test
    @DisplayName("validate retorna email e tenantName para token válido")
    void validate_happyPath() {
        Invitation inv = buildInvitation(false, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findByToken("valid-token")).thenReturn(Optional.of(inv));

        InvitationInfoDTO result = service.validate("valid-token");

        assertThat(result.email()).isEqualTo("convidado@silva.com");
        assertThat(result.tenantName()).isEqualTo("Família Silva");
    }

    @Test
    @DisplayName("validate lança EntityNotFoundException para token inexistente")
    void validate_tokenNotFound() {
        when(invitationRepository.findByToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate("nope"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("Convite inválido ou inexistente");
    }

    @Test
    @DisplayName("validate lança InviteAlreadyUsedException para token já usado")
    void validate_alreadyUsed() {
        Invitation inv = buildInvitation(true, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findByToken("used-token")).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.validate("used-token"))
                .isInstanceOf(InviteAlreadyUsedException.class);
    }

    @Test
    @DisplayName("validate lança InviteExpiredException para token expirado")
    void validate_expired() {
        Invitation inv = buildInvitation(false, LocalDateTime.now().minusDays(1));
        when(invitationRepository.findByToken("expired-token")).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.validate("expired-token"))
                .isInstanceOf(InviteExpiredException.class);
    }

    // --- ACEITAR CONVITE ---

    @Test
    @DisplayName("accept cria usuário USER, marca convite como usado e retorna JWT")
    void accept_happyPath() {
        Invitation inv = buildInvitation(false, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findByToken("valid-token")).thenReturn(Optional.of(inv));
        when(userRepository.existsByEmail("convidado@silva.com")).thenReturn(false);
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tokenService.generateToken(any())).thenReturn("jwt-token");

        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João Silva", "senha123");
        String jwt = service.accept(dto);

        assertThat(jwt).isEqualTo("jwt-token");
        assertThat(inv.isUsed()).isTrue();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getEmail()).isEqualTo("convidado@silva.com");
        assertThat(saved.getTenant()).isEqualTo(tenant);
    }

    @Test
    @DisplayName("accept lança BusinessConflictException quando email já tem conta")
    void accept_emailAlreadyExists() {
        Invitation inv = buildInvitation(false, LocalDateTime.now().plusDays(1));
        when(invitationRepository.findByToken("valid-token")).thenReturn(Optional.of(inv));
        when(userRepository.existsByEmail("convidado@silva.com")).thenReturn(true);

        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João", "senha");
        assertThatThrownBy(() -> service.accept(dto))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage("Este email já possui uma conta");
    }
```

- [ ] **Passo 2: Executar os testes e confirmar que falham (métodos não existem)**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvitationServiceTest -q 2>&1 | tail -5
```
Esperado: ERRORS — `validate` e `accept` não encontrados.

- [ ] **Passo 3: Adicionar os métodos validate e accept ao InvitationService**

Adicionar ao final da classe `InvitationService`, antes do fechamento `}`:

```java
    @Transactional(readOnly = true)
    public InvitationInfoDTO validate(String token) {
        Invitation invitation = findValidInvitation(token);
        return new InvitationInfoDTO(invitation.getEmail(), invitation.getTenant().getName());
    }

    @Transactional
    public String accept(AcceptInviteDTO dto) {
        Invitation invitation = findValidInvitation(dto.token());

        if (userRepository.existsByEmail(invitation.getEmail())) {
            throw new BusinessConflictException("Este email já possui uma conta");
        }

        User user = new User();
        user.setName(dto.name());
        user.setEmail(invitation.getEmail());
        user.setPasswordHash(passwordEncoder.encode(dto.password()));
        user.setRole(UserRole.USER);
        user.setTenant(invitation.getTenant());
        userRepository.save(user);

        invitation.setUsed(true);
        invitationRepository.save(invitation);

        return tokenService.generateToken(user);
    }

    private Invitation findValidInvitation(String token) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Convite inválido ou inexistente"));
        if (invitation.isUsed()) throw new InviteAlreadyUsedException();
        if (invitation.getExpiresAt().isBefore(LocalDateTime.now())) throw new InviteExpiredException();
        return invitation;
    }
```

- [ ] **Passo 4: Executar todos os testes do service**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvitationServiceTest -q 2>&1 | tail -5
```
Esperado: `Tests run: 9, Failures: 0, Errors: 0`.

- [ ] **Passo 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/InvitationService.java \
        backend/src/test/java/com/fintech/api/service/InvitationServiceTest.java
git commit -m "feat(invitation): implementa validate e accept no InvitationService com testes"
```

---

## Tarefa 5: GlobalExceptionHandler + SecurityConfigurations

**Arquivos:**
- Modificar: `exception/GlobalExceptionHandler.java`
- Modificar: `config/SecurityConfigurations.java`

- [ ] **Passo 1: Adicionar handlers ao GlobalExceptionHandler**

Adicionar os 3 métodos abaixo, após o handler de `EntityNotFoundException` existente:

```java
    @ExceptionHandler(com.fintech.api.exception.BusinessConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(
            com.fintech.api.exception.BusinessConflictException ex) {
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(com.fintech.api.exception.InviteAlreadyUsedException.class)
    public ResponseEntity<Map<String, Object>> handleInviteAlreadyUsed(
            com.fintech.api.exception.InviteAlreadyUsedException ex) {
        return buildErrorResponse(HttpStatus.GONE, ex.getMessage(), null);
    }

    @ExceptionHandler(com.fintech.api.exception.InviteExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleInviteExpired(
            com.fintech.api.exception.InviteExpiredException ex) {
        return buildErrorResponse(HttpStatus.GONE, ex.getMessage(), null);
    }
```

- [ ] **Passo 2: Atualizar SecurityConfigurations com as novas regras de autorização**

Substituir o bloco `.authorizeHttpRequests(...)` existente por:

```java
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/accept-invite").permitAll()
                        .requestMatchers(HttpMethod.GET, "/invites/*").permitAll()
                        .requestMatchers("/openapi.yaml", "/swagger-ui.html", "/swagger-ui/**", "/webjars/**", "/v3/api-docs/**", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/invites").hasRole("ADMIN")
                        .anyRequest().authenticated())
```

- [ ] **Passo 3: Compilar**

```bash
cd backend && ./mvnw compile -q
```
Esperado: BUILD SUCCESS.

- [ ] **Passo 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/exception/GlobalExceptionHandler.java \
        backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java
git commit -m "feat(invitation): adiciona handlers de exceção e regras de segurança"
```

---

## Tarefa 6: InvitationController (TDD)

**Arquivos:**
- Criar: `controller/InvitationController.java`
- Criar: `src/test/java/com/fintech/api/controller/InvitationControllerTest.java`

- [ ] **Passo 1: Criar os testes do controller**

```java
// backend/src/test/java/com/fintech/api/controller/InvitationControllerTest.java
package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationInfoDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.exception.InviteAlreadyUsedException;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class InvitationControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean InvitationService invitationService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private User adminUser;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        adminUser = new User();
        adminUser.setEmail("admin@test.com");
        adminUser.setRole(UserRole.ADMIN);
        adminUser.setTenant(tenant);

        when(tokenService.validateToken(anyString())).thenReturn(adminUser.getEmail());
        when(userRepository.findByEmail(adminUser.getEmail())).thenReturn(Optional.of(adminUser));
    }

    @Test
    @DisplayName("POST /invites retorna 201 com token e link para ADMIN autenticado")
    void createInvite_returnsCreated() throws Exception {
        CreateInvitationDTO dto = new CreateInvitationDTO("convidado@test.com");
        InvitationResponseDTO response = new InvitationResponseDTO(
                "abc-token", "http://localhost:4200/accept-invite?token=abc-token",
                "convidado@test.com", LocalDateTime.now().plusDays(7));

        when(invitationService.create(any(), any())).thenReturn(response);

        mockMvc.perform(post("/invites")
                        .header("Authorization", "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("abc-token"))
                .andExpect(jsonPath("$.email").value("convidado@test.com"));
    }

    @Test
    @DisplayName("POST /invites retorna 403 sem autenticação")
    void createInvite_withoutAuth_returns403() throws Exception {
        CreateInvitationDTO dto = new CreateInvitationDTO("x@test.com");

        mockMvc.perform(post("/invites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /invites retorna 403 para usuário com role USER")
    void createInvite_withUserRole_returns403() throws Exception {
        adminUser.setRole(UserRole.USER);
        CreateInvitationDTO dto = new CreateInvitationDTO("x@test.com");

        mockMvc.perform(post("/invites")
                        .header("Authorization", "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(dto)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /invites/{token} retorna 200 com email e tenantName")
    void validateToken_returnsOk() throws Exception {
        InvitationInfoDTO info = new InvitationInfoDTO("convidado@test.com", "Família Silva");
        when(invitationService.validate("valid-token")).thenReturn(info);

        mockMvc.perform(get("/invites/valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("convidado@test.com"))
                .andExpect(jsonPath("$.tenantName").value("Família Silva"));
    }

    @Test
    @DisplayName("GET /invites/{token} retorna 404 para token inexistente")
    void validateToken_notFound_returns404() throws Exception {
        when(invitationService.validate("bad-token"))
                .thenThrow(new EntityNotFoundException("Convite inválido ou inexistente"));

        mockMvc.perform(get("/invites/bad-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Convite inválido ou inexistente"));
    }

    @Test
    @DisplayName("GET /invites/{token} retorna 410 para token já usado")
    void validateToken_alreadyUsed_returns410() throws Exception {
        when(invitationService.validate("used-token"))
                .thenThrow(new InviteAlreadyUsedException());

        mockMvc.perform(get("/invites/used-token"))
                .andExpect(status().isGone());
    }
}
```

- [ ] **Passo 2: Executar os testes e confirmar que falham**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvitationControllerTest -q 2>&1 | tail -5
```
Esperado: ERRORS — `InvitationController` não encontrado.

- [ ] **Passo 3: Criar o InvitationController**

```java
// backend/src/main/java/com/fintech/api/controller/InvitationController.java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.CreateInvitationDTO;
import com.fintech.api.dto.InvitationInfoDTO;
import com.fintech.api.dto.InvitationResponseDTO;
import com.fintech.api.service.InvitationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/invites")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping
    public ResponseEntity<InvitationResponseDTO> create(@RequestBody @Valid CreateInvitationDTO dto) {
        User admin = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.create(dto, admin));
    }

    @GetMapping("/{token}")
    public ResponseEntity<InvitationInfoDTO> validate(@PathVariable String token) {
        return ResponseEntity.ok(invitationService.validate(token));
    }
}
```

- [ ] **Passo 4: Executar os testes do controller**

```bash
cd backend && ./mvnw test -pl . -Dtest=InvitationControllerTest -q 2>&1 | tail -5
```
Esperado: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Passo 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/InvitationController.java \
        backend/src/test/java/com/fintech/api/controller/InvitationControllerTest.java
git commit -m "feat(invitation): implementa InvitationController com testes"
```

---

## Tarefa 7: AuthController — POST /auth/accept-invite (TDD)

**Arquivos:**
- Modificar: `controller/AuthController.java`
- Modificar: `src/test/java/com/fintech/api/controller/AuthControllerTest.java`

- [ ] **Passo 1: Adicionar o teste de accept-invite ao AuthControllerTest**

Adicionar os seguintes imports ao topo de `AuthControllerTest.java`:

```java
import com.fintech.api.dto.AcceptInviteDTO;
import com.fintech.api.service.InvitationService;
```

Adicionar `@MockitoBean` para `InvitationService`:

```java
    @MockitoBean
    private InvitationService invitationService;
```

Adicionar os testes ao final da classe:

```java
    @Test
    @DisplayName("POST /auth/accept-invite retorna 200 com token JWT")
    void shouldAcceptInviteSuccessfully() throws Exception {
        AcceptInviteDTO dto = new AcceptInviteDTO("valid-token", "João Silva", "senha123");
        when(invitationService.accept(any(AcceptInviteDTO.class))).thenReturn("jwt-result");

        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-result"));
    }

    @Test
    @DisplayName("POST /auth/accept-invite retorna 400 quando campos obrigatórios ausentes")
    void shouldFailAcceptInviteWhenMissingFields() throws Exception {
        mockMvc.perform(post("/auth/accept-invite")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Passo 2: Executar os testes e confirmar que falham**

```bash
cd backend && ./mvnw test -pl . -Dtest=AuthControllerTest -q 2>&1 | tail -5
```
Esperado: ERRORS — endpoint não existe ainda.

- [ ] **Passo 3: Atualizar AuthController**

Adicionar `private final InvitationService invitationService;` ao campo da classe (a anotação `@RequiredArgsConstructor` cuida do construtor).

Também adicionar o import de `AcceptInviteDTO` e `InvitationService`, e o novo endpoint:

```java
    @PostMapping("/accept-invite")
    public ResponseEntity<LoginResponseDTO> acceptInvite(@RequestBody @Valid AcceptInviteDTO dto) {
        String token = invitationService.accept(dto);
        return ResponseEntity.ok(new LoginResponseDTO(token));
    }
```

O arquivo final de `AuthController.java` fica assim:

```java
package com.fintech.api.controller;

import com.fintech.api.config.TokenService;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.dto.*;
import com.fintech.api.openapi.AuthApi;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.InvitationService;
import com.fintech.api.service.TenantRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final TenantRegistrationService registrationService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final InvitationService invitationService;

    @Override
    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDTO> register(@RequestBody @Valid TenantRegistrationDTO dto) {
        Tenant newTenant = registrationService.register(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponseDTO(newTenant.getId(), newTenant.getName()));
    }

    @Override
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@RequestBody @Valid LoginDTO data) {
        var user = this.userRepository.findByEmail(data.email())
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado"));

        if (passwordEncoder.matches(data.password(), user.getPasswordHash())) {
            String token = tokenService.generateToken(user);
            return ResponseEntity.ok(new LoginResponseDTO(token));
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<LoginResponseDTO> acceptInvite(@RequestBody @Valid AcceptInviteDTO dto) {
        String token = invitationService.accept(dto);
        return ResponseEntity.ok(new LoginResponseDTO(token));
    }
}
```

- [ ] **Passo 4: Executar todos os testes do backend**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -10
```
Esperado: todos os testes passam, sem falhas.

- [ ] **Passo 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/AuthController.java \
        backend/src/test/java/com/fintech/api/controller/AuthControllerTest.java
git commit -m "feat(invitation): adiciona endpoint POST /auth/accept-invite com testes"
```

---

## Tarefa 8: Frontend — AuthService.setToken + InvitationService (TDD)

**Arquivos:**
- Modificar: `frontend/src/app/core/services/auth.ts`
- Criar: `frontend/src/app/core/services/invitation.ts`
- Criar: `frontend/src/app/core/services/invitation.spec.ts`

- [ ] **Passo 1: Adicionar setToken ao AuthService**

Em `frontend/src/app/core/services/auth.ts`, adicionar após o método `saveToken()`:

```typescript
  setToken(token: string): void {
    this.saveToken(token);
    this.decodeToken();
  }
```

Também mudar o acesso de `decodeToken` de `private` para `private` (mantém) — mas agora é chamado por `setToken` que é público. Sem alteração na visibilidade.

- [ ] **Passo 2: Criar os testes do InvitationService**

```typescript
// frontend/src/app/core/services/invitation.spec.ts
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { InvitationService } from './invitation';

describe('InvitationService', () => {
  let service: InvitationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(InvitationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('validateToken chama GET /invites/{token}', () => {
    service.validateToken('abc123').subscribe(info => {
      expect(info.email).toBe('x@test.com');
      expect(info.tenantName).toBe('Família');
    });

    const req = httpMock.expectOne('/invites/abc123');
    expect(req.request.method).toBe('GET');
    req.flush({ email: 'x@test.com', tenantName: 'Família' });
  });

  it('acceptInvite chama POST /auth/accept-invite', () => {
    service.acceptInvite({ token: 'tok', name: 'João', password: '123' })
      .subscribe(r => expect(r.token).toBe('jwt'));

    const req = httpMock.expectOne('/auth/accept-invite');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'tok', name: 'João', password: '123' });
    req.flush({ token: 'jwt' });
  });
});
```

- [ ] **Passo 3: Executar os testes e confirmar que falham**

```bash
cd frontend && npm test -- --run invitation.spec 2>&1 | tail -10
```
Esperado: FAIL — `InvitationService` não encontrado.

- [ ] **Passo 4: Criar o InvitationService**

```typescript
// frontend/src/app/core/services/invitation.ts
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface InvitationInfo {
  email: string;
  tenantName: string;
}

export interface AcceptInviteRequest {
  token: string;
  name: string;
  password: string;
}

@Injectable({ providedIn: 'root' })
export class InvitationService {
  private http = inject(HttpClient);

  validateToken(token: string): Observable<InvitationInfo> {
    return this.http.get<InvitationInfo>(`/invites/${token}`);
  }

  acceptInvite(dto: AcceptInviteRequest): Observable<{ token: string }> {
    return this.http.post<{ token: string }>('/auth/accept-invite', dto);
  }
}
```

- [ ] **Passo 5: Executar os testes e confirmar que passam**

```bash
cd frontend && npm test -- --run invitation.spec 2>&1 | tail -10
```
Esperado: `2 tests passed`.

- [ ] **Passo 6: Commit**

```bash
git add frontend/src/app/core/services/auth.ts \
        frontend/src/app/core/services/invitation.ts \
        frontend/src/app/core/services/invitation.spec.ts
git commit -m "feat(invitation): adiciona InvitationService e setToken no AuthService"
```

---

## Tarefa 9: Frontend — AcceptInviteComponent (TDD)

**Arquivos:**
- Criar: `features/auth/accept-invite/accept-invite.ts`
- Criar: `features/auth/accept-invite/accept-invite.html`
- Criar: `features/auth/accept-invite/accept-invite.scss`
- Criar: `features/auth/accept-invite/accept-invite.spec.ts`

- [ ] **Passo 1: Criar os testes do componente**

```typescript
// frontend/src/app/features/auth/accept-invite/accept-invite.spec.ts
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AcceptInviteComponent } from './accept-invite';
import { InvitationService } from '../../../core/services/invitation';
import { AuthService } from '../../../core/services/auth';

const makeRoute = (token: string | null) => ({
  snapshot: { queryParamMap: { get: (k: string) => (k === 'token' ? token : null) } },
});

describe('AcceptInviteComponent', () => {
  let fixture: ComponentFixture<AcceptInviteComponent>;
  let component: AcceptInviteComponent;
  let invitationSvc: jasmine.SpyObj<InvitationService>;
  let authSvc: jasmine.SpyObj<AuthService>;

  const setupComponent = async (token: string | null, invitationResponse: any) => {
    invitationSvc = jasmine.createSpyObj('InvitationService', ['validateToken', 'acceptInvite']);
    authSvc = jasmine.createSpyObj('AuthService', ['setToken']);
    invitationSvc.validateToken.and.returnValue(invitationResponse);

    await TestBed.configureTestingModule({
      imports: [AcceptInviteComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: makeRoute(token) },
        { provide: InvitationService, useValue: invitationSvc },
        { provide: AuthService, useValue: authSvc },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AcceptInviteComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  };

  it('mostra formulário após validar token com sucesso', async () => {
    await setupComponent('tok', of({ email: 'x@test.com', tenantName: 'Família' }));
    expect(component.state()).toBe('form');
    expect(component.invitationInfo()?.email).toBe('x@test.com');
  });

  it('mostra erro quando token inválido', async () => {
    await setupComponent('bad', throwError(() => ({ error: { message: 'Convite inválido' } })));
    expect(component.state()).toBe('error');
    expect(component.errorMessage()).toBe('Convite inválido');
  });

  it('mostra erro quando nenhum token na URL', async () => {
    await setupComponent(null, of({}));
    expect(component.state()).toBe('error');
    expect(component.errorMessage()).toBe('Link de convite inválido.');
  });
});
```

- [ ] **Passo 2: Executar os testes e confirmar que falham**

```bash
cd frontend && npm test -- --run accept-invite.spec 2>&1 | tail -10
```
Esperado: FAIL — `AcceptInviteComponent` não encontrado.

- [ ] **Passo 3: Criar o componente TypeScript**

```typescript
// frontend/src/app/features/auth/accept-invite/accept-invite.ts
import { Component, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { InvitationService, InvitationInfo } from '../../../core/services/invitation';
import { AuthService } from '../../../core/services/auth';

type PageState = 'loading' | 'error' | 'form' | 'submitting';

@Component({
  selector: 'app-accept-invite',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './accept-invite.html',
  styleUrl: './accept-invite.scss',
})
export class AcceptInviteComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private fb = inject(FormBuilder);
  private invitationService = inject(InvitationService);
  private authService = inject(AuthService);

  state = signal<PageState>('loading');
  errorMessage = signal('');
  invitationInfo = signal<InvitationInfo | null>(null);

  form = this.fb.group({
    name:     ['', [Validators.required]],
    password: ['', [Validators.required, Validators.minLength(6)]],
  });

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.errorMessage.set('Link de convite inválido.');
      this.state.set('error');
      return;
    }

    this.invitationService.validateToken(token).subscribe({
      next: (info) => {
        this.invitationInfo.set(info);
        this.state.set('form');
      },
      error: (err) => {
        this.errorMessage.set(err.error?.message ?? 'Convite inválido ou expirado.');
        this.state.set('error');
      },
    });
  }

  onSubmit(): void {
    if (this.form.invalid) return;
    const token = this.route.snapshot.queryParamMap.get('token')!;

    this.state.set('submitting');
    this.invitationService.acceptInvite({
      token,
      name:     this.form.value.name!,
      password: this.form.value.password!,
    }).subscribe({
      next: (response) => {
        this.authService.setToken(response.token);
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.errorMessage.set(err.error?.message ?? 'Erro ao aceitar convite.');
        this.state.set('error');
      },
    });
  }
}
```

- [ ] **Passo 4: Criar o template HTML**

```html
<!-- frontend/src/app/features/auth/accept-invite/accept-invite.html -->
<div class="accept-invite-container">
  <mat-card class="accept-invite-card">

    @if (state() === 'loading') {
      <mat-card-content class="loading-state">
        <mat-spinner diameter="48"></mat-spinner>
        <p>Validando convite...</p>
      </mat-card-content>
    }

    @if (state() === 'error') {
      <mat-card-header>
        <mat-card-title>Convite inválido</mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <p class="error-message">{{ errorMessage() }}</p>
      </mat-card-content>
      <mat-card-actions align="end">
        <button mat-stroked-button routerLink="/login">Ir para o Login</button>
      </mat-card-actions>
    }

    @if (state() === 'form' || state() === 'submitting') {
      <mat-card-header>
        <mat-card-title>Criar sua conta</mat-card-title>
        <mat-card-subtitle>Você foi convidado para <strong>{{ invitationInfo()?.tenantName }}</strong></mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="onSubmit()">

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>E-mail</mat-label>
            <input matInput [value]="invitationInfo()?.email ?? ''" readonly>
          </mat-form-field>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Seu Nome Completo</mat-label>
            <input matInput formControlName="name">
          </mat-form-field>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Senha</mat-label>
            <input matInput type="password" formControlName="password">
          </mat-form-field>

          <mat-card-actions align="end">
            <button mat-flat-button color="primary" type="submit"
                    [disabled]="form.invalid || state() === 'submitting'">
              {{ state() === 'submitting' ? 'PROCESSANDO...' : 'CRIAR CONTA' }}
            </button>
          </mat-card-actions>
        </form>
      </mat-card-content>
    }

  </mat-card>
</div>
```

- [ ] **Passo 5: Criar o SCSS**

```scss
// frontend/src/app/features/auth/accept-invite/accept-invite.scss
.accept-invite-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  padding: 24px;
  background-color: var(--mat-sys-surface-container-lowest, #f5f5f5);
}

.accept-invite-card {
  width: 100%;
  max-width: 480px;
}

.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 32px 0;
}

.error-message {
  color: var(--mat-sys-error, #b00020);
}

.full-width {
  width: 100%;
  margin-bottom: 8px;
}
```

- [ ] **Passo 6: Executar os testes e confirmar que passam**

```bash
cd frontend && npm test -- --run accept-invite.spec 2>&1 | tail -10
```
Esperado: `3 tests passed`.

- [ ] **Passo 7: Commit**

```bash
git add frontend/src/app/features/auth/accept-invite/
git commit -m "feat(invitation): adiciona AcceptInviteComponent com testes"
```

---

## Tarefa 10: Frontend — Rota /accept-invite

**Arquivo:**
- Modificar: `frontend/src/app/app.routes.ts`

- [ ] **Passo 1: Adicionar a rota pública ao app.routes.ts**

Adicionar após a rota `register`, antes do bloco de rotas autenticadas:

```typescript
  {
    path: 'accept-invite',
    loadComponent: () =>
      import('./features/auth/accept-invite/accept-invite').then(m => m.AcceptInviteComponent)
  },
```

O arquivo `app.routes.ts` completo fica:

```typescript
import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth-guard';

export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },

  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login').then(m => m.LoginComponent)
  },
  {
    path: 'register',
    loadComponent: () => import('./features/auth/register/register').then(m => m.RegisterComponent)
  },
  {
    path: 'accept-invite',
    loadComponent: () =>
      import('./features/auth/accept-invite/accept-invite').then(m => m.AcceptInviteComponent)
  },

  {
    path: '',
    loadComponent: () => import('./components/shell/shell').then(m => m.ShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: 'dashboard',
        loadComponent: () => import('./features/dashboard/dashboard').then(m => m.DashboardComponent)
      },
      {
        path: 'accounts',
        loadComponent: () => import('./features/account/account-list/account-list').then(m => m.AccountList)
      },
      {
        path: 'accounts/new',
        loadComponent: () => import('./features/account/account-form/account-form').then(m => m.AccountForm)
      },
      {
        path: 'accounts/:id',
        loadComponent: () => import('./features/account/account-form/account-form').then(m => m.AccountForm)
      },
      {
        path: 'categories',
        loadComponent: () => import('./features/category/category-list/category-list').then(m => m.CategoryList)
      },
      {
        path: 'categories/new',
        loadComponent: () => import('./features/category/category-form/category-form').then(m => m.CategoryForm)
      },
      {
        path: 'categories/:id',
        loadComponent: () => import('./features/category/category-form/category-form').then(m => m.CategoryForm)
      },
      {
        path: 'transactions',
        loadComponent: () => import('./features/transaction/transaction-list/transaction-list').then(m => m.TransactionList)
      },
      {
        path: 'transactions/new',
        loadComponent: () => import('./features/transaction/transaction-form/transaction-form').then(m => m.TransactionForm)
      },
      {
        path: 'transactions/:id',
        loadComponent: () => import('./features/transaction/transaction-form/transaction-form').then(m => m.TransactionForm)
      },
    ]
  }
];
```

- [ ] **Passo 2: Executar a suíte completa de testes do frontend**

```bash
cd frontend && npm test -- --run 2>&1 | tail -10
```
Esperado: todos os testes passam.

- [ ] **Passo 3: Executar a suíte completa de testes do backend**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -10
```
Esperado: todos os testes passam.

- [ ] **Passo 4: Commit final**

```bash
git add frontend/src/app/app.routes.ts
git commit -m "feat(invitation): adiciona rota /accept-invite e conclui feature de convites"
```

---

## Auto-revisão do plano

### Cobertura da spec

| Requisito da spec | Tarefa |
|---|---|
| Tabela `invitations` com todos os campos | T1 |
| Entidade JPA `Invitation` | T1 |
| `InviteAlreadyUsedException` → 410 | T2 + T5 |
| `InviteExpiredException` → 410 | T2 + T5 |
| `BusinessConflictException` → 409 | T2 + T5 |
| Todos os DTOs | T2 |
| `InvitationRepository` com query de convite pendente | T2 |
| `InvitationService.create` com validações | T3 |
| `InvitationService.validate` | T4 |
| `InvitationService.accept` | T4 |
| Rotas públicas + restrição ADMIN no Spring Security | T5 |
| `POST /invites` → 201 | T6 |
| `GET /invites/{token}` → 200/404/410 | T6 |
| `POST /auth/accept-invite` → 200 | T7 |
| `AuthService.setToken()` | T8 |
| `InvitationService` frontend | T8 |
| `AcceptInviteComponent` (loading/error/form/submitting) | T9 |
| Rota lazy `/accept-invite` | T10 |

Sem gaps identificados.
