# Transferência entre Contas — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expor endpoint de transferência entre contas no backend e criar formulário com toggle TRANSACTION/TRANSFER no Angular, com exclusão atômica das duas pernas.

**Architecture:** `TransferController` dedicado com `POST /api/transfers` e `DELETE /api/transfers/{transferId}`; frontend com `MatButtonToggle` no `TransactionForm` existente — sem nova rota nem novo componente. Double-entry preservada: transferência gera EXPENSE (conta origem) + INCOME (conta destino) com mesmo `transferId`.

**Tech Stack:** Java 21, Spring Boot 4, JPA/Hibernate, OpenAPI Generator (backend), Angular 21 Zoneless, Signals, Angular Material 3, Orval (cliente HTTP gerado).

---

## Paralelização com Git Worktrees

Tarefas 1–7 (backend) e Tarefas 8–12 (frontend) são independentes e devem rodar em paralelo, cada uma em seu próprio worktree. Criar os worktrees **antes** de despachar os agentes:

```bash
# Executar a partir do diretório raiz do projeto
git worktree add ../fintech-backend -b feat/transfer-backend
git worktree add ../fintech-frontend -b feat/transfer-frontend
```

Ao final, mergear em develop:

```bash
git checkout develop
git merge feat/transfer-backend --no-ff -m "feat: implementa endpoint de transferência entre contas (backend)"
git merge feat/transfer-frontend --no-ff -m "feat: implementa formulário de transferência no Angular (frontend)"
```

> Se houver conflito no `openapi.yaml`: as mudanças são puramente aditivas nos dois worktrees. Resolver mantendo os blocos novos de ambos os lados.

---

## Mapa de arquivos

| Arquivo | Ação | Agente |
|---------|------|--------|
| `api-spec/openapi.yaml` | Modify — adiciona schemas + paths de transferência | Backend e Frontend (ambos) |
| `backend/src/main/resources/static/openapi.yaml` | Modify — cópia do anterior (Swagger UI) | Backend e Frontend (ambos) |
| `backend/.../repository/TransactionRepository.java` | Modify — +1 método de query | Backend |
| `backend/.../dto/transfer/TransferRequestDTO.java` | Create | Backend |
| `backend/.../dto/transfer/TransferResponseDTO.java` | Create | Backend |
| `backend/.../service/TransactionService.java` | Modify — refatora createTransfer + novo deleteTransfer | Backend |
| `backend/.../controller/TransferController.java` | Create | Backend |
| `backend/.../service/TransactionServiceTest.java` | Modify — atualiza + novos cenários | Backend |
| `backend/.../controller/TransferControllerTest.java` | Create | Backend |
| `frontend/src/app/core/api/transfers/transfers.service.ts` | Generated (Orval) | Frontend |
| `frontend/.../transaction-form/transaction-form.ts` | Modify — toggle + campos + lógica de submit | Frontend |
| `frontend/.../transaction-form/transaction-form.html` | Modify — toggle + campos condicionais | Frontend |
| `frontend/.../transaction-form/transaction-form.scss` | Modify — estilos do toggle e erro cross-field | Frontend |
| `frontend/.../transaction-list/transaction-list.ts` | Modify — typeLabel + onDelete atômico | Frontend |
| `frontend/.../transaction-list/transaction-list.html` | Modify — botão editar desabilitado + typeLabel | Frontend |

---

## Tarefas do Backend (worktree `../fintech-backend`)

### Tarefa 1: Atualizar openapi.yaml

**Files:**
- Modify: `api-spec/openapi.yaml`
- Modify: `backend/src/main/resources/static/openapi.yaml`

- [ ] **Step 1.1: Adicionar schemas TransferRequest e TransferResponse**

Abrir `api-spec/openapi.yaml`. Na seção `components.schemas`, localizar o comentário `# --- Dashboard ---` e inserir **antes** dele:

```yaml
    TransferRequest:
      type: object
      required: [fromAccountId, toAccountId, amount, date]
      properties:
        fromAccountId:
          type: string
          format: uuid
        toAccountId:
          type: string
          format: uuid
        amount:
          type: number
          format: double
          minimum: 0.01
        date:
          type: string
          format: date
        description:
          type: string
          nullable: true

    TransferResponse:
      type: object
      properties:
        transferId:
          type: string
          format: uuid
        fromLegId:
          type: string
          format: uuid
        toLegId:
          type: string
          format: uuid
        amount:
          type: number
          format: double
        date:
          type: string
          format: date
        description:
          type: string
        fromAccount:
          type: string
        toAccount:
          type: string
```

- [ ] **Step 1.2: Adicionar paths /api/transfers**

