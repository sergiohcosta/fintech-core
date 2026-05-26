# OpenAPI Adoption (Spec-First) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adotar OpenAPI spec-first no projeto Fintech SaaS — `api-spec/openapi.yaml` como fonte da verdade, gerando interfaces Spring no backend e services Angular no frontend.

**Architecture:** O arquivo `api-spec/openapi.yaml` define todos os contratos. O `openapi-generator-maven-plugin` gera interfaces Spring (fase `generate-sources`); controllers existentes implementam essas interfaces. O Orval CLI gera services Angular tipados em `core/api/`; services manuais em `core/services/` e modelos em `core/models/` são removidos.

**Tech Stack:** `openapi-generator-maven-plugin 7.4.0`, `springdoc-openapi-starter-webmvc-ui 2.6.0`, `orval 7.x`, Spring Boot 4.0.1, Angular 21 Zoneless.

**Spec:** `docs/superpowers/specs/2026-05-26-openapi-adoption-design.md`

---

## Mapa de Arquivos

| Ação | Arquivo |
|---|---|
| Criar | `api-spec/openapi.yaml` |
| Criar | `backend/src/main/java/com/fintech/api/dto/LoginResponseDTO.java` |
| Criar | `backend/src/main/java/com/fintech/api/dto/RegisterResponseDTO.java` |
| Criar | `frontend/orval.config.ts` |
| Modificar | `backend/pom.xml` |
| Modificar | `backend/src/main/resources/application.properties` |
| Modificar | `backend/src/main/java/com/fintech/api/controller/AuthController.java` |
| Modificar | `backend/src/main/java/com/fintech/api/controller/TransactionController.java` |
| Modificar | `backend/src/main/java/com/fintech/api/controller/CategoryController.java` |
| Modificar | `backend/src/main/java/com/fintech/api/controller/DashboardController.java` |
| Modificar | `backend/src/main/java/com/fintech/api/controller/CreditCardController.java` |
| Modificar | `frontend/package.json` |
| Modificar | `frontend/src/app/features/transaction/transaction-list/transaction-list.ts` |
| Modificar | `frontend/src/app/features/transaction/transaction-form/transaction-form.ts` |
| Modificar | `frontend/src/app/features/category/category-list/category-list.ts` |
| Modificar | `frontend/src/app/features/category/category-form/category-form.ts` |
| Modificar | `frontend/src/app/features/dashboard/dashboard.ts` |
| Modificar | `frontend/src/app/features/credit-card/components/card-list.ts` |
| Modificar | `frontend/src/app/features/credit-card/components/card-form/card-form.ts` |
| Deletar | `frontend/src/app/core/services/transaction.ts` |
| Deletar | `frontend/src/app/core/services/category.ts` |
| Deletar | `frontend/src/app/core/services/dashboard.ts` |
| Deletar | `frontend/src/app/core/services/credit-card.ts` |
| Deletar | `frontend/src/app/core/models/transaction.ts` |
| Deletar | `frontend/src/app/core/models/category.ts` |
| Deletar | `frontend/src/app/core/models/dashboard.ts` |
| Deletar | `frontend/src/app/core/models/credit-card.ts` |
| Deletar | `frontend/src/app/core/models/brand.enum.ts` |
| Criar | `.gitattributes` |

**Nota sobre `core/services/auth.ts`:** este service gerencia estado (token em localStorage, signal `currentUser`, navegação via Router) além de HTTP — não é substituído pelo Orval. Continua existindo. O spec cobre os endpoints de auth para documentação; o service gerado `core/api/auth.service.ts` é deletado após a geração.

---

## Task 1: Criar `api-spec/openapi.yaml`

**Files:**
- Criar: `api-spec/openapi.yaml`

- [ ] **Step 1: Criar o diretório e o arquivo do spec**

```bash
mkdir -p api-spec
```

Criar `api-spec/openapi.yaml` com o conteúdo completo:

