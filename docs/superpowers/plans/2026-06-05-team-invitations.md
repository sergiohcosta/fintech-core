# Team Invitations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar a página `/team` com listagem de membros e gerenciamento de convites do tenant, permitindo ao admin enviar convites por link.

**Architecture:** Backend ganha dois novos endpoints (`GET /invites`, `DELETE /invites/{id}`) e um novo controller (`GET /api/members`). Frontend ganha a feature `team/` com `TeamComponent` + `InviteDialogComponent`, protegidos por role ADMIN para ações de escrita. JWT precisa incluir a role real do usuário (bug fix pré-requisito).

**Tech Stack:** Java 21 / Spring Boot 4 / JUnit 5 + Mockito (backend); Angular 21 Zoneless / Signals / Angular Material / Vitest (frontend).

---

## Mapa de arquivos

### Criados
| Arquivo | Responsabilidade |
|---------|-----------------|
| `backend/.../domain/enums/InvitationStatus.java` | Enum PENDING / ACCEPTED / EXPIRED |
| `backend/.../dto/InvitationSummaryDTO.java` | DTO de listagem de convites |
| `backend/.../dto/MemberDTO.java` | DTO de listagem de membros |
| `backend/.../service/MembersService.java` | Lista usuários do tenant |
| `backend/.../controller/MembersController.java` | GET /api/members |
| `backend/.../service/MembersServiceTest.java` | Testes de MembersService |
| `backend/.../controller/MembersControllerTest.java` | Testes de MembersController |
| `frontend/.../core/services/members.ts` | MembersService Angular |
| `frontend/.../core/services/members.spec.ts` | Testes de MembersService |
| `frontend/.../features/team/team/team.ts` | TeamComponent |
| `frontend/.../features/team/team/team.html` | Template de TeamComponent |
| `frontend/.../features/team/team/team.scss` | Estilos de TeamComponent |
| `frontend/.../features/team/team/team.spec.ts` | Testes de TeamComponent |
| `frontend/.../features/team/invite-dialog/invite-dialog.ts` | InviteDialogComponent |
| `frontend/.../features/team/invite-dialog/invite-dialog.html` | Template do dialog |
| `frontend/.../features/team/invite-dialog/invite-dialog.spec.ts` | Testes do dialog |

### Modificados
| Arquivo | Mudança |
|---------|---------|
| `backend/.../config/TokenService.java` | Emite role real no claim JWT |
| `backend/.../config/SecurityConfigurations.java` | Adiciona regras para GET/DELETE /invites e GET /api/members |
| `backend/.../repository/InvitationRepository.java` | `findAllByTenantIdOrderByCreatedAtDesc` |
| `backend/.../repository/UserRepository.java` | `findAllByTenantIdOrderByNameAsc` |
| `backend/.../service/InvitationService.java` | Métodos `list()` e `revoke()` |
| `backend/.../controller/InvitationController.java` | Handlers GET e DELETE |
| `backend/.../service/InvitationServiceTest.java` | Testes dos novos métodos |
| `backend/.../controller/InvitationControllerTest.java` | Testes dos novos handlers |
| `frontend/.../core/services/auth.ts` | Adiciona `role` ao TokenPayload e `isAdmin` signal |
| `frontend/.../core/services/invitation.ts` | Adiciona interfaces e métodos `create`, `list`, `revoke` |
| `frontend/.../core/services/invitation.spec.ts` | Testes dos novos métodos |
| `frontend/.../app.routes.ts` | Rota `/team` |
| `frontend/.../components/shell/shell.ts` | Item "Equipe" no nav |

---

## Task 1: Fix JWT role claim + isAdmin no AuthService

**Contexto:** `TokenService.generateToken()` hardcoda `"ROLE_USER"` no JWT. O backend funciona porque o `SecurityFilter` relê o role do banco — mas o frontend não tem como distinguir ADMIN de USER só pelo token. Correção obrigatória antes de qualquer UI baseada em role.

**Arquivos:**
- Modify: `backend/src/main/java/com/fintech/api/config/TokenService.java:27`
- Modify: `frontend/src/app/core/services/auth.ts`

- [ ] **Step 1: Corrigir TokenService para emitir role real**

Em `backend/src/main/java/com/fintech/api/config/TokenService.java`, substituir a linha 27:
```java
// antes:
.withClaim("role", "ROLE_USER")
// depois:
.withClaim("role", user.getRole().name())
```
O `user.getRole().name()` retorna `"ADMIN"` ou `"USER"` — a string exata do enum `UserRole`.

- [ ] **Step 2: Adicionar `role` ao TokenPayload e `isAdmin` ao AuthService**

Em `frontend/src/app/core/services/auth.ts`, aplicar estas mudanças:

```typescript
// Adicionar `role` à interface (após `tenant_id`):
export interface TokenPayload {
  sub: string;
  name: string;
  tenant_id: string;
  role: string;      // "ADMIN" | "USER"
  exp: number;
}

// Adicionar signal `isAdmin` como propriedade de classe (após `currentUser`):
isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');
```

O `computed()` já está importado no topo do arquivo.

- [ ] **Step 3: Verificar que os testes existentes ainda passam**

```bash
cd backend && ./mvnw test -pl . -Dtest="InvitationServiceTest,InvitationControllerTest" -q
cd frontend && npm test -- --run
```

Expected: todos PASS (sem regressão).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/config/TokenService.java \
        frontend/src/app/core/services/auth.ts
git commit -m "fix: JWT emite role real do usuário e frontend expõe isAdmin signal"
```

---

## Task 2: Enum InvitationStatus + DTOs de listagem

**Contexto:** O status do convite é calculado em runtime (não armazenado no banco). O enum centraliza essa semântica. Os DTOs são records imutáveis — padrão do projeto.

**Arquivos:**
- Create: `backend/src/main/java/com/fintech/api/domain/enums/InvitationStatus.java`
- Create: `backend/src/main/java/com/fintech/api/dto/InvitationSummaryDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/MemberDTO.java`

- [ ] **Step 1: Criar enum InvitationStatus**

Criar `backend/src/main/java/com/fintech/api/domain/enums/InvitationStatus.java`:
```java
package com.fintech.api.domain.enums;

public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    EXPIRED
}
```

- [ ] **Step 2: Criar InvitationSummaryDTO**

Criar `backend/src/main/java/com/fintech/api/dto/InvitationSummaryDTO.java`:
```java
package com.fintech.api.dto;