No final do arquivo `api-spec/openapi.yaml`, após o bloco de `/api/transactions/{id}`, adicionar:

```yaml
  # --- Transfers ---

  /api/transfers:
    post:
      tags: [transfers]
      operationId: createTransfer
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TransferRequest'
      responses:
        '201':
          description: Transferência criada com sucesso
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TransferResponse'
        '400':
          description: Contas iguais ou dados inválidos

  /api/transfers/{transferId}:
    delete:
      tags: [transfers]
      operationId: deleteTransfer
      parameters:
        - name: transferId
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '204':
          description: Transferência excluída
        '404':
          description: Transferência não encontrada
```

- [ ] **Step 1.3: Sincronizar a cópia do Swagger UI**

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

- [ ] **Step 1.4: Gerar a interface TransfersApi**

```bash
cd backend && ./mvnw generate-sources -q
```

Verificar geração:

```bash
ls target/generated-sources/openapi/src/main/java/com/fintech/api/openapi/TransfersApi.java
```

Expected: arquivo existe.

- [ ] **Step 1.5: Commit**

```bash
git add api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
git commit -m "feat(openapi): adiciona schemas e paths para transferência entre contas"
```

---

### Tarefa 2: Adicionar método ao TransactionRepository

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/repository/TransactionRepository.java`

- [ ] **Step 2.1: Adicionar o método derivado**

Após o método `findByIdAndTenant`, adicionar:

```java
List<Transaction> findByTransferIdAndTenant(UUID transferId, Tenant tenant);
```

O Spring Data JPA deriva o SQL automaticamente: `WHERE transfer_id = ? AND tenant_id = ?`. Nenhum `@Query` necessário.

- [ ] **Step 2.2: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 2.3: Commit**

```bash
git add backend/src/main/java/com/fintech/api/repository/TransactionRepository.java
git commit -m "feat(repository): adiciona findByTransferIdAndTenant para busca das pernas de transferência"
```

---

### Tarefa 3: Criar TransferRequestDTO e TransferResponseDTO

**Files:**
- Create: `backend/src/main/java/com/fintech/api/dto/transfer/TransferRequestDTO.java`
- Create: `backend/src/main/java/com/fintech/api/dto/transfer/TransferResponseDTO.java`

- [ ] **Step 3.1: Criar TransferRequestDTO**

```java
// backend/src/main/java/com/fintech/api/dto/transfer/TransferRequestDTO.java
package com.fintech.api.dto.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransferRequestDTO(
        @NotNull(message = "A conta de origem é obrigatória") UUID fromAccountId,
        @NotNull(message = "A conta de destino é obrigatória") UUID toAccountId,
        @NotNull(message = "O valor é obrigatório")
        @DecimalMin(value = "0.01", message = "O valor deve ser positivo") BigDecimal amount,
        @NotNull(message = "A data é obrigatória") LocalDate date,
        String description
) {}
```

- [ ] **Step 3.2: Criar TransferResponseDTO**

```java
// backend/src/main/java/com/fintech/api/dto/transfer/TransferResponseDTO.java
package com.fintech.api.dto.transfer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransferResponseDTO(
        UUID transferId,
        UUID fromLegId,
        UUID toLegId,
        BigDecimal amount,
        LocalDate date,
        String description,
        String fromAccount,
        String toAccount
) {}
```

- [ ] **Step 3.3: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3.4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/transfer/
git commit -m "feat(dto): adiciona TransferRequestDTO e TransferResponseDTO"
```

---