```yaml
openapi: 3.0.3
info:
  title: Fintech SaaS API
  version: 1.0.0
  description: API de gestão financeira multi-tenant.

servers:
  - url: http://localhost:8080
    description: Desenvolvimento local

security:
  - bearerAuth: []

components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT

  schemas:

    # --- Enums ---

    TransactionType:
      type: string
      enum: [INCOME, EXPENSE, TRANSFER]

    TransactionStatus:
      type: string
      enum: [PENDING, PAID, CANCELLED]

    CardBrand:
      type: string
      enum: [VISA, MASTERCARD, ELO, AMEX, HIPERCARD, OTHER]

    # --- Auth ---

    LoginDTO:
      type: object
      required: [email, password]
      properties:
        email:
          type: string
        password:
          type: string

    LoginResponseDTO:
      type: object
      properties:
        token:
          type: string

    TenantRegistrationDTO:
      type: object
      required: [name, adminName, adminEmail, password]
      properties:
        name:
          type: string
        document:
          type: string
          nullable: true
        adminName:
          type: string
        adminEmail:
          type: string
          format: email
        password:
          type: string

    RegisterResponseDTO:
      type: object
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string

    # --- Categories ---

    CategoryCreateDTO:
      type: object
      required: [name, icon, color]
      properties:
        name:
          type: string
        icon:
          type: string
        color:
          type: string
        parentId:
          type: string
          format: uuid
          nullable: true

    CategoryResponseDTO:
      type: object
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string
        icon:
          type: string
        color:
          type: string
        parentId:
          type: string
          format: uuid
          nullable: true
        children:
          type: array
          items:
            $ref: '#/components/schemas/CategoryResponseDTO'

    # --- Transactions ---

    TransactionRequestDTO:
      type: object
      required: [description, amount, date, type]
      properties:
        description:
          type: string
        amount:
          type: number
          format: double
          minimum: 0.01
        date:
          type: string
          format: date
        type:
          $ref: '#/components/schemas/TransactionType'
        status:
          $ref: '#/components/schemas/TransactionStatus'
          nullable: true
        totalInstallments:
          type: integer
          nullable: true
        categoryId:
          type: string
          format: uuid
          nullable: true
        creditCardId:
          type: string
          format: uuid
          nullable: true

    TransactionUpdateDTO:
      type: object
      properties:
        description:
          type: string
          nullable: true
        amount:
          type: number
          format: double
          minimum: 0.01
          nullable: true
        date:
          type: string
          format: date
          nullable: true
        type:
          $ref: '#/components/schemas/TransactionType'
          nullable: true
        status:
          $ref: '#/components/schemas/TransactionStatus'
          nullable: true
        categoryId:
          type: string
          format: uuid
          nullable: true
        creditCardId:
          type: string
          format: uuid
          nullable: true

    TransactionResponseDTO:
      type: object
      properties:
        id:
          type: string
          format: uuid
        description:
          type: string
        amount:
          type: number
          format: double
        date:
          type: string
          format: date
        type:
          $ref: '#/components/schemas/TransactionType'
        status:
          $ref: '#/components/schemas/TransactionStatus'
        installmentLabel:
          type: string
          nullable: true
        categoryName:
          type: string
          nullable: true
        creditCardName:
          type: string
          nullable: true

    # --- Dashboard ---

    DashboardSummaryDTO:
      type: object
      properties:
        period:
          type: string
          example: "2026-05"
        totalIncome:
          type: number
          format: double
        totalExpense:
          type: number
          format: double
        balance:
          type: number
          format: double

    # --- Credit Cards ---

    CreateCreditCardDTO:
      type: object
      required: [name, brand, limitAmount, closingDay, dueDay]
      properties:
        name:
          type: string
        brand:
          $ref: '#/components/schemas/CardBrand'
        color:
          type: string
          pattern: '^#([A-Fa-f0-9]{6})$'
          nullable: true
        lastFourDigits:
          type: string
          minLength: 4
          maxLength: 4
          nullable: true
        limitAmount:
          type: number
          format: double
          minimum: 0.01
        closingDay:
          type: integer
          minimum: 1
          maximum: 31
        dueDay:
          type: integer
          minimum: 1
          maximum: 31

    CreditCardResponseDTO:
      type: object
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string
        brand:
          $ref: '#/components/schemas/CardBrand'
        color:
          type: string
          nullable: true
        lastFourDigits:
          type: string
          nullable: true
        limitAmount:
          type: number
          format: double
        closingDay:
          type: integer
        dueDay:
          type: integer

    UpdateCreditCardDTO:
      type: object
      properties:
        name:
          type: string
          nullable: true
        color:
          type: string
          nullable: true
        lastFourDigits:
          type: string
          nullable: true
        limitAmount:
          type: number
          format: double
          nullable: true
        closingDay:
          type: integer
          nullable: true
        dueDay:
          type: integer
          nullable: true

paths:

  # --- Auth (sem autenticação) ---

  /auth/login:
    post:
      tags: [auth]
      operationId: login
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/LoginDTO'
      responses:
        '200':
          description: Token JWT gerado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/LoginResponseDTO'
        '401':
          description: Credenciais inválidas

  /auth/register:
    post:
      tags: [auth]
      operationId: register
      security: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TenantRegistrationDTO'
      responses:
        '201':
          description: Tenant e usuário admin criados
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/RegisterResponseDTO'

  # --- Categories ---

  /api/categories:
    get:
      tags: [categories]
      operationId: listCategories
      responses:
        '200':
          description: Lista de categorias raiz com filhos aninhados
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/CategoryResponseDTO'
    post:
      tags: [categories]
      operationId: createCategory
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CategoryCreateDTO'
      responses:
        '201':
          description: Categoria criada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CategoryResponseDTO'

  /api/categories/{id}:
    get:
      tags: [categories]
      operationId: getCategory
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Categoria encontrada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CategoryResponseDTO'
        '404':
          description: Categoria não encontrada
    put:
      tags: [categories]
      operationId: updateCategory
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CategoryCreateDTO'
      responses:
        '200':
          description: Categoria atualizada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CategoryResponseDTO'
        '404':
          description: Categoria não encontrada
    delete:
      tags: [categories]
      operationId: deleteCategory
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '204':
          description: Categoria removida
        '404':
          description: Categoria não encontrada

  # --- Transactions ---

  /api/transactions:
    get:
      tags: [transactions]
      operationId: listTransactions
      responses:
        '200':
          description: Lista de transações do tenant
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/TransactionResponseDTO'
    post:
      tags: [transactions]
      operationId: createTransaction
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TransactionRequestDTO'
      responses:
        '201':
          description: Transação(ões) criada(s) — múltiplas em caso de parcelamento
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/TransactionResponseDTO'

  /api/transactions/{id}:
    get:
      tags: [transactions]
      operationId: getTransaction
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Transação encontrada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TransactionResponseDTO'
        '404':
          description: Transação não encontrada
    put:
      tags: [transactions]
      operationId: updateTransaction
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/TransactionUpdateDTO'
      responses:
        '200':
          description: Transação atualizada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/TransactionResponseDTO'
        '404':
          description: Transação não encontrada
    delete:
      tags: [transactions]
      operationId: deleteTransaction
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '204':
          description: Transação removida
        '404':
          description: Transação não encontrada

  # --- Dashboard ---

  /api/dashboard/summary:
    get:
      tags: [dashboard]
      operationId: getDashboardSummary
      parameters:
        - name: month
          in: query
          required: true
          schema:
            type: string
          example: "2026-05"
          description: Mês no formato yyyy-MM
      responses:
        '200':
          description: Resumo financeiro do período
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/DashboardSummaryDTO'
        '400':
          description: Formato do parâmetro month inválido

  # --- Credit Cards ---

  /api/credit-cards:
    get:
      tags: [credit-cards]
      operationId: listCreditCards
      responses:
        '200':
          description: Lista de cartões do tenant
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/CreditCardResponseDTO'
    post:
      tags: [credit-cards]
      operationId: createCreditCard
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateCreditCardDTO'
      responses:
        '201':
          description: Cartão criado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CreditCardResponseDTO'

  /api/credit-cards/{id}:
    get:
      tags: [credit-cards]
      operationId: getCreditCard
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: Cartão encontrado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CreditCardResponseDTO'
        '404':
          description: Cartão não encontrado
    put:
      tags: [credit-cards]
      operationId: updateCreditCard
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/UpdateCreditCardDTO'
      responses:
        '200':
          description: Cartão atualizado
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CreditCardResponseDTO'
        '404':
          description: Cartão não encontrado
    delete:
      tags: [credit-cards]
      operationId: deleteCreditCard
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '204':
          description: Cartão removido
        '404':
          description: Cartão não encontrado
```