import com.fintech.api.domain.enums.InvitationStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record InvitationSummaryDTO(
    UUID id,
    String email,
    InvitationStatus status,
    LocalDateTime createdAt,
    LocalDateTime expiresAt,
    String link
) {}
```

- [ ] **Step 3: Criar MemberDTO**

Criar `backend/src/main/java/com/fintech/api/dto/MemberDTO.java`:
```java
package com.fintech.api.dto;

import com.fintech.api.domain.enums.UserRole;
import java.util.UUID;

public record MemberDTO(
    UUID id,
    String name,
    String email,
    UserRole role
) {}
```

- [ ] **Step 4: Compilar para confirmar que não há erro**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/enums/InvitationStatus.java \
        backend/src/main/java/com/fintech/api/dto/InvitationSummaryDTO.java \
        backend/src/main/java/com/fintech/api/dto/MemberDTO.java
git commit -m "feat: adiciona enum InvitationStatus e DTOs de listagem de convites e membros"
```

---

## Task 3: InvitationService.list() e revoke()

**Contexto:** `list()` retorna todos os convites do tenant com status calculado em memória. `revoke()` remove o registro físico — convites aceitos (`used=true`) não podem ser revogados (409); convites de outro tenant retornam 404 (nunca vazar existência).

**Arquivos:**
- Modify: `backend/src/main/java/com/fintech/api/repository/InvitationRepository.java`
- Modify: `backend/src/main/java/com/fintech/api/service/InvitationService.java`
- Modify: `backend/src/test/java/com/fintech/api/service/InvitationServiceTest.java`

- [ ] **Step 1: Escrever testes que falham para list() e revoke()**

Adicionar ao final de `InvitationServiceTest.java` (antes do último `}`):

```java
// --- LISTAR CONVITES ---

@Test
@DisplayName("list retorna convites do tenant com status calculado")
void list_returnsInvitationsWithStatus() {
    Invitation pending = buildInvitation(false, LocalDateTime.now().plusDays(3));
    Invitation accepted = buildInvitation(true, LocalDateTime.now().plusDays(1));
    Invitation expired = buildInvitation(false, LocalDateTime.now().minusDays(1));
    ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:4200");

    when(invitationRepository.findAllByTenantIdOrderByCreatedAtDesc(tenant.getId()))
            .thenReturn(List.of(pending, accepted, expired));

    List<InvitationSummaryDTO> result = service.list(admin);

    assertThat(result).hasSize(3);
    assertThat(result.get(0).status()).isEqualTo(InvitationStatus.PENDING);
    assertThat(result.get(0).link()).isNotNull();
    assertThat(result.get(1).status()).isEqualTo(InvitationStatus.ACCEPTED);
    assertThat(result.get(1).link()).isNull();
    assertThat(result.get(2).status()).isEqualTo(InvitationStatus.EXPIRED);
    assertThat(result.get(2).link()).isNull();
}

@Test
@DisplayName("list retorna apenas convites do tenant do admin")
void list_filtersOnlyAdminTenant() {
    when(invitationRepository.findAllByTenantIdOrderByCreatedAtDesc(tenant.getId()))
            .thenReturn(List.of());

    List<InvitationSummaryDTO> result = service.list(admin);

    assertThat(result).isEmpty();
    verify(invitationRepository).findAllByTenantIdOrderByCreatedAtDesc(tenant.getId());
}

// --- REVOGAR CONVITE ---

@Test
@DisplayName("revoke exclui convite pendente")
void revoke_deletesPendingInvitation() {
    Invitation pending = buildInvitation(false, LocalDateTime.now().plusDays(3));
    when(invitationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

    service.revoke(pending.getId(), admin);

    verify(invitationRepository).delete(pending);
}

@Test
@DisplayName("revoke lança BusinessConflictException para convite aceito")
void revoke_throwsConflictForAcceptedInvitation() {
    Invitation accepted = buildInvitation(true, LocalDateTime.now().plusDays(1));
    when(invitationRepository.findById(accepted.getId())).thenReturn(Optional.of(accepted));

    assertThatThrownBy(() -> service.revoke(accepted.getId(), admin))
            .isInstanceOf(BusinessConflictException.class)
            .hasMessage("Convite já aceito não pode ser revogado");
}

@Test
@DisplayName("revoke lança EntityNotFoundException para convite de outro tenant")
void revoke_throwsNotFoundForDifferentTenant() {
    Tenant other = new Tenant();
    other.setId(UUID.randomUUID());
    Invitation foreign = buildInvitation(false, LocalDateTime.now().plusDays(3));
    foreign.setTenant(other);
    when(invitationRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() -> service.revoke(foreign.getId(), admin))
            .isInstanceOf(EntityNotFoundException.class);
}
```

Adicionar imports necessários ao topo do arquivo:
```java
import com.fintech.api.domain.enums.InvitationStatus;
import com.fintech.api.dto.InvitationSummaryDTO;
import java.util.List;
```

- [ ] **Step 2: Rodar testes para confirmar que falham**

```bash
cd backend && ./mvnw test -Dtest="InvitationServiceTest" -q
```

Expected: FAIL — método `list` e `revoke` não existem ainda.

- [ ] **Step 3: Adicionar query ao InvitationRepository**

Em `backend/src/main/java/com/fintech/api/repository/InvitationRepository.java`, adicionar método:

```java
import java.util.List;

List<Invitation> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
```

- [ ] **Step 4: Implementar list() e revoke() no InvitationService**

Adicionar ao `InvitationService.java`:

```java
// Adicionar import no topo:
import com.fintech.api.domain.enums.InvitationStatus;
import com.fintech.api.dto.InvitationSummaryDTO;
import java.util.List;

// Adicionar métodos na classe:

@Transactional(readOnly = true)
public List<InvitationSummaryDTO> list(User admin) {
    return invitationRepository
            .findAllByTenantIdOrderByCreatedAtDesc(admin.getTenant().getId())
            .stream()
            .map(this::toSummary)
            .toList();
}

@Transactional
public void revoke(UUID id, User admin) {
    Invitation invitation = invitationRepository.findById(id)
            .filter(inv -> inv.getTenant().getId().equals(admin.getTenant().getId()))
            .orElseThrow(() -> new EntityNotFoundException("Convite não encontrado"));
    if (invitation.isUsed()) {
        throw new BusinessConflictException("Convite já aceito não pode ser revogado");
    }
    invitationRepository.delete(invitation);
}

private InvitationSummaryDTO toSummary(Invitation inv) {
    InvitationStatus status;
    if (inv.isUsed()) {
        status = InvitationStatus.ACCEPTED;
    } else if (inv.getExpiresAt().isBefore(LocalDateTime.now())) {
        status = InvitationStatus.EXPIRED;
    } else {
        status = InvitationStatus.PENDING;
    }
    String link = status == InvitationStatus.PENDING
            ? frontendUrl + "/accept-invite?token=" + inv.getToken()
            : null;
    return new InvitationSummaryDTO(
            inv.getId(), inv.getEmail(), status,
            inv.getCreatedAt(), inv.getExpiresAt(), link);
}
```

Adicionar `import java.util.UUID;` se não existir (cheque os imports existentes).

- [ ] **Step 5: Rodar testes para confirmar que passam**

```bash
cd backend && ./mvnw test -Dtest="InvitationServiceTest" -q
```

Expected: BUILD SUCCESS — todos os testes PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/InvitationRepository.java \
        backend/src/main/java/com/fintech/api/service/InvitationService.java \
        backend/src/test/java/com/fintech/api/service/InvitationServiceTest.java