### Tarefa 4: Refatorar TransactionService

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TransactionService.java`

O método `createTransfer` atual (assinatura com parâmetros avulsos, linhas 79–103) será substituído por uma versão que recebe `TransferRequestDTO` e retorna `TransferResponseDTO`. O método `deleteTransfer` será adicionado.

- [ ] **Step 4.1: Adicionar imports**

No topo do arquivo, adicionar:

```java
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
```

- [ ] **Step 4.2: Substituir o método createTransfer**

Localizar e substituir o método `createTransfer` existente pelo seguinte:

```java
@Transactional
public TransferResponseDTO createTransfer(TransferRequestDTO dto, User user) {
    if (dto.fromAccountId().equals(dto.toAccountId())) {
        throw new IllegalArgumentException("As contas de origem e destino devem ser diferentes.");
    }
    Account from = resolveAccount(dto.fromAccountId(), user);
    Account to   = resolveAccount(dto.toAccountId(), user);
    UUID transferId = UUID.randomUUID();
    String description = (dto.description() != null && !dto.description().isBlank())
            ? dto.description() : "Transferência";

    Transaction expense = repository.save(Transaction.builder()
            .description(description)
            .amount(dto.amount()).date(dto.date())
            .type(TransactionType.EXPENSE)
            .status(TransactionStatus.PAID)
            .installmentNumber(1).totalInstallments(1)
            .tenant(user.getTenant()).user(user)
            .account(from).transferId(transferId)
            .build());

    Transaction income = repository.save(Transaction.builder()
            .description(description)
            .amount(dto.amount()).date(dto.date())
            .type(TransactionType.INCOME)
            .status(TransactionStatus.PAID)
            .installmentNumber(1).totalInstallments(1)
            .tenant(user.getTenant()).user(user)
            .account(to).transferId(transferId)
            .build());

    return new TransferResponseDTO(
            transferId,
            expense.getId(),
            income.getId(),
            dto.amount(),
            dto.date(),
            description,
            from.getName(),
            to.getName()
    );
}
```

- [ ] **Step 4.3: Adicionar método deleteTransfer**

Logo após `createTransfer`, adicionar:

```java
@Transactional
public void deleteTransfer(UUID transferId, User user) {
    List<Transaction> legs = repository.findByTransferIdAndTenant(transferId, user.getTenant());
    if (legs.isEmpty()) {
        throw new EntityNotFoundException("Transferência não encontrada.");
    }
    repository.deleteAll(legs);
}
```

- [ ] **Step 4.4: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4.5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/TransactionService.java
git commit -m "feat(service): refatora createTransfer para DTO e adiciona deleteTransfer atômico"
```

---

### Tarefa 5: Criar TransferController

**Files:**
- Create: `backend/src/main/java/com/fintech/api/controller/TransferController.java`

- [ ] **Step 5.1: Criar o controller**

```java
// backend/src/main/java/com/fintech/api/controller/TransferController.java
package com.fintech.api.controller;

import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
import com.fintech.api.openapi.TransfersApi;
import com.fintech.api.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController implements TransfersApi {

    private final TransactionService service;

    @Override
    @PostMapping
    public ResponseEntity<TransferResponseDTO> createTransfer(
            @RequestBody @Valid TransferRequestDTO transferRequestDTO) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createTransfer(transferRequestDTO, getAuthenticatedUser()));
    }

    @Override
    @DeleteMapping("/{transferId}")
    public ResponseEntity<Void> deleteTransfer(@PathVariable UUID transferId) {
        service.deleteTransfer(transferId, getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
```