- [ ] **Step 2: Verificar que o YAML é válido com o CLI do Orval (instalação temporária)**

```bash
cd frontend && npx --yes orval --version
```

Expected: versão impressa sem erros (confirma que o binário funciona).

- [ ] **Step 3: Commit**

```bash
git add api-spec/openapi.yaml
git commit -m "feat(openapi): adiciona spec completo de todos os endpoints"
```

---

## Task 2: Configurar Backend — Plugin + springdoc

**Files:**
- Modificar: `backend/pom.xml`
- Modificar: `backend/src/main/resources/application.properties`

**Contexto para o desenvolvedor:** O `openapi-generator-maven-plugin` roda na fase `generate-sources` (antes de `compile`) e escreve em `target/generated-sources/openapi/`. O Maven adiciona esse diretório ao classpath automaticamente. O `interfaceOnly=true` faz com que apenas interfaces sejam geradas — a implementação continua sendo sua. O `generateModels=false` + `importMappings` instrui o gerador a não criar classes de modelo próprias, usando as DTOs existentes em vez disso.

- [ ] **Step 1: Adicionar dependências e plugin no `backend/pom.xml`**

Adicionar dentro de `<dependencies>` (após as dependências existentes):

```xml
<!-- Swagger UI via springdoc — serve o spec estático em /swagger-ui.html -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

Adicionar dentro de `<plugins>` (após os plugins existentes):

```xml
<plugin>
    <groupId>org.openapitools</groupId>
    <artifactId>openapi-generator-maven-plugin</artifactId>
    <version>7.4.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <generatorName>spring</generatorName>
                <inputSpec>${project.basedir}/../api-spec/openapi.yaml</inputSpec>
                <output>${project.build.directory}/generated-sources/openapi</output>
                <generateModels>false</generateModels>
                <apiPackage>com.fintech.api.openapi</apiPackage>
                <importMappings>
                    <importMapping>LoginDTO=com.fintech.api.dto.LoginDTO</importMapping>
                    <importMapping>LoginResponseDTO=com.fintech.api.dto.LoginResponseDTO</importMapping>
                    <importMapping>TenantRegistrationDTO=com.fintech.api.dto.TenantRegistrationDTO</importMapping>
                    <importMapping>RegisterResponseDTO=com.fintech.api.dto.RegisterResponseDTO</importMapping>
                    <importMapping>CategoryCreateDTO=com.fintech.api.dto.category.CategoryCreateDTO</importMapping>
                    <importMapping>CategoryResponseDTO=com.fintech.api.dto.category.CategoryResponseDTO</importMapping>
                    <importMapping>TransactionRequestDTO=com.fintech.api.dto.transaction.TransactionRequestDTO</importMapping>
                    <importMapping>TransactionUpdateDTO=com.fintech.api.dto.transaction.TransactionUpdateDTO</importMapping>
                    <importMapping>TransactionResponseDTO=com.fintech.api.dto.transaction.TransactionResponseDTO</importMapping>
                    <importMapping>DashboardSummaryDTO=com.fintech.api.dto.dashboard.DashboardSummaryDTO</importMapping>
                    <importMapping>CreateCreditCardDTO=com.fintech.api.dto.CreateCreditCardDTO</importMapping>
                    <importMapping>CreditCardResponseDTO=com.fintech.api.dto.CreditCardResponseDTO</importMapping>
                    <importMapping>UpdateCreditCardDTO=com.fintech.api.dto.UpdateCreditCardDTO</importMapping>
                    <importMapping>TransactionType=com.fintech.api.domain.enums.TransactionType</importMapping>
                    <importMapping>TransactionStatus=com.fintech.api.domain.enums.TransactionStatus</importMapping>
                    <importMapping>CardBrand=com.fintech.api.domain.enums.CardBrand</importMapping>
                </importMappings>
                <configOptions>
                    <interfaceOnly>true</interfaceOnly>
                    <useSpringBoot3>true</useSpringBoot3>
                    <useTags>true</useTags>
                    <useJakartaEe>true</useJakartaEe>
                    <skipDefaultInterface>true</skipDefaultInterface>
                </configOptions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 2: Configurar springdoc para servir o spec estático**