git commit -m "feat: InvitationService implementa list() e revoke() com cálculo de status"
```

---

## Task 4: InvitationController — GET /invites + DELETE /invites/{id} + SecurityConfig

**Contexto:** Dois novos handlers no controller existente. A config de segurança já tem `GET /invites/*` público (token validation) e `POST /invites` ADMIN — precisamos adicionar `GET /invites` e `DELETE /invites/*` como ADMIN. O `GET /invites` sem segmento extra não conflita com o wildcard `/*`.

**Arquivos:**
- Modify: `backend/src/main/java/com/fintech/api/controller/InvitationController.java`
- Modify: `backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java`
- Modify: `backend/src/test/java/com/fintech/api/controller/InvitationControllerTest.java`

- [ ] **Step 1: Escrever testes que falham para os novos endpoints**

Adicionar ao final de `InvitationControllerTest.java` (antes do último `}`):

```java
import com.fintech.api.domain.enums.InvitationStatus;
import com.fintech.api.dto.InvitationSummaryDTO;
// (adicionar aos imports no topo se necessário)
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import java.util.List;
import java.util.UUID;

// --- LISTAR CONVITES ---

@Test
@DisplayName("GET /invites retorna 200 com lista para ADMIN autenticado")
void listInvites_returnsOk() throws Exception {
    UUID id = UUID.randomUUID();
    InvitationSummaryDTO summary = new InvitationSummaryDTO(
            id, "x@test.com", InvitationStatus.PENDING,
            LocalDateTime.now().minusDays(1),
            LocalDateTime.now().plusDays(6),
            "http://localhost:4200/accept-invite?token=tok");

    when(invitationService.list(any())).thenReturn(List.of(summary));

    mockMvc.perform(get("/invites")
                    .header("Authorization", "Bearer admin-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].email").value("x@test.com"))
            .andExpect(jsonPath("$[0].status").value("PENDING"));
}

@Test
@DisplayName("GET /invites retorna 403 sem autenticação")
void listInvites_withoutAuth_returns403() throws Exception {
    mockMvc.perform(get("/invites"))
            .andExpect(status().isForbidden());
}

@Test
@DisplayName("GET /invites retorna 403 para role USER")
void listInvites_withUserRole_returns403() throws Exception {
    adminUser.setRole(UserRole.USER);

    mockMvc.perform(get("/invites")
                    .header("Authorization", "Bearer user-token"))
            .andExpect(status().isForbidden());
}

// --- REVOGAR CONVITE ---

@Test
@DisplayName("DELETE /invites/{id} retorna 204 para ADMIN")
void revokeInvite_returnsNoContent() throws Exception {
    UUID id = UUID.randomUUID();
    doNothing().when(invitationService).revoke(eq(id), any());

    mockMvc.perform(delete("/invites/" + id)
                    .header("Authorization", "Bearer admin-token"))
            .andExpect(status().isNoContent());
}

@Test
@DisplayName("DELETE /invites/{id} retorna 403 para role USER")
void revokeInvite_withUserRole_returns403() throws Exception {
    adminUser.setRole(UserRole.USER);

    mockMvc.perform(delete("/invites/" + UUID.randomUUID())
                    .header("Authorization", "Bearer user-token"))
            .andExpect(status().isForbidden());
}
```

Adicionar no topo do arquivo, se necessário:
```java
import static org.mockito.Mockito.doNothing;
import static org.mockito.ArgumentMatchers.eq;
```

- [ ] **Step 2: Rodar testes para confirmar que falham**

```bash
cd backend && ./mvnw test -Dtest="InvitationControllerTest" -q
```

Expected: FAIL — endpoints não existem ainda.

- [ ] **Step 3: Adicionar handlers ao InvitationController**

Adicionar em `InvitationController.java`:

```java
// Imports a adicionar:
import com.fintech.api.dto.InvitationSummaryDTO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import java.util.UUID;

// Métodos a adicionar na classe:

@GetMapping
public ResponseEntity<List<InvitationSummaryDTO>> list() {
    User admin = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return ResponseEntity.ok(invitationService.list(admin));
}

@DeleteMapping("/{id}")
public ResponseEntity<Void> revoke(@PathVariable UUID id) {
    User admin = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    invitationService.revoke(id, admin);
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 4: Atualizar SecurityConfigurations**

Em `SecurityConfigurations.java`, adicionar **após** a linha `.requestMatchers(HttpMethod.POST, "/invites").hasRole("ADMIN")`:

```java
.requestMatchers(HttpMethod.GET, "/invites").hasRole("ADMIN")
.requestMatchers(HttpMethod.DELETE, "/invites/*").hasRole("ADMIN")
```

- [ ] **Step 5: Rodar testes para confirmar que passam**

```bash
cd backend && ./mvnw test -Dtest="InvitationControllerTest" -q
```

Expected: BUILD SUCCESS — todos PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/InvitationController.java \
        backend/src/main/java/com/fintech/api/config/SecurityConfigurations.java \
        backend/src/test/java/com/fintech/api/controller/InvitationControllerTest.java
git commit -m "feat: InvitationController expõe GET /invites e DELETE /invites/{id} para ADMIN"
```

---

## Task 5: MembersService + MembersController

**Contexto:** Endpoint simples que lista usuários do tenant do usuário autenticado. Segue o mesmo padrão dos outros controllers: principal extraído do `SecurityContextHolder`, query escopada pelo tenant.

**Arquivos:**
- Modify: `backend/src/main/java/com/fintech/api/repository/UserRepository.java`
- Create: `backend/src/main/java/com/fintech/api/service/MembersService.java`
- Create: `backend/src/main/java/com/fintech/api/controller/MembersController.java`
- Create: `backend/src/test/java/com/fintech/api/service/MembersServiceTest.java`
- Create: `backend/src/test/java/com/fintech/api/controller/MembersControllerTest.java`

- [ ] **Step 1: Escrever teste para MembersService**

Criar `backend/src/test/java/com/fintech/api/service/MembersServiceTest.java`:

```java
package com.fintech.api.service;

import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.MemberDTO;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MembersServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks MembersService service;

    private Tenant tenant;
    private User currentUser;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Família Silva");

        currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        currentUser.setName("Admin");
        currentUser.setEmail("admin@silva.com");
        currentUser.setRole(UserRole.ADMIN);
        currentUser.setTenant(tenant);
    }

    @Test
    @DisplayName("list retorna membros ordenados por nome do tenant")
    void list_returnsMembersForTenant() {
        User member = new User();
        member.setId(UUID.randomUUID());
        member.setName("Maria");
        member.setEmail("maria@silva.com");
        member.setRole(UserRole.USER);
        member.setTenant(tenant);

        when(userRepository.findAllByTenantIdOrderByNameAsc(tenant.getId()))
                .thenReturn(List.of(currentUser, member));

        List<MemberDTO> result = service.list(currentUser);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("Admin");
        assertThat(result.get(1).name()).isEqualTo("Maria");
        assertThat(result.get(1).role()).isEqualTo(UserRole.USER);
    }

    @Test
    @DisplayName("list consulta apenas pelo tenant do usuário autenticado")
    void list_queriesByTenantId() {
        when(userRepository.findAllByTenantIdOrderByNameAsc(tenant.getId()))
                .thenReturn(List.of());

        service.list(currentUser);

        verify(userRepository).findAllByTenantIdOrderByNameAsc(tenant.getId());
    }
}
```

- [ ] **Step 2: Rodar teste para confirmar que falha**

```bash
cd backend && ./mvnw test -Dtest="MembersServiceTest" -q
```

Expected: FAIL — `MembersService` não existe.

- [ ] **Step 3: Adicionar query ao UserRepository**

Em `UserRepository.java`, adicionar:

```java
import java.util.List;

List<User> findAllByTenantIdOrderByNameAsc(UUID tenantId);
```

- [ ] **Step 4: Criar MembersService**

Criar `backend/src/main/java/com/fintech/api/service/MembersService.java`:

```java
package com.fintech.api.service;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.MemberDTO;
import com.fintech.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MembersService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<MemberDTO> list(User currentUser) {
        return userRepository
                .findAllByTenantIdOrderByNameAsc(currentUser.getTenant().getId())
                .stream()
                .map(u -> new MemberDTO(u.getId(), u.getName(), u.getEmail(), u.getRole()))
                .toList();
    }
}
```

- [ ] **Step 5: Rodar teste para confirmar que passa**

```bash
cd backend && ./mvnw test -Dtest="MembersServiceTest" -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Criar MembersController com teste**

Criar `backend/src/test/java/com/fintech/api/controller/MembersControllerTest.java`:

```java
package com.fintech.api.controller;

import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.MemberDTO;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.MembersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class MembersControllerTest {

    private MockMvc mockMvc;

    @Autowired WebApplicationContext context;
    @MockitoBean MembersService membersService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean TokenService tokenService;

    private User authenticatedUser;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        authenticatedUser = new User();
        authenticatedUser.setEmail("admin@test.com");
        authenticatedUser.setRole(UserRole.ADMIN);
        authenticatedUser.setTenant(tenant);

        when(tokenService.validateToken(anyString())).thenReturn(authenticatedUser.getEmail());
        when(userRepository.findByEmail(authenticatedUser.getEmail()))
                .thenReturn(Optional.of(authenticatedUser));
    }

    @Test
    @DisplayName("GET /api/members retorna 200 com lista de membros")
    void listMembers_returnsOk() throws Exception {
        MemberDTO member = new MemberDTO(UUID.randomUUID(), "João", "joao@test.com", UserRole.USER);
        when(membersService.list(any())).thenReturn(List.of(member));

        mockMvc.perform(get("/api/members")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("João"))
                .andExpect(jsonPath("$[0].email").value("joao@test.com"))
                .andExpect(jsonPath("$[0].role").value("USER"));
    }

    @Test
    @DisplayName("GET /api/members retorna 403 sem autenticação")
    void listMembers_withoutAuth_returns403() throws Exception {
        mockMvc.perform(get("/api/members"))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 7: Rodar teste de controller para confirmar que falha**

```bash
cd backend && ./mvnw test -Dtest="MembersControllerTest" -q
```

Expected: FAIL — `MembersController` não existe.

- [ ] **Step 8: Criar MembersController**

Criar `backend/src/main/java/com/fintech/api/controller/MembersController.java`:

```java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.MemberDTO;
import com.fintech.api.service.MembersService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MembersController {

    private final MembersService membersService;

    @GetMapping
    public ResponseEntity<List<MemberDTO>> list() {
        User currentUser = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.ok(membersService.list(currentUser));
    }
}
```

- [ ] **Step 9: Rodar todos os testes do backend**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS — sem regressões.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/UserRepository.java \
        backend/src/main/java/com/fintech/api/service/MembersService.java \
        backend/src/main/java/com/fintech/api/controller/MembersController.java \
        backend/src/test/java/com/fintech/api/service/MembersServiceTest.java \
        backend/src/test/java/com/fintech/api/controller/MembersControllerTest.java
git commit -m "feat: GET /api/members lista usuários do tenant autenticado"
```

---

## Task 6: Frontend — InvitationService (novos métodos) + MembersService

**Contexto:** O `InvitationService` já existe com `validateToken` e `acceptInvite`. Vamos adicionar as interfaces e métodos necessários para a nova feature. `MembersService` é novo.

**Arquivos:**
- Modify: `frontend/src/app/core/services/invitation.ts`
- Modify: `frontend/src/app/core/services/invitation.spec.ts`
- Create: `frontend/src/app/core/services/members.ts`
- Create: `frontend/src/app/core/services/members.spec.ts`

- [ ] **Step 1: Escrever testes que falham para os novos métodos do InvitationService**

Adicionar ao final de `invitation.spec.ts` (antes do último `}`):

```typescript
it('create chama POST /invites com email', () => {
  service.create({ email: 'novo@test.com' }).subscribe(r => {
    expect(r.email).toBe('novo@test.com');
    expect(r.link).toBe('http://localhost:4200/accept-invite?token=tok');
  });

  const req = httpMock.expectOne('/invites');
  expect(req.request.method).toBe('POST');
  expect(req.request.body).toEqual({ email: 'novo@test.com' });
  req.flush({
    token: 'tok',
    link: 'http://localhost:4200/accept-invite?token=tok',
    email: 'novo@test.com',
    expiresAt: '2026-06-12T00:00:00',
  });
});

it('list chama GET /invites', () => {
  service.list().subscribe(invites => {
    expect(invites).toHaveLength(1);
    expect(invites[0].status).toBe('PENDING');
  });

  const req = httpMock.expectOne('/invites');
  expect(req.request.method).toBe('GET');
  req.flush([{
    id: 'uuid-1',
    email: 'x@test.com',
    status: 'PENDING',
    createdAt: '2026-06-05T10:00:00',
    expiresAt: '2026-06-12T10:00:00',
    link: 'http://localhost:4200/accept-invite?token=tok',
  }]);
});

it('revoke chama DELETE /invites/{id}', () => {
  service.revoke('uuid-1').subscribe();

  const req = httpMock.expectOne('/invites/uuid-1');
  expect(req.request.method).toBe('DELETE');
  req.flush(null);
});
```

- [ ] **Step 2: Rodar testes para confirmar que falham**

```bash
cd frontend && npm test -- --run invitation.spec.ts
```

Expected: FAIL — métodos e interfaces não existem.

- [ ] **Step 3: Atualizar invitation.ts com interfaces e métodos**

Substituir o conteúdo de `frontend/src/app/core/services/invitation.ts`:

```typescript
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

export interface CreateInvitationRequest {
  email: string;
}

export interface InvitationResponse {
  token: string;
  link: string;
  email: string;
  expiresAt: string;
}

export interface InvitationSummary {
  id: string;
  email: string;
  status: 'PENDING' | 'ACCEPTED' | 'EXPIRED';
  createdAt: string;
  expiresAt: string | null;
  link: string | null;
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

  create(dto: CreateInvitationRequest): Observable<InvitationResponse> {
    return this.http.post<InvitationResponse>('/invites', dto);
  }

  list(): Observable<InvitationSummary[]> {
    return this.http.get<InvitationSummary[]>('/invites');
  }

  revoke(id: string): Observable<void> {
    return this.http.delete<void>(`/invites/${id}`);
  }
}
```

- [ ] **Step 4: Criar members.spec.ts (teste primeiro)**

Criar `frontend/src/app/core/services/members.spec.ts`:

```typescript
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { MembersService } from './members';

describe('MembersService', () => {
  let service: MembersService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MembersService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('list chama GET /api/members', () => {
    service.list().subscribe(members => {
      expect(members).toHaveLength(1);
      expect(members[0].name).toBe('João');
      expect(members[0].role).toBe('ADMIN');
    });

    const req = httpMock.expectOne('/api/members');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'uuid-1', name: 'João', email: 'joao@test.com', role: 'ADMIN' }]);
  });
});
```

- [ ] **Step 5: Rodar testes para confirmar que falham**

```bash
cd frontend && npm test -- --run members.spec.ts
```

Expected: FAIL — `MembersService` não existe.

- [ ] **Step 6: Criar members.ts**

Criar `frontend/src/app/core/services/members.ts`:

```typescript
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Member {
  id: string;
  name: string;
  email: string;
  role: 'ADMIN' | 'USER';
}

@Injectable({ providedIn: 'root' })
export class MembersService {
  private http = inject(HttpClient);

  list(): Observable<Member[]> {
    return this.http.get<Member[]>('/api/members');
  }
}
```

- [ ] **Step 7: Rodar todos os testes de serviço para confirmar que passam**

```bash
cd frontend && npm test -- --run invitation.spec.ts members.spec.ts
```

Expected: todos PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/core/services/invitation.ts \
        frontend/src/app/core/services/invitation.spec.ts \
        frontend/src/app/core/services/members.ts \
        frontend/src/app/core/services/members.spec.ts
git commit -m "feat: InvitationService ganha create/list/revoke; MembersService criado"
```

---

## Task 7: InviteDialogComponent

**Contexto:** Dialog com dois estados (form → success). Formulário com campo email; ao submeter, chama `InvitationService.create()` e transiciona para o estado `success` exibindo o link gerado. O botão "Copiar link" usa `navigator.clipboard`. Erros aparecem como snackbar.

**Arquivos:**
- Create: `frontend/src/app/features/team/invite-dialog/invite-dialog.ts`
- Create: `frontend/src/app/features/team/invite-dialog/invite-dialog.html`
- Create: `frontend/src/app/features/team/invite-dialog/invite-dialog.spec.ts`

- [ ] **Step 1: Criar o teste primeiro**

Criar `frontend/src/app/features/team/invite-dialog/invite-dialog.spec.ts`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { of, throwError } from 'rxjs';
import { InviteDialogComponent } from './invite-dialog';
import { InvitationService } from '../../../core/services/invitation';

describe('InviteDialogComponent', () => {
  let fixture: ComponentFixture<InviteDialogComponent>;
  let component: InviteDialogComponent;
  let invitationSvc: { create: ReturnType<typeof vi.fn> };
  let dialogRef: { close: ReturnType<typeof vi.fn> };
  let snackBar: { open: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    invitationSvc = { create: vi.fn() };
    dialogRef = { close: vi.fn() };
    snackBar = { open: vi.fn() };

    await TestBed.configureTestingModule({
      imports: [InviteDialogComponent],
      providers: [
        provideAnimationsAsync(),
        { provide: InvitationService, useValue: invitationSvc },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(InviteDialogComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('inicia no estado form', () => {
    expect(component.state()).toBe('form');
  });

  it('transiciona para success após criar convite', async () => {
    invitationSvc.create.mockReturnValue(of({
      token: 'tok',
      link: 'http://localhost:4200/accept-invite?token=tok',
      email: 'x@test.com',
      expiresAt: '2026-06-12T00:00:00',
    }));

    component.form.setValue({ email: 'x@test.com' });
    component.submit();
    await fixture.whenStable();

    expect(component.state()).toBe('success');
    expect(component.inviteLink()).toBe('http://localhost:4200/accept-invite?token=tok');
  });

  it('exibe snackbar e volta ao estado form em caso de erro', async () => {
    invitationSvc.create.mockReturnValue(
      throwError(() => ({ error: { message: 'Convite já pendente' } }))
    );

    component.form.setValue({ email: 'x@test.com' });
    component.submit();
    await fixture.whenStable();

    expect(component.state()).toBe('form');
    expect(snackBar.open).toHaveBeenCalledWith('Convite já pendente', 'Fechar', { duration: 4000 });
  });

  it('copyLink chama navigator.clipboard.writeText e sinaliza copiado', async () => {
    component.inviteLink.set('http://localhost:4200/accept-invite?token=tok');
    vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();

    component.copyLink();
    await fixture.whenStable();

    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(
      'http://localhost:4200/accept-invite?token=tok'
    );
    expect(component.copied()).toBe(true);
  });

  it('close chama dialogRef.close()', () => {
    component.close();
    expect(dialogRef.close).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Rodar teste para confirmar que falha**

```bash
cd frontend && npm test -- --run invite-dialog.spec.ts
```

Expected: FAIL — componente não existe.

- [ ] **Step 3: Criar invite-dialog.ts**

Criar `frontend/src/app/features/team/invite-dialog/invite-dialog.ts`:

```typescript
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { InvitationService } from '../../../core/services/invitation';

type DialogState = 'form' | 'submitting' | 'success';

@Component({
  selector: 'app-invite-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './invite-dialog.html',
})
export class InviteDialogComponent {
  private dialogRef = inject(MatDialogRef<InviteDialogComponent>);
  private fb = inject(FormBuilder);
  private invitationService = inject(InvitationService);
  private snackBar = inject(MatSnackBar);

  state = signal<DialogState>('form');
  inviteLink = signal('');
  copied = signal(false);

  form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
  });

  submit(): void {
    if (this.form.invalid) return;
    this.state.set('submitting');

    this.invitationService.create({ email: this.form.value.email! }).subscribe({
      next: (response) => {
        this.inviteLink.set(response.link);
        this.state.set('success');
      },
      error: (err) => {
        this.snackBar.open(err.error?.message ?? 'Erro ao criar convite', 'Fechar', { duration: 4000 });
        this.state.set('form');
      },
    });
  }

  copyLink(): void {
    navigator.clipboard.writeText(this.inviteLink()).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    });
  }

  close(): void {
    this.dialogRef.close();
  }
}
```

- [ ] **Step 4: Criar invite-dialog.html**

Criar `frontend/src/app/features/team/invite-dialog/invite-dialog.html`:

```html
<h2 mat-dialog-title>Convidar membro</h2>

@if (state() === 'form' || state() === 'submitting') {
  <mat-dialog-content>
    <form [formGroup]="form">
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>E-mail</mat-label>
        <input matInput formControlName="email" type="email" placeholder="fulano@empresa.com" />
        @if (form.get('email')?.hasError('required')) {
          <mat-error>E-mail obrigatório</mat-error>
        }
        @if (form.get('email')?.hasError('email')) {
          <mat-error>E-mail inválido</mat-error>
        }
      </mat-form-field>
    </form>
  </mat-dialog-content>

  <mat-dialog-actions align="end">
    <button mat-button (click)="close()">Cancelar</button>
    <button
      mat-flat-button
      color="primary"
      [disabled]="form.invalid || state() === 'submitting'"
      (click)="submit()"
    >
      @if (state() === 'submitting') {
        <mat-spinner diameter="20" />
      } @else {
        Convidar
      }
    </button>
  </mat-dialog-actions>
}

@if (state() === 'success') {
  <mat-dialog-content>
    <p>Convite criado com sucesso! Compartilhe o link abaixo:</p>
    <mat-form-field appearance="outline" class="full-width">
      <mat-label>Link do convite</mat-label>
      <input matInput [value]="inviteLink()" readonly />
      <button matSuffix mat-icon-button (click)="copyLink()" [attr.aria-label]="'Copiar link'">
        <mat-icon>{{ copied() ? 'check' : 'content_copy' }}</mat-icon>
      </button>
    </mat-form-field>
    @if (copied()) {
      <p class="copied-feedback">Copiado!</p>
    }
  </mat-dialog-content>

  <mat-dialog-actions align="end">
    <button mat-flat-button (click)="close()">Fechar</button>
  </mat-dialog-actions>
}
```

- [ ] **Step 5: Rodar testes para confirmar que passam**

```bash
cd frontend && npm test -- --run invite-dialog.spec.ts
```

Expected: todos PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/features/team/invite-dialog/
git commit -m "feat: InviteDialogComponent com fluxo form → success e cópia de link"
```

---

## Task 8: TeamComponent

**Contexto:** Página principal da feature. Carrega membros e convites em paralelo (`forkJoin`). Exibe ações de escrita (Convidar, Revogar) condicionadas a `isAdmin`. Erros de rede exibem mensagem com botão de retry.

**Arquivos:**
- Create: `frontend/src/app/features/team/team/team.ts`
- Create: `frontend/src/app/features/team/team/team.html`
- Create: `frontend/src/app/features/team/team/team.scss`
- Create: `frontend/src/app/features/team/team/team.spec.ts`

- [ ] **Step 1: Criar o teste primeiro**

Criar `frontend/src/app/features/team/team/team.spec.ts`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { of, throwError } from 'rxjs';
import { TeamComponent } from './team';
import { MembersService } from '../../../core/services/members';
import { InvitationService } from '../../../core/services/invitation';
import { AuthService } from '../../../core/services/auth';
import { signal, computed } from '@angular/core';

describe('TeamComponent', () => {
  let fixture: ComponentFixture<TeamComponent>;
  let component: TeamComponent;
  let membersSvc: { list: ReturnType<typeof vi.fn> };
  let invitationSvc: { list: ReturnType<typeof vi.fn>; revoke: ReturnType<typeof vi.fn> };
  let dialog: { open: ReturnType<typeof vi.fn> };
  let snackBar: { open: ReturnType<typeof vi.fn> };

  const mockMember = { id: 'u1', name: 'João', email: 'j@test.com', role: 'ADMIN' as const };
  const mockInvite = {
    id: 'i1', email: 'x@test.com', status: 'PENDING' as const,
    createdAt: '2026-06-05T00:00:00', expiresAt: '2026-06-12T00:00:00',
    link: 'http://localhost:4200/accept-invite?token=tok',
  };

  const setupComponent = async (isAdmin: boolean) => {
    const currentUser = signal(isAdmin ? { role: 'ADMIN' } : { role: 'USER' });
    const authSvc = { isAdmin: computed(() => currentUser()?.role === 'ADMIN') };

    await TestBed.configureTestingModule({
      imports: [TeamComponent],
      providers: [
        provideAnimationsAsync(),
        { provide: MembersService, useValue: membersSvc },
        { provide: InvitationService, useValue: invitationSvc },
        { provide: AuthService, useValue: authSvc },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TeamComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  };

  beforeEach(() => {
    membersSvc = { list: vi.fn().mockReturnValue(of([mockMember])) };
    invitationSvc = {
      list: vi.fn().mockReturnValue(of([mockInvite])),
      revoke: vi.fn().mockReturnValue(of(void 0)),
    };
    dialog = { open: vi.fn().mockReturnValue({ afterClosed: () => of(null) }) };
    snackBar = { open: vi.fn() };
  });

  it('carrega membros e convites ao inicializar', async () => {
    await setupComponent(true);
    expect(component.members()).toHaveLength(1);
    expect(component.members()[0].name).toBe('João');
    expect(component.invitations()).toHaveLength(1);
    expect(component.invitations()[0].email).toBe('x@test.com');
  });

  it('exibe estado de erro quando carregamento falha', async () => {
    membersSvc.list.mockReturnValue(throwError(() => new Error('network')));
    await setupComponent(true);
    expect(component.error()).toBe('Erro ao carregar dados da equipe.');
  });

  it('openInviteDialog abre o dialog e recarrega após fechar', async () => {
    await setupComponent(true);
    component.openInviteDialog();
    expect(dialog.open).toHaveBeenCalled();
  });

  it('revoke chama invitationService.revoke e recarrega', async () => {
    await setupComponent(true);
    component.revoke('i1');
    await fixture.whenStable();
    expect(invitationSvc.revoke).toHaveBeenCalledWith('i1');
  });

  it('botão Convidar é visível para ADMIN', async () => {
    await setupComponent(true);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="invite-btn"]');
    expect(btn).not.toBeNull();
  });

  it('botão Convidar é oculto para USER', async () => {
    await setupComponent(false);
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('[data-testid="invite-btn"]');
    expect(btn).toBeNull();
  });
});
```

- [ ] **Step 2: Rodar teste para confirmar que falha**

```bash
cd frontend && npm test -- --run team.spec.ts
```

Expected: FAIL — `TeamComponent` não existe.

- [ ] **Step 3: Criar team.ts**

Criar `frontend/src/app/features/team/team/team.ts`:

```typescript
import { Component, OnInit, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../../core/services/auth';
import { InvitationService, InvitationSummary } from '../../../core/services/invitation';
import { Member, MembersService } from '../../../core/services/members';
import { InviteDialogComponent } from '../invite-dialog/invite-dialog';

@Component({
  selector: 'app-team',
  standalone: true,
  imports: [
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatTooltipModule,
  ],
  templateUrl: './team.html',
  styleUrl: './team.scss',
})
export class TeamComponent implements OnInit {
  private membersService = inject(MembersService);
  private invitationService = inject(InvitationService);
  private dialog = inject(MatDialog);
  private snackBar = inject(MatSnackBar);
  private authService = inject(AuthService);

  members = signal<Member[]>([]);
  invitations = signal<InvitationSummary[]>([]);
  loading = signal(true);
  error = signal('');

  isAdmin = this.authService.isAdmin;

  readonly memberColumns = ['name', 'email', 'role'];
  readonly inviteColumns = ['email', 'status', 'expiresAt', 'actions'];

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.loading.set(true);
    this.error.set('');

    forkJoin({
      members: this.membersService.list(),
      invitations: this.invitationService.list(),
    }).subscribe({
      next: ({ members, invitations }) => {
        this.members.set(members);
        this.invitations.set(invitations);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Erro ao carregar dados da equipe.');
        this.loading.set(false);
      },
    });
  }

  openInviteDialog(): void {
    const ref = this.dialog.open(InviteDialogComponent, { width: '480px' });
    ref.afterClosed().subscribe(() => this.loadAll());
  }

  revoke(id: string): void {
    this.invitationService.revoke(id).subscribe({
      next: () => this.loadAll(),
      error: (err) =>
        this.snackBar.open(err.error?.message ?? 'Erro ao revogar convite', 'Fechar', { duration: 4000 }),
    });
  }

  statusLabel(status: InvitationSummary['status']): string {
    return { PENDING: 'Pendente', ACCEPTED: 'Aceito', EXPIRED: 'Expirado' }[status];
  }

  statusColor(status: InvitationSummary['status']): string {
    return { PENDING: 'primary', ACCEPTED: 'accent', EXPIRED: '' }[status];
  }
}
```

- [ ] **Step 4: Criar team.html**

Criar `frontend/src/app/features/team/team/team.html`:

```html
<div class="page-header">
  <h1>Equipe</h1>
  @if (isAdmin()) {
    <button mat-flat-button color="primary" data-testid="invite-btn" (click)="openInviteDialog()">
      <mat-icon>person_add</mat-icon>
      Convidar
    </button>
  }
</div>

@if (loading()) {
  <div class="loading-center">
    <mat-spinner diameter="40" />
  </div>
} @else if (error()) {
  <div class="error-state">
    <p>{{ error() }}</p>
    <button mat-stroked-button (click)="loadAll()">Tentar novamente</button>
  </div>
} @else {
  <mat-card>
    <mat-card-header>
      <mat-card-title>Membros ({{ members().length }})</mat-card-title>
    </mat-card-header>
    <mat-card-content>
      <table mat-table [dataSource]="members()" class="full-width">
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Nome</th>
          <td mat-cell *matCellDef="let m">{{ m.name }}</td>
        </ng-container>
        <ng-container matColumnDef="email">
          <th mat-header-cell *matHeaderCellDef>E-mail</th>
          <td mat-cell *matCellDef="let m">{{ m.email }}</td>
        </ng-container>
        <ng-container matColumnDef="role">
          <th mat-header-cell *matHeaderCellDef>Papel</th>
          <td mat-cell *matCellDef="let m">{{ m.role === 'ADMIN' ? 'Admin' : 'Membro' }}</td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="memberColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: memberColumns"></tr>
      </table>
    </mat-card-content>
  </mat-card>

  <mat-card class="invites-card">
    <mat-card-header>
      <mat-card-title>Convites ({{ invitations().length }})</mat-card-title>
    </mat-card-header>
    <mat-card-content>
      @if (invitations().length === 0) {
        <p class="empty-state">Nenhum convite enviado ainda.</p>
      } @else {
        <table mat-table [dataSource]="invitations()" class="full-width">
          <ng-container matColumnDef="email">
            <th mat-header-cell *matHeaderCellDef>E-mail</th>
            <td mat-cell *matCellDef="let i">{{ i.email }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let i">
              <mat-chip [color]="statusColor(i.status)" highlighted>
                {{ statusLabel(i.status) }}
              </mat-chip>
            </td>
          </ng-container>
          <ng-container matColumnDef="expiresAt">
            <th mat-header-cell *matHeaderCellDef>Expira em</th>
            <td mat-cell *matCellDef="let i">
              {{ i.status === 'PENDING' ? (i.expiresAt | date: 'dd/MM/yyyy') : '—' }}
            </td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let i">
              @if (isAdmin() && i.status === 'PENDING') {
                <button
                  mat-icon-button
                  color="warn"
                  matTooltip="Revogar convite"
                  (click)="revoke(i.id)"
                >
                  <mat-icon>delete</mat-icon>
                </button>
              }
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="inviteColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: inviteColumns"></tr>
        </table>
      }
    </mat-card-content>
  </mat-card>
}
```

- [ ] **Step 5: Criar team.scss**

Criar `frontend/src/app/features/team/team/team.scss`:

```scss
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;

  h1 {
    margin: 0;
    font-size: 24px;
    font-weight: 500;
  }
}

.loading-center {
  display: flex;
  justify-content: center;
  padding: 48px;
}

.error-state {
  text-align: center;
  padding: 48px;
  color: var(--mat-sys-error);
}

.full-width {
  width: 100%;
}

.invites-card {
  margin-top: 24px;
}

.empty-state {
  color: var(--mat-sys-on-surface-variant);
  padding: 16px 0;
}
```

- [ ] **Step 6: Adicionar DatePipe ao template**

O template usa `date` pipe — precisamos importar `DatePipe` no componente. Em `team.ts`, adicionar ao array `imports`:

```typescript
import { DatePipe } from '@angular/common';

// No array imports do @Component:
DatePipe,
```

- [ ] **Step 7: Rodar testes para confirmar que passam**

```bash
cd frontend && npm test -- --run team.spec.ts
```

Expected: todos PASS.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/features/team/
git commit -m "feat: TeamComponent com listagem de membros e gerenciamento de convites"
```

---

## Task 9: Rota + item de navegação

**Contexto:** Última etapa — expor a feature no roteador e adicionar o link no sidenav do shell.

**Arquivos:**
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/components/shell/shell.ts`

- [ ] **Step 1: Adicionar rota /team ao app.routes.ts**

Em `frontend/src/app/app.routes.ts`, adicionar dentro do bloco `children` do `ShellComponent` (após a rota `categories/:id`):

```typescript
{
  path: 'team',
  loadComponent: () => import('./features/team/team/team').then(m => m.TeamComponent)
},
```

- [ ] **Step 2: Adicionar item "Equipe" no sidenav**

Em `frontend/src/app/components/shell/shell.ts`, adicionar ao array `navItems`:

```typescript
{ label: 'Equipe', icon: 'group', route: '/team' },
```

- [ ] **Step 3: Verificar que o frontend compila sem erros**

```bash
cd frontend && npm run build -- --configuration development 2>&1 | tail -5
```

Expected: sem erros de compilação.

- [ ] **Step 4: Rodar todos os testes do frontend**

```bash
cd frontend && npm test -- --run
```

Expected: todos PASS — sem regressões.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/app.routes.ts \
        frontend/src/app/components/shell/shell.ts
git commit -m "feat: rota /team e item Equipe no sidenav"
```

---

## Self-Review

**Spec coverage:**

| Requisito do spec | Task |
|---|---|
| Fix JWT role claim | Task 1 |
| isAdmin no AuthService | Task 1 |
| InvitationStatus enum | Task 2 |
| InvitationSummaryDTO | Task 2 |
| MemberDTO | Task 2 |
| InvitationService.list() + testes | Task 3 |
| InvitationService.revoke() + testes | Task 3 |
| GET /invites (ADMIN) + testes | Task 4 |
| DELETE /invites/{id} (ADMIN) + testes | Task 4 |
| SecurityConfigurations atualizado | Task 4 |
| MembersService + testes | Task 5 |
| MembersController GET /api/members + testes | Task 5 |
| Frontend InvitationService.create/list/revoke | Task 6 |
| Frontend MembersService | Task 6 |
| InviteDialogComponent (form → success, clipboard) | Task 7 |
| TeamComponent (membros, convites, isAdmin) | Task 8 |
| Rota /team + nav item | Task 9 |

**Nenhum gap identificado.**

**Tipos consistentes entre tasks:**
- `InvitationSummary.status: 'PENDING' | 'ACCEPTED' | 'EXPIRED'` — definido em Task 6, usado em Task 7 e 8 ✓
- `Member` interface — definida em Task 6, usada em Task 8 ✓
- `isAdmin` como `computed` em `AuthService` — definido em Task 1, consumido em Task 8 ✓
- `InviteDialogComponent` importado em `TeamComponent` — criado em Task 7, usado em Task 8 ✓