- [ ] **Step 5.2: Verificar compilação**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5.3: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/TransferController.java
git commit -m "feat(controller): adiciona TransferController com POST /api/transfers e DELETE /api/transfers/{id}"
```

---

### Tarefa 6: Atualizar testes do TransactionService

**Files:**
- Modify: `backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java`

O teste `createTransferMirrorsTransactions` usa a assinatura antiga do service. Ele será atualizado, e novos cenários serão adicionados.

- [ ] **Step 6.1: Adicionar imports**

```java
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
```

- [ ] **Step 6.2: Atualizar o teste existente**

Substituir o corpo do método `createTransferMirrorsTransactions`:

```java
@Test
@DisplayName("createTransfer cria duas transações espelhadas com mesmo transferId")
void createTransferMirrorsTransactions() {
    User user = buildUser();
    Account from = buildAccount(user);
    Account to   = buildAccount(user);

    when(accountRepository.findByIdAndTenant(from.getId(), user.getTenant())).thenReturn(Optional.of(from));
    when(accountRepository.findByIdAndTenant(to.getId(), user.getTenant())).thenReturn(Optional.of(to));
    when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    TransferRequestDTO dto = new TransferRequestDTO(
            from.getId(), to.getId(), new BigDecimal("500.00"), LocalDate.now(), null);

    service.createTransfer(dto, user);

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(repository, times(2)).save(captor.capture());

    List<Transaction> saved = captor.getAllValues();
    Transaction expense = saved.stream().filter(t -> t.getType() == TransactionType.EXPENSE).findFirst().orElseThrow();
    Transaction income  = saved.stream().filter(t -> t.getType() == TransactionType.INCOME).findFirst().orElseThrow();

    assertThat(expense.getAccount()).isEqualTo(from);
    assertThat(income.getAccount()).isEqualTo(to);
    assertThat(expense.getTransferId()).isNotNull().isEqualTo(income.getTransferId());
    assertThat(expense.getAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
    assertThat(expense.getDescription()).isEqualTo("Transferência");
}
```

- [ ] **Step 6.3: Adicionar teste de contas iguais**

```java
@Test
@DisplayName("createTransfer lança IllegalArgumentException quando contas são iguais")
void createTransferRejectsEqualAccounts() {
    User user = buildUser();
    UUID sameId = UUID.randomUUID();
    TransferRequestDTO dto = new TransferRequestDTO(
            sameId, sameId, new BigDecimal("100.00"), LocalDate.now(), null);

    assertThatThrownBy(() -> service.createTransfer(dto, user))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("diferentes");
}
```

- [ ] **Step 6.4: Adicionar teste de descrição customizada**

```java
@Test
@DisplayName("createTransfer usa descrição customizada quando fornecida")
void createTransferUsesCustomDescription() {
    User user = buildUser();
    Account from = buildAccount(user);
    Account to   = buildAccount(user);

    when(accountRepository.findByIdAndTenant(from.getId(), user.getTenant())).thenReturn(Optional.of(from));
    when(accountRepository.findByIdAndTenant(to.getId(), user.getTenant())).thenReturn(Optional.of(to));
    when(repository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    TransferRequestDTO dto = new TransferRequestDTO(
            from.getId(), to.getId(), new BigDecimal("200.00"), LocalDate.now(), "Reserva emergência");

    service.createTransfer(dto, user);

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(repository, times(2)).save(captor.capture());
    captor.getAllValues().forEach(t ->
            assertThat(t.getDescription()).isEqualTo("Reserva emergência"));
}
```

- [ ] **Step 6.5: Adicionar testes de deleteTransfer**

```java
@Test
@DisplayName("deleteTransfer exclui as duas pernas da transferência")
void deleteTransferRemovesBothLegs() {
    User user = buildUser();
    UUID transferId = UUID.randomUUID();
    Account from = buildAccount(user);
    Account to   = buildAccount(user);

    Transaction leg1 = Transaction.builder().id(UUID.randomUUID())
            .type(TransactionType.EXPENSE).account(from)
            .transferId(transferId).tenant(user.getTenant()).build();
    Transaction leg2 = Transaction.builder().id(UUID.randomUUID())
            .type(TransactionType.INCOME).account(to)
            .transferId(transferId).tenant(user.getTenant()).build();

    when(repository.findByTransferIdAndTenant(transferId, user.getTenant()))
            .thenReturn(List.of(leg1, leg2));

    service.deleteTransfer(transferId, user);

    verify(repository).deleteAll(List.of(leg1, leg2));
}

@Test
@DisplayName("deleteTransfer lança EntityNotFoundException para transferId inexistente")
void deleteTransferThrowsForUnknownId() {
    User user = buildUser();
    UUID transferId = UUID.randomUUID();

    when(repository.findByTransferIdAndTenant(transferId, user.getTenant()))
            .thenReturn(List.of());

    assertThatThrownBy(() -> service.deleteTransfer(transferId, user))
            .isInstanceOf(EntityNotFoundException.class);
}
```

- [ ] **Step 6.6: Rodar os testes**

```bash
cd backend && ./mvnw test -Dtest=TransactionServiceTest -q
```

Expected: BUILD SUCCESS, todos os testes verdes.

- [ ] **Step 6.7: Commit**

```bash
git add backend/src/test/java/com/fintech/api/service/TransactionServiceTest.java
git commit -m "test(service): atualiza e adiciona testes de createTransfer e deleteTransfer"
```

---

### Tarefa 7: Criar TransferControllerTest

**Files:**
- Create: `backend/src/test/java/com/fintech/api/controller/TransferControllerTest.java`

- [ ] **Step 7.1: Criar o arquivo de teste**

```java
// backend/src/test/java/com/fintech/api/controller/TransferControllerTest.java
package com.fintech.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.api.config.SecurityConfigurations;
import com.fintech.api.config.SecurityFilter;
import com.fintech.api.config.TokenService;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.transfer.TransferRequestDTO;
import com.fintech.api.dto.transfer.TransferResponseDTO;
import com.fintech.api.exception.EntityNotFoundException;
import com.fintech.api.repository.UserRepository;
import com.fintech.api.service.TransactionService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import({ SecurityConfigurations.class, SecurityFilter.class })
class TransferControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private TransactionService transactionService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private TokenService tokenService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private User user;
    private String token;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test@email.com");
        user.setPasswordHash("hash");
        user.setTenant(new Tenant());
        user.getTenant().setId(UUID.randomUUID());

        token = "valid-token";
        when(tokenService.validateToken(token)).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("POST /api/transfers com payload válido retorna 201 com transferId")
    void createTransferReturns201() throws Exception {
        UUID fromId    = UUID.randomUUID();
        UUID toId      = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        TransferRequestDTO request = new TransferRequestDTO(
                fromId, toId, new BigDecimal("500.00"), LocalDate.now(), "Reserva");

        TransferResponseDTO response = new TransferResponseDTO(
                transferId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("500.00"), LocalDate.now(), "Reserva",
                "Nubank", "Inter");

        when(transactionService.createTransfer(any(TransferRequestDTO.class), any(User.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.fromAccount").value("Nubank"))
                .andExpect(jsonPath("$.toAccount").value("Inter"));
    }

    @Test
    @DisplayName("POST /api/transfers com contas iguais retorna 400")
    void createTransferWithEqualAccountsReturns400() throws Exception {
        UUID sameId = UUID.randomUUID();
        TransferRequestDTO request = new TransferRequestDTO(
                sameId, sameId, new BigDecimal("100.00"), LocalDate.now(), null);

        when(transactionService.createTransfer(any(TransferRequestDTO.class), any(User.class)))
                .thenThrow(new IllegalArgumentException("As contas de origem e destino devem ser diferentes."));

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/transfers sem autenticação retorna 403")
    void createTransferWithoutAuthReturns403() throws Exception {
        TransferRequestDTO request = new TransferRequestDTO(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), LocalDate.now(), null);

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/transfers/{transferId} válido retorna 204")
    void deleteTransferReturns204() throws Exception {
        mockMvc.perform(delete("/api/transfers/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/transfers/{transferId} inexistente retorna 404")
    void deleteTransferNotFoundReturns404() throws Exception {
        doThrow(new EntityNotFoundException("Transferência não encontrada."))
                .when(transactionService).deleteTransfer(any(UUID.class), any(User.class));

        mockMvc.perform(delete("/api/transfers/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 7.2: Rodar todos os testes do backend**

```bash
cd backend && ./mvnw test -q
```

Expected: BUILD SUCCESS — todos os testes verdes.

- [ ] **Step 7.3: Commit final do backend**

```bash
git add backend/src/test/java/com/fintech/api/controller/TransferControllerTest.java
git commit -m "test(controller): adiciona TransferControllerTest com 5 cenários de criação e exclusão"
```

---

## Tarefas do Frontend (worktree `../fintech-frontend`)

### Tarefa 8: Gerar TransfersService com Orval

**Files:**
- Modify: `api-spec/openapi.yaml`
- Modify: `backend/src/main/resources/static/openapi.yaml`
- Generated: `frontend/src/app/core/api/transfers/transfers.service.ts`

- [ ] **Step 8.1: Aplicar as mesmas mudanças do openapi.yaml**

Aplicar exatamente os Steps 1.1 e 1.2 da Tarefa 1 ao `api-spec/openapi.yaml` deste worktree. Depois:

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

- [ ] **Step 8.2: Rodar Orval**

```bash
cd frontend && npm run api:generate
```

Expected: `src/app/core/api/transfers/transfers.service.ts` gerado sem erros.

- [ ] **Step 8.3: Verificar métodos gerados**

```bash
grep -n "createTransfer\|deleteTransfer" frontend/src/app/core/api/transfers/transfers.service.ts
```

Expected: ambos os métodos presentes.

- [ ] **Step 8.4: Commit**

```bash
git add api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml frontend/src/app/core/api/
git commit -m "feat(openapi): adiciona endpoints de transferência e regenera cliente Orval"
```

---

### Tarefa 9: Atualizar TransactionForm — TypeScript

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`

- [ ] **Step 9.1: Adicionar imports**

No topo do arquivo, adicionar:

```typescript
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { AbstractControl, ValidationErrors } from '@angular/forms';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
```

Adicionar `MatButtonToggleModule` ao array `imports` do `@Component`.

- [ ] **Step 9.2: Injetar TransfersService**

Na classe, após os injects existentes:

```typescript
private transferService = inject(TransfersService);
```

- [ ] **Step 9.3: Adicionar signal de modo**

Após os signals existentes:

```typescript
mode = signal<'TRANSACTION' | 'TRANSFER'>('TRANSACTION');
```

- [ ] **Step 9.4: Atualizar o FormGroup com campos de transferência e validador**

Substituir a declaração do `form` por:

```typescript
form = this.fb.group({
  description: ['', [Validators.required, Validators.minLength(2)]],
  amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
  date: [new Date(), Validators.required],
  type: ['EXPENSE', Validators.required],
  status: ['PENDING'],
  totalInstallments: [1, [Validators.min(1), Validators.max(48)]],
  categoryId: [null as string | null],
  accountId: [null as string | null, Validators.required],
  fromAccountId: [null as string | null],
  toAccountId: [null as string | null]
}, { validators: this.differentAccountsValidator });
```

- [ ] **Step 9.5: Adicionar o validador cross-field**

Na classe (pode ser método privado estático):

```typescript
private differentAccountsValidator(group: AbstractControl): ValidationErrors | null {
  const from = group.get('fromAccountId')?.value;
  const to = group.get('toAccountId')?.value;
  if (from && to && from === to) {
    return { sameAccount: true };
  }
  return null;
}
```

- [ ] **Step 9.6: Adicionar onModeChange**

```typescript
onModeChange(mode: 'TRANSACTION' | 'TRANSFER'): void {
  this.mode.set(mode);
  const fromCtrl    = this.form.controls.fromAccountId;
  const toCtrl      = this.form.controls.toAccountId;
  const accountCtrl = this.form.controls.accountId;
  const descCtrl    = this.form.controls.description;

  if (mode === 'TRANSFER') {
    fromCtrl.setValidators(Validators.required);
    toCtrl.setValidators(Validators.required);
    accountCtrl.clearValidators();
    descCtrl.clearValidators();           // descrição é opcional em transferência
  } else {
    fromCtrl.clearValidators();
    toCtrl.clearValidators();
    accountCtrl.setValidators(Validators.required);
    descCtrl.setValidators([Validators.required, Validators.minLength(2)]);
  }
  fromCtrl.updateValueAndValidity();
  toCtrl.updateValueAndValidity();
  accountCtrl.updateValueAndValidity();
  descCtrl.updateValueAndValidity();
}
```

- [ ] **Step 9.7: Substituir o método onSubmit**

```typescript
onSubmit(): void {
  if (this.form.invalid) return;
  const raw = this.form.getRawValue();
  this.saving.set(true);

  if (this.isEditMode()) {
    this.transactionService.updateTransaction(this.transactionId()!, {
      description: raw.description!,
      amount: raw.amount!,
      date: this.toDateString(raw.date as Date),
      type: raw.type as 'INCOME' | 'EXPENSE',
      status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
      categoryId: raw.categoryId ?? undefined,
      accountId: raw.accountId!
    }).subscribe({
      next: () => {
        this.snackBar.open('Transação atualizada com sucesso!', 'OK', { duration: 3000 });
        this.router.navigate(['/transactions']);
      },
      error: () => {
        this.saving.set(false);
        this.snackBar.open('Erro ao atualizar transação.', 'Fechar', { duration: 5000 });
      }
    });
    return;
  }

  if (this.mode() === 'TRANSFER') {
    this.transferService.createTransfer({
      fromAccountId: raw.fromAccountId!,
      toAccountId: raw.toAccountId!,
      amount: raw.amount!,
      date: this.toDateString(raw.date as Date),
      description: raw.description || undefined
    }).subscribe({
      next: () => {
        this.snackBar.open('Transferência registrada com sucesso!', 'OK', { duration: 3000 });
        this.router.navigate(['/transactions']);
      },
      error: () => {
        this.saving.set(false);
        this.snackBar.open('Erro ao registrar transferência.', 'Fechar', { duration: 5000 });
      }
    });
    return;
  }

  this.transactionService.createTransaction({
    description: raw.description!,
    amount: raw.amount!,
    date: this.toDateString(raw.date as Date),
    type: raw.type as 'INCOME' | 'EXPENSE',
    status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
    categoryId: raw.categoryId ?? undefined,
    accountId: raw.accountId!,
    totalInstallments: raw.totalInstallments ?? 1
  }).subscribe({
    next: (created) => {
      const msg = created.length > 1
        ? `${created.length} parcelas criadas com sucesso!`
        : 'Transação criada com sucesso!';
      this.snackBar.open(msg, 'OK', { duration: 3000 });
      this.router.navigate(['/transactions']);
    },
    error: () => {
      this.saving.set(false);
      this.snackBar.open('Erro ao salvar transação.', 'Fechar', { duration: 5000 });
    }
  });
}
```

- [ ] **Step 9.8: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Expected: sem erros.

- [ ] **Step 9.9: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/transaction-form.ts
git commit -m "feat(form): adiciona modo transferência com toggle, validação cross-field e lógica de submit"
```

---

### Tarefa 10: Atualizar template e estilos do TransactionForm

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.html`
- Modify: `frontend/src/app/features/transaction/transaction-form/transaction-form.scss`

- [ ] **Step 10.1: Atualizar o título da página**

Substituir o `<h1>` existente por:

```html
<h1>{{ isEditMode() ? 'Editar Transação' : (mode() === 'TRANSFER' ? 'Nova Transferência' : 'Nova Transação') }}</h1>
```

- [ ] **Step 10.2: Adicionar toggle logo após a tag `<form>`**

Após `<form [formGroup]="form" (ngSubmit)="onSubmit()">`, adicionar:

```html
@if (!isEditMode()) {
  <div class="form-row mode-toggle-row">
    <mat-button-toggle-group [value]="mode()" (change)="onModeChange($event.value)" [hideSingleSelectionIndicator]="true">
      <mat-button-toggle value="TRANSACTION">Receita / Despesa</mat-button-toggle>
      <mat-button-toggle value="TRANSFER">Transferência</mat-button-toggle>
    </mat-button-toggle-group>
  </div>
}
```

- [ ] **Step 10.3: Envolver campos exclusivos de transação em `@if`**

Envolver os blocos de campos "tipo + status", "parcelas + categoria" e "conta" num `@if (mode() === 'TRANSACTION')`:

```html
@if (mode() === 'TRANSACTION') {
  <div class="form-row form-row-cols">
    <mat-form-field appearance="outline">
      <mat-label>Tipo</mat-label>
      <mat-select formControlName="type">
        <mat-option value="EXPENSE">Despesa</mat-option>
        <mat-option value="INCOME">Receita</mat-option>
      </mat-select>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Status</mat-label>
      <mat-select formControlName="status">
        <mat-option value="PENDING">Pendente</mat-option>
        <mat-option value="PAID">Pago</mat-option>
        <mat-option value="CANCELLED">Cancelado</mat-option>
      </mat-select>
    </mat-form-field>
  </div>

  <div class="form-row form-row-cols">
    <mat-form-field appearance="outline">
      <mat-label>Nº de Parcelas</mat-label>
      <input matInput type="number" formControlName="totalInstallments" min="1" max="48" />
      <mat-hint>1 = à vista. Máx: 48x</mat-hint>
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Categoria (opcional)</mat-label>
      <mat-select formControlName="categoryId">
        <mat-option [value]="null">Nenhuma</mat-option>
        @for (cat of categories(); track cat.id) {
          <mat-option [value]="cat.id">
            <span [style.padding-left.px]="cat.level * 20">
              {{ cat.level > 0 ? '↳ ' : '' }}{{ cat.name }}
            </span>
          </mat-option>
        }
      </mat-select>
    </mat-form-field>
  </div>

  <div class="form-row">
    <mat-form-field appearance="outline" class="field-full">
      <mat-label>Conta *</mat-label>
      <mat-select formControlName="accountId">
        @for (a of accounts(); track a.id) {
          <mat-option [value]="a.id">{{ a.name }}</mat-option>
        }
      </mat-select>
    </mat-form-field>
  </div>
}
```

- [ ] **Step 10.4: Adicionar campos de transferência após o bloco anterior**

```html
@if (mode() === 'TRANSFER') {
  <div class="form-row form-row-cols">
    <mat-form-field appearance="outline">
      <mat-label>Conta de origem *</mat-label>
      <mat-select formControlName="fromAccountId">
        @for (a of accounts(); track a.id) {
          <mat-option [value]="a.id">{{ a.name }}</mat-option>
        }
      </mat-select>
      @if (form.controls.fromAccountId.hasError('required')) {
        <mat-error>Conta de origem obrigatória</mat-error>
      }
    </mat-form-field>

    <mat-form-field appearance="outline">
      <mat-label>Conta de destino *</mat-label>
      <mat-select formControlName="toAccountId">
        @for (a of accounts(); track a.id) {
          <mat-option [value]="a.id">{{ a.name }}</mat-option>
        }
      </mat-select>
      @if (form.controls.toAccountId.hasError('required')) {
        <mat-error>Conta de destino obrigatória</mat-error>
      }
    </mat-form-field>
  </div>

  @if (form.hasError('sameAccount') && (form.controls.fromAccountId.dirty || form.controls.toAccountId.dirty)) {
    <div class="form-row">
      <p class="cross-field-error">As contas de origem e destino devem ser diferentes</p>
    </div>
  }
}
```

- [ ] **Step 10.5: Tornar o campo descrição opcional no modo TRANSFER**

No bloco de descrição existente, tornar a validação `required` visualmente opcional. No template, adicionar uma `mat-label` condicional:

```html
<mat-label>{{ mode() === 'TRANSFER' ? 'Descrição (opcional)' : 'Descrição' }}</mat-label>
```

- [ ] **Step 10.6: Adicionar estilos ao SCSS**

No arquivo `transaction-form.scss`, adicionar ao final:

```scss
.mode-toggle-row {
  display: flex;
  justify-content: center;
  margin-bottom: 1.5rem;

  mat-button-toggle-group {
    border-radius: 8px;
  }
}

.cross-field-error {
  color: var(--mat-form-field-error-text-color, #b00020);
  font-size: 0.75rem;
  margin: 0;
}
```

- [ ] **Step 10.7: Commit**

```bash
git add frontend/src/app/features/transaction/transaction-form/transaction-form.html \
        frontend/src/app/features/transaction/transaction-form/transaction-form.scss
git commit -m "feat(form): template com toggle de modo, campos condicionais e estilos"
```

---

### Tarefa 11: Atualizar TransactionList

**Files:**
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`
- Modify: `frontend/src/app/features/transaction/transaction-list/transaction-list.html`

- [ ] **Step 11.1: Injetar TransfersService no TransactionList**

```typescript
import { TransfersService } from '../../../core/api/transfers/transfers.service';
// ...
private transferService = inject(TransfersService);
```

- [ ] **Step 11.2: Atualizar typeLabel para usar transferId**

Substituir o método `typeLabel` por:

```typescript
typeLabel(t: TransactionResponseDTO): string {
  if (t.transferId) return 'Transferência';
  const labels: Record<string, string> = { INCOME: 'Receita', EXPENSE: 'Despesa' };
  return labels[t.type ?? ''] ?? (t.type ?? '');
}
```

- [ ] **Step 11.3: Atualizar onDelete para exclusão atômica**

Substituir o método `onDelete` por:

```typescript
onDelete(t: TransactionResponseDTO): void {
  const isTransfer = !!t.transferId;
  const dialogRef = this.dialog.open(ConfirmationDialogComponent, {
    width: '400px',
    data: {
      title: isTransfer ? 'Excluir Transferência' : 'Excluir Transação',
      message: isTransfer
        ? 'Deseja excluir esta transferência? Os dois lançamentos serão removidos. Esta ação não pode ser desfeita.'
        : `Deseja excluir "${t.description}"? Esta ação não pode ser desfeita.`,
      confirmText: 'Sim, excluir'
    }
  });

  dialogRef.afterClosed().subscribe(confirmed => {
    if (confirmed !== true) return;

    if (isTransfer) {
      this.transferService.deleteTransfer(t.transferId!).subscribe({
        next: () => {
          this.snackBar.open('Transferência excluída.', 'OK', { duration: 3000 });
          this.loadTransactions();
        },
        error: () => this.snackBar.open('Erro ao excluir transferência.', 'Fechar', { duration: 5000 })
      });
    } else {
      this.service.deleteTransaction(t.id).subscribe({
        next: () => {
          this.snackBar.open('Transação excluída.', 'OK', { duration: 3000 });
          this.loadTransactions();
        },
        error: () => this.snackBar.open('Erro ao excluir transação.', 'Fechar', { duration: 5000 })
      });
    }
  });
}
```

- [ ] **Step 11.4: Atualizar template — coluna tipo e botão editar**

Na coluna `type`, substituir a chamada `typeLabel(t.type)` por `typeLabel(t)`:

```html
<span [class]="'type-badge type-' + (t.transferId ? 'transfer' : (t.type ?? '').toLowerCase())">
  {{ typeLabel(t) }}
</span>
```

Na coluna `actions`, substituir o botão editar por:

```html
<button mat-icon-button color="primary"
        (click)="onEdit(t)"
        [disabled]="!!t.transferId"
        [matTooltip]="t.transferId ? 'Transferências não podem ser editadas' : 'Editar'">
  <mat-icon>edit</mat-icon>
</button>
```

- [ ] **Step 11.5: Verificar compilação TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Expected: sem erros.

- [ ] **Step 11.6: Commit final do frontend**

```bash
git add frontend/src/app/features/transaction/transaction-list/
git commit -m "feat(list): exclusão atômica de transferência e botão editar desabilitado"
```

---

## Merge em develop

Após ambos os agentes concluírem:

```bash
git checkout develop
git merge feat/transfer-backend --no-ff -m "feat: implementa endpoint de transferência entre contas (backend)"
git merge feat/transfer-frontend --no-ff -m "feat: implementa formulário de transferência no Angular (frontend)"
```

> **Conflito esperado em `openapi.yaml`**: as alterações são puramente aditivas. Resolver mantendo os novos blocos de ambas as branches (TransferRequest, TransferResponse, /api/transfers e /api/transfers/{transferId}).

Após o merge, rodar os testes completos:

```bash
cd backend && ./mvnw test -q
cd frontend && npm test
```