Em `backend/src/main/resources/application.properties`, adicionar:

```properties
# Desabilita geração automática de spec pelo springdoc (usamos o spec estático)
springdoc.api-docs.enabled=false
# Aponta o Swagger UI para o arquivo estático servido por Spring Boot
springdoc.swagger-ui.url=/openapi.yaml
springdoc.swagger-ui.path=/swagger-ui.html
```

- [ ] **Step 3: Copiar o spec como recurso estático**

```bash
cp api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
```

**Atenção:** este arquivo copiado é um artefato derivado. Quando o spec for atualizado, copiar novamente. A cópia em `static/` é o que o Spring Boot serve; a fonte da verdade é `api-spec/openapi.yaml`.

- [ ] **Step 4: Executar primeira compilação — verificar que o plugin funciona**

```bash
cd backend && ./mvnw compile
```

Expected: `BUILD SUCCESS`. O diretório `target/generated-sources/openapi/com/fintech/api/openapi/` deve existir com interfaces como `AuthApi.java`, `TransactionsApi.java` etc.

Se houver `BUILD FAILURE` com erro de `importMapping` ou tipo não encontrado, ler a mensagem de erro e ajustar o `importMapping` correspondente no `pom.xml`.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.properties backend/src/main/resources/static/openapi.yaml
git commit -m "feat(openapi): adiciona plugin de geração de interfaces Spring e springdoc"
```

---

## Task 3: Preparar DTOs ausentes no Backend

**Files:**
- Criar: `backend/src/main/java/com/fintech/api/dto/LoginResponseDTO.java`
- Criar: `backend/src/main/java/com/fintech/api/dto/RegisterResponseDTO.java`
- Modificar: `backend/src/main/java/com/fintech/api/controller/AuthController.java`

**Contexto:** O `AuthController` usa um record `LoginResponseDTO` definido inline como classe aninhada. A interface gerada `AuthApi` precisará importar `com.fintech.api.dto.LoginResponseDTO` (conforme `importMapping`). Além disso, `/auth/register` retornava `ResponseEntity<Tenant>` expondo a entidade JPA diretamente — violação das convenções do projeto. Criamos `RegisterResponseDTO` para corrigir isso.

- [ ] **Step 1: Criar `LoginResponseDTO.java`**

Criar `backend/src/main/java/com/fintech/api/dto/LoginResponseDTO.java`:

```java
package com.fintech.api.dto;

public record LoginResponseDTO(String token) {}
```

- [ ] **Step 2: Criar `RegisterResponseDTO.java`**

Criar `backend/src/main/java/com/fintech/api/dto/RegisterResponseDTO.java`:

```java
package com.fintech.api.dto;

import java.util.UUID;

public record RegisterResponseDTO(UUID id, String name) {}
```

- [ ] **Step 3: Atualizar `AuthController` para usar os DTOs extraídos**

Substituir o conteúdo de `backend/src/main/java/com/fintech/api/controller/AuthController.java`:

```java
package com.fintech.api.controller;

import com.fintech.api.config.TokenService;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.dto.LoginDTO;
import com.fintech.api.dto.LoginResponseDTO;
import com.fintech.api.dto.RegisterResponseDTO;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.repository.UserRepository;
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
@CrossOrigin(origins = "*")
public class AuthController {

    private final TenantRegistrationService registrationService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    @PostMapping("/register")
    public ResponseEntity<RegisterResponseDTO> register(@RequestBody @Valid TenantRegistrationDTO dto) {
        Tenant newTenant = registrationService.register(dto);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterResponseDTO(newTenant.getId(), newTenant.getName()));
    }

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
}
```

- [ ] **Step 4: Compilar para verificar sem erros**

```bash
cd backend && ./mvnw compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/LoginResponseDTO.java \
        backend/src/main/java/com/fintech/api/dto/RegisterResponseDTO.java \
        backend/src/main/java/com/fintech/api/controller/AuthController.java
git commit -m "refactor(auth): extrai LoginResponseDTO e cria RegisterResponseDTO — prepara para interfaces OpenAPI"
```

---

## Task 4: Controllers Implementam as Interfaces Geradas

**Files:**
- Modificar: todos os 5 controllers

**Contexto:** Com `implements XxxApi`, cada método do controller deve ter a assinatura exata que a interface gerada declara. Se houver divergência (tipo de retorno, nome de parâmetro, anotação diferente), o compilador quebra — esse é o mecanismo de validação. Corrija no spec (se o contrato estava errado) ou no controller (se a implementação estava errada).

- [ ] **Step 1: `TransactionController` implementa `TransactionsApi`**

Adicionar `implements TransactionsApi` e o import correspondente. A interface gerada fica em `com.fintech.api.openapi.TransactionsApi`.

Substituir o cabeçalho da classe:

```java
import com.fintech.api.openapi.TransactionsApi;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController implements TransactionsApi {
```

- [ ] **Step 2: `CategoryController` implementa `CategoriesApi`**

```java
import com.fintech.api.openapi.CategoriesApi;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CategoryController implements CategoriesApi {
```

- [ ] **Step 3: `AuthController` implementa `AuthApi`**

```java
import com.fintech.api.openapi.AuthApi;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController implements AuthApi {
```

- [ ] **Step 4: `DashboardController` implementa `DashboardApi`**

```java
import com.fintech.api.openapi.DashboardApi;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController implements DashboardApi {
```

- [ ] **Step 5: `CreditCardController` implementa `CreditCardsApi`**

```java
import com.fintech.api.openapi.CreditCardsApi;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/credit-cards")
public class CreditCardController implements CreditCardsApi {
```

- [ ] **Step 6: Compilar — este é o teste de sincronização**

```bash
cd backend && ./mvnw compile
```

Expected: `BUILD SUCCESS`.

Se houver `error: method X in interface Y cannot be applied to given types`, significa divergência entre o spec e o controller. Exemplos comuns:
- Parâmetro `@PathVariable UUID id` vs spec esperando `String id` — ajuste o spec para `type: string, format: uuid` (correto já no spec acima).
- Tipo de retorno `ResponseEntity<List<T>>` no controller vs `ResponseEntity<T>` na interface — ajuste o spec.
- Nome de parâmetro diferente — alinhe os nomes.

**Corrigir spec → recompilar → repetir até `BUILD SUCCESS`.**

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/fintech/api/controller/
git commit -m "feat(openapi): controllers implementam interfaces geradas do spec"
```

---

## Task 5: Subir backend e verificar Swagger UI

**Contexto:** Antes de seguir para o frontend, validar que o backend está íntegro e o Swagger UI funciona.

- [ ] **Step 1: Garantir que o banco está rodando**

```bash
docker compose ps
```

Expected: serviço `postgres` com status `Up`. Se não: `docker compose up -d`.

- [ ] **Step 2: Subir o backend**

```bash
cd backend && ./mvnw spring-boot:run
```

Aguardar a linha:
```
Started FintechApiApplication in X.XXX seconds
```

- [ ] **Step 3: Verificar health**

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

Expected: `{"status":"UP"}`.

- [ ] **Step 4: Abrir Swagger UI**

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/swagger-ui.html
```

Expected: `200` ou `302`. Abrindo em browser: `http://localhost:8080/swagger-ui.html` deve mostrar a interface com todos os endpoints listados.

- [ ] **Step 5: Testar login via Swagger UI**

Na interface Swagger:
1. Expandir `POST /auth/login`
2. Clicar "Try it out"
3. Body: `{"email": "seu@email.com", "password": "sua_senha"}`
4. Execute → Expected: `200` com `{ "token": "eyJ..." }`

- [ ] **Step 6: Parar o backend**

`Ctrl+C` no terminal do backend.

---

## Task 6: Instalar Orval e Criar `orval.config.ts`

**Files:**
- Modificar: `frontend/package.json`
- Criar: `frontend/orval.config.ts`

**Contexto:** O Orval lê o `openapi.yaml` e gera services Angular com `HttpClient`. O `mode: 'tags-split'` cria um arquivo por tag: `transactions.service.ts`, `categories.service.ts`, etc. Os arquivos gerados vão para `src/app/core/api/`.

- [ ] **Step 1: Instalar Orval**

```bash
cd frontend && npm install orval --save-dev
```

Expected: `orval` aparece em `devDependencies` do `package.json`.

- [ ] **Step 2: Adicionar script ao `package.json`**

No objeto `"scripts"` do `frontend/package.json`, adicionar:

```json
"api:generate": "orval --config orval.config.ts"
```

- [ ] **Step 3: Criar `frontend/orval.config.ts`**

```typescript
import { defineConfig } from 'orval';

export default defineConfig({
  fintechApi: {
    input: '../api-spec/openapi.yaml',
    output: {
      mode: 'tags-split',
      target: 'src/app/core/api',
      client: 'angular',
      override: {
        angular: {
          provideIn: 'root',
        },
      },
    },
  },
});
```

- [ ] **Step 4: Commit da configuração (antes de gerar)**

```bash
git add frontend/package.json frontend/package-lock.json frontend/orval.config.ts
git commit -m "feat(openapi): instala Orval e configura geração de client Angular"
```

---

## Task 7: Gerar Código Angular e Inspecionar Output

- [ ] **Step 1: Executar geração**

```bash
cd frontend && npm run api:generate
```

Expected: sem erros. Arquivos criados em `frontend/src/app/core/api/`:
- `auth.service.ts` — gerado mas não será usado por componentes (ver nota no início)
- `categories.service.ts`
- `transactions.service.ts`
- `dashboard.service.ts`
- `credit-cards.service.ts`

Verificar presença:
```bash
ls frontend/src/app/core/api/
```

- [ ] **Step 2: Inspecionar `transactions.service.ts` gerado**

```bash
cat frontend/src/app/core/api/transactions.service.ts
```

Verificar que contém:
- Classe `TransactionsService` com `@Injectable({ providedIn: 'root' })`
- Método `listTransactions(): Observable<TransactionResponseDTO[]>`
- Método `createTransaction(transactionRequestDTO: TransactionRequestDTO): Observable<TransactionResponseDTO[]>`
- Método `getTransaction(id: string): Observable<TransactionResponseDTO>`
- Método `updateTransaction(id: string, transactionUpdateDTO: TransactionUpdateDTO): Observable<TransactionResponseDTO>`
- Método `deleteTransaction(id: string): Observable<void>`

- [ ] **Step 3: Deletar o `auth.service.ts` gerado**

O serviço de auth tem estado (token, signals, router) que não é gerado — `core/services/auth.ts` continua sendo a implementação de auth.

```bash
rm frontend/src/app/core/api/auth.service.ts
```

- [ ] **Step 4: Verificar tipagem TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Neste ponto haverá erros de importação nos componentes (ainda apontam para `core/services/`). Isso é esperado — será resolvido na próxima task.

- [ ] **Step 5: Commit do código gerado**

```bash
git add frontend/src/app/core/api/
git commit -m "feat(openapi): gera services Angular via Orval a partir do spec"
```

---

## Task 8: Substituir Imports nos Componentes

**Files:**
- Modificar: `transaction-list.ts`, `transaction-form.ts`, `category-list.ts`, `category-form.ts`, `dashboard.ts`, `card-list.ts`, `card-form.ts`

**Contexto:** Os componentes usam os services manuais de `core/services/` e os modelos de `core/models/`. Precisam ser atualizados para `core/api/`. Os nomes das classes de service mudaram de singular para plural (ex: `TransactionService` → `TransactionsService`), e os nomes dos métodos foram padronizados pelos `operationId`s do spec.

**Mapa de imports:**
| Antes | Depois |
|---|---|
| `core/services/transaction` → `TransactionService` | `core/api/transactions.service` → `TransactionsService` |
| `core/services/category` → `CategoryService` | `core/api/categories.service` → `CategoriesService` |
| `core/services/dashboard` → `DashboardService` | `core/api/dashboard.service` → `DashboardService` |
| `core/services/credit-card` → `CreditCardService` | `core/api/credit-cards.service` → `CreditCardsService` |
| `core/models/transaction` → tipos | `core/api/transactions.service` → tipos gerados |
| `core/models/category` → tipos | `core/api/categories.service` → tipos gerados |
| `core/models/dashboard` → tipos | `core/api/dashboard.service` → tipos gerados |
| `core/models/credit-card` → tipos | `core/api/credit-cards.service` → tipos gerados |
| `core/models/brand.enum` → `Brand` | `core/api/credit-cards.service` → `CardBrand` |

**Mapa de métodos:**
| Antes | Depois |
|---|---|
| `transactionService.list()` | `transactionsService.listTransactions()` |
| `transactionService.getById(id)` | `transactionsService.getTransaction(id)` |
| `transactionService.create(dto)` | `transactionsService.createTransaction(dto)` |
| `transactionService.update(id, dto)` | `transactionsService.updateTransaction(id, dto)` |
| `transactionService.delete(id)` | `transactionsService.deleteTransaction(id)` |
| `categoryService.list()` | `categoriesService.listCategories()` |
| `categoryService.getById(id)` | `categoriesService.getCategory(id)` |
| `categoryService.create(dto)` | `categoriesService.createCategory(dto)` |
| `categoryService.update(id, dto)` | `categoriesService.updateCategory(id, dto)` |
| `categoryService.delete(id)` | `categoriesService.deleteCategory(id)` |
| `dashboardService.getSummary(month)` | `dashboardService.getDashboardSummary(month)` |
| `creditCardService.list()` | `creditCardsService.listCreditCards()` |
| `creditCardService.getById(id)` | `creditCardsService.getCreditCard(id)` |
| `creditCardService.create(dto)` | `creditCardsService.createCreditCard(dto)` |
| `creditCardService.update(id, dto)` | `creditCardsService.updateCreditCard(id, dto)` |
| `creditCardService.delete(id)` | `creditCardsService.deleteCreditCard(id)` |

**Nota:** Os nomes exatos dos métodos gerados dependem dos `operationId`s definidos no spec (Task 1). Se o Orval gerou nomes diferentes do esperado, verificar `cat frontend/src/app/core/api/*.service.ts` para confirmar os nomes reais antes de editar os componentes.

- [ ] **Step 1: Atualizar `transaction-list.ts`**

Em `frontend/src/app/features/transaction/transaction-list/transaction-list.ts`:

Substituir:
```typescript
import { TransactionService } from '../../../core/services/transaction';
import { TransactionResponse } from '../../../core/models/transaction';
```
Por:
```typescript
import { TransactionsService, TransactionResponseDTO } from '../../../core/api/transactions.service';
```

Renomear injeção:
```typescript
// Antes:
private service = inject(TransactionService);
// Depois:
private service = inject(TransactionsService);
```

Renomear tipo do signal:
```typescript
// Antes:
transactions = signal<TransactionResponse[]>([]);
// Depois:
transactions = signal<TransactionResponseDTO[]>([]);
```

Atualizar chamadas de método conforme o mapa acima.

- [ ] **Step 2: Atualizar `transaction-form.ts`**

Em `frontend/src/app/features/transaction/transaction-form/transaction-form.ts`:

Substituir imports de `core/services/transaction` e `core/models/transaction` pelos equivalentes de `core/api/transactions.service`. Substituir imports de `core/services/category` por `core/api/categories.service`. Atualizar métodos conforme o mapa.

- [ ] **Step 3: Atualizar `category-list.ts`**

Em `frontend/src/app/features/category/category-list/category-list.ts`:

Substituir `CategoryService` por `CategoriesService` de `core/api/categories.service`. Substituir `CategoryModel` por `CategoryResponseDTO`. Atualizar métodos conforme o mapa.

- [ ] **Step 4: Atualizar `category-form.ts`**

Em `frontend/src/app/features/category/category-form/category-form.ts`:

Substituir `CategoryService` por `CategoriesService`. Substituir `CategoryCreate` por `CategoryCreateDTO`. Atualizar métodos conforme o mapa.

- [ ] **Step 5: Atualizar `dashboard.ts`**

Em `frontend/src/app/features/dashboard/dashboard.ts`:

Substituir:
```typescript
import { DashboardService } from '../../core/services/dashboard';
import { TransactionService } from '../../core/services/transaction';
import { DashboardSummary } from '../../core/models/dashboard';
```
Por:
```typescript
import { DashboardService, DashboardSummaryDTO } from '../../core/api/dashboard.service';
import { TransactionsService, TransactionResponseDTO } from '../../core/api/transactions.service';
```

Substituir as injeções no corpo da classe:
```typescript
// Antes:
private dashboardService = inject(DashboardService);
private transactionService = inject(TransactionService);
// Depois:
private dashboardService = inject(DashboardService);       // mesmo nome, novo import
private transactionsService = inject(TransactionsService); // pluralizado
```

Atualizar as chamadas de método (o padrão `toObservable + switchMap + toSignal` permanece igual):
```typescript
// Antes:
readonly summary = toSignal(
  toObservable(this.selectedMonth).pipe(
    switchMap(month => this.dashboardService.getSummary(month))
  )
);

readonly recentTransactions = toSignal(
  this.transactionService.list()
);
// Depois:
readonly summary = toSignal(
  toObservable(this.selectedMonth).pipe(
    switchMap(month => this.dashboardService.getDashboardSummary(month))
  )
);

readonly recentTransactions = toSignal(
  this.transactionsService.listTransactions()
);
```

Atualizar o tipo do signal de `DashboardSummary` para `DashboardSummaryDTO`:
```typescript
// Antes (se tiver tipagem explícita):
summary = signal<DashboardSummary | undefined>(undefined);
// Depois:
summary = signal<DashboardSummaryDTO | undefined>(undefined);
```

- [ ] **Step 6: Verificar nome da classe gerada para credit-cards e atualizar `card-list.ts` e `card-form.ts`**

Primeiro, confirmar o nome exato da classe gerada:
```bash
grep "export class" frontend/src/app/core/api/credit-cards.service.ts
```
Expected: `export class CreditCardsService` (Orval camelCase do tag `credit-cards`).

Se o nome for diferente, usar o nome real nos passos abaixo.

Em `card-list.ts` e `card-form.ts`, substituir:
- `CreditCardService` → `CreditCardsService` (de `core/api/credit-cards.service`)
- `CreditCardModel` → `CreditCardResponseDTO`
- `CreditCardCreate` → `CreateCreditCardDTO`
- `Brand` / `BRAND_OPTIONS` → `CardBrand` e `Object.values(CardBrand)`
- `creditCardService.list()` → `creditCardsService.listCreditCards()`
- `creditCardService.getById(id)` → `creditCardsService.getCreditCard(id)`
- `creditCardService.create(dto)` → `creditCardsService.createCreditCard(dto)`
- `creditCardService.update(id, dto)` → `creditCardsService.updateCreditCard(id, dto)`
- `creditCardService.delete(id)` → `creditCardsService.deleteCreditCard(id)`

- [ ] **Step 7: Verificar tipagem TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Expected: zero erros. Se houver erros de tipo, ler a mensagem e corrigir o import ou cast correspondente.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/features/
git commit -m "feat(openapi): migra componentes Angular para services gerados pelo Orval"
```

---

## Task 9: Remover Arquivos Manuais e Finalizar

**Files:**
- Deletar: services manuais em `core/services/`
- Deletar: modelos manuais em `core/models/`
- Criar: `.gitattributes`

- [ ] **Step 1: Deletar services manuais substituídos**

```bash
rm frontend/src/app/core/services/transaction.ts \
   frontend/src/app/core/services/category.ts \
   frontend/src/app/core/services/dashboard.ts \
   frontend/src/app/core/services/credit-card.ts
```

**Manter:** `frontend/src/app/core/services/auth.ts` — não foi substituído.

- [ ] **Step 2: Deletar modelos manuais substituídos**

```bash
rm frontend/src/app/core/models/transaction.ts \
   frontend/src/app/core/models/category.ts \
   frontend/src/app/core/models/dashboard.ts \
   frontend/src/app/core/models/credit-card.ts \
   frontend/src/app/core/models/brand.enum.ts
```

- [ ] **Step 3: Verificar TypeScript após remoções**

```bash
cd frontend && npx tsc --noEmit
```

Expected: zero erros. Se o compilador reclamar de algo que ainda importa os arquivos deletados, corrigir o import.

- [ ] **Step 4: Criar `.gitattributes` para marcar código gerado**

Criar `.gitattributes` na raiz do projeto:

```
# Arquivos gerados pelo Orval — diferença normal em PRs
frontend/src/app/core/api/*.service.ts linguist-generated=true
frontend/src/app/core/api/*.ts linguist-generated=true
```

- [ ] **Step 5: Subir o frontend e verificar os fluxos principais**

```bash
cd frontend && npm start
```

Verificar no browser (`http://localhost:4200`):
1. Login funciona
2. Listagem de transações carrega
3. Criar transação funciona
4. Editar transação funciona
5. Listagem de categorias carrega
6. Dashboard carrega com valores corretos
7. Listagem de cartões carrega

- [ ] **Step 6: Commit final**

```bash
git add -A
git commit -m "feat(openapi): remove services e modelos manuais — migração spec-first concluída"
```

---

## Verificação Final

Após todos os tasks:

```bash
# Backend compila limpo
cd backend && ./mvnw compile
# Expected: BUILD SUCCESS

# Frontend compila limpo
cd frontend && npx tsc --noEmit
# Expected: zero erros

# Swagger UI acessível (com backend rodando)
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/swagger-ui.html
# Expected: 200 ou 302

# Spec na raiz está em sincronia com a cópia estática
diff api-spec/openapi.yaml backend/src/main/resources/static/openapi.yaml
# Expected: sem diferenças
```
