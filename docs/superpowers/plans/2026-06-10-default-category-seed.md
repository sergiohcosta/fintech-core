# Default Category Seed + TaxonomyCode — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ao registrar um novo tenant, criar automaticamente 66 categorias padrão (14 raízes + 52 subcategorias) com um campo `taxonomy_code` estável para benchmarking semântico futuro.

**Architecture:** O `TenantRegistrationService` delega a criação das categorias para um `DefaultCategorySeeder` injetado, que persiste as 66 entidades em batch único dentro da mesma transação do registro. A árvore de templates é definida como constante estática imutável usando records Java. Categorias criadas manualmente pelo tenant ficam com `taxonomy_code = null`, diferenciando-as das categorias padrão.

**Tech Stack:** Java 21, Spring Boot, JPA/Hibernate (`saveAll` batch), Flyway (V11), JUnit 5 + Mockito (testes unitários), OpenAPI Generator (codegen após mudança na spec).

---

## Mapa de Arquivos

| Arquivo | Ação | Responsabilidade |
|---------|------|-----------------|
| `backend/src/main/resources/db/migration/V11__category_taxonomy_code.sql` | **criar** | Migration: adiciona coluna `taxonomy_code VARCHAR(50) NULL` |
| `backend/src/main/java/com/fintech/api/domain/category/CategoryTaxonomy.java` | **criar** | Enum com os 66 códigos de taxonomia |
| `backend/src/main/java/com/fintech/api/domain/category/CategoryTemplateNode.java` | **criar** | Record imutável: nó da árvore de template |
| `backend/src/main/java/com/fintech/api/service/DefaultCategorySeeder.java` | **criar** | Service: cria 66 categorias para um tenant em batch |
| `backend/src/main/java/com/fintech/api/domain/category/Category.java` | **modificar** | +campo `taxonomyCode` |
| `backend/src/main/java/com/fintech/api/dto/category/CategoryResponseDTO.java` | **modificar** | +campo `taxonomyCode` no record e em `fromEntity()` |
| `backend/src/main/java/com/fintech/api/service/TenantRegistrationService.java` | **modificar** | Injetar `DefaultCategorySeeder`; chamar após salvar admin user |
| `api-spec/openapi.yaml` | **modificar** | +`taxonomyCode` nullable no schema `CategoryResponseDTO` |
| `backend/src/main/resources/db/seed/V10__seed_dev.sql` | **modificar** | Adicionar `taxonomy_code` nos INSERTs de categorias |
| `backend/src/test/java/com/fintech/api/service/DefaultCategorySeederTest.java` | **criar** | Testes unitários do seeder |
| `backend/src/test/java/com/fintech/api/service/TenantRegistrationServiceTest.java` | **criar** | Testes unitários de integração seeder↔registro |

---

## Task 1: Migration V11 — coluna `taxonomy_code`

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__category_taxonomy_code.sql`

- [ ] **Step 1: Criar a migration**

```sql
-- V11__category_taxonomy_code.sql
ALTER TABLE categories
    ADD COLUMN taxonomy_code VARCHAR(50) NULL;

COMMENT ON COLUMN categories.taxonomy_code IS
    'Código semântico estável para benchmarking cross-tenant. NULL = categoria criada pelo usuário.';
```

> **Por que nullable?** Categorias criadas manualmente pelo tenant não têm código — `null` é o diferenciador entre categorias padrão e customizadas. Sem unique constraint porque dois tenants distintos devem poder ter o mesmo `taxonomy_code` (esse é justamente o objetivo: bases comparáveis entre tenants).

- [ ] **Step 2: Verificar que a migration roda sem erros**

```bash
cd backend && ./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/fintech \
  -Dflyway.user=admin -Dflyway.password=secret
```

Esperado: `Successfully applied 1 migration` (ou equivalente no log do app ao iniciar).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V11__category_taxonomy_code.sql
git commit -m "feat(db): adiciona coluna taxonomy_code em categories (V11)"
```

---

## Task 2: Enum `CategoryTaxonomy` + Record `CategoryTemplateNode`

**Files:**
- Create: `backend/src/main/java/com/fintech/api/domain/category/CategoryTaxonomy.java`
- Create: `backend/src/main/java/com/fintech/api/domain/category/CategoryTemplateNode.java`

- [ ] **Step 1: Criar o enum `CategoryTaxonomy`**

```java
package com.fintech.api.domain.category;

public enum CategoryTaxonomy {
    // Renda
    INCOME, INCOME_SALARY, INCOME_FREELANCE, INCOME_INVESTMENT_RETURNS, INCOME_REIMBURSEMENT,
    // Moradia
    HOUSING, HOUSING_RENT, HOUSING_CONDO, HOUSING_ENERGY, HOUSING_WATER_GAS, HOUSING_INTERNET,
    // Alimentação
    FOOD, FOOD_GROCERY, FOOD_RESTAURANT, FOOD_DELIVERY, FOOD_BAKERY,
    // Transporte
    TRANSPORT, TRANSPORT_FUEL, TRANSPORT_RIDESHARE, TRANSPORT_PUBLIC,
    TRANSPORT_VEHICLE_TAX, TRANSPORT_VEHICLE_MAINTENANCE,
    // Saúde
    HEALTH, HEALTH_PHARMACY, HEALTH_INSURANCE, HEALTH_APPOINTMENTS, HEALTH_FITNESS, HEALTH_MENTAL,
    // Lazer
    LEISURE, LEISURE_ENTERTAINMENT, LEISURE_TRAVEL, LEISURE_HOBBIES, LEISURE_NIGHTLIFE,
    // Educação
    EDUCATION, EDUCATION_COURSES, EDUCATION_BOOKS, EDUCATION_SCHOOL,
    // Vestuário
    CLOTHING, CLOTHING_APPAREL, CLOTHING_ACCESSORIES, CLOTHING_LAUNDRY,
    // Casa & Decoração
    HOME_GOODS, HOME_GOODS_FURNITURE, HOME_GOODS_UTILITIES, HOME_GOODS_MAINTENANCE,
    // Assinaturas
    SUBSCRIPTIONS, SUBSCRIPTIONS_VIDEO, SUBSCRIPTIONS_MUSIC, SUBSCRIPTIONS_GAMING, SUBSCRIPTIONS_SOFTWARE,
    // Cuidados Pessoais
    PERSONAL_CARE, PERSONAL_CARE_BEAUTY, PERSONAL_CARE_HYGIENE, PERSONAL_CARE_WELLNESS,
    // Pets
    PETS, PETS_FOOD, PETS_VET, PETS_GROOMING,
    // Financeiro
    FINANCIAL, FINANCIAL_TAXES, FINANCIAL_BANK_FEES, FINANCIAL_LOANS, FINANCIAL_INSURANCE,
    // Presentes e Doações
    GIFTS, GIFTS_PRESENTS, GIFTS_DONATIONS
}
```

> **Por que enum e não String direto?** O enum centraliza os 66 códigos como fonte da verdade — impossível criar um `DefaultCategorySeeder` com um código digitado errado. No `Category.java` o campo é `String` (não enum) para não quebrar a deserialização se uma migration futura adicionar valores ainda desconhecidos pelo código; o enum existe apenas na camada de aplicação.

- [ ] **Step 2: Criar o record `CategoryTemplateNode`**

```java
package com.fintech.api.domain.category;

import java.util.List;

public record CategoryTemplateNode(
        String name,
        String icon,
        String color,
        CategoryTaxonomy taxonomy,
        List<CategoryTemplateNode> children
) {
    // Construtor conveniente para nós-folha (sem filhos)
    public CategoryTemplateNode(String name, String icon, String color, CategoryTaxonomy taxonomy) {
        this(name, icon, color, taxonomy, List.of());
    }
}
```

> **Por que `record`?** Records em Java são imutáveis por design — o template da árvore é uma constante que nunca muda em runtime. Usar uma classe mutável seria um risco desnecessário e forçaria getters boilerplate. Também demonstra o padrão idiomático para estruturas de dados em Java 16+.

- [ ] **Step 3: Compilar para verificar que não há erros**

```bash
cd backend && ./mvnw compile -q
```

Esperado: BUILD SUCCESS sem erros de compilação.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/category/CategoryTaxonomy.java \
        backend/src/main/java/com/fintech/api/domain/category/CategoryTemplateNode.java
git commit -m "feat(category): adiciona enum CategoryTaxonomy e record CategoryTemplateNode"
```

---

## Task 3: `DefaultCategorySeeder` — testes primeiro (TDD)

**Files:**
- Create: `backend/src/test/java/com/fintech/api/service/DefaultCategorySeederTest.java`
- Create: `backend/src/main/java/com/fintech/api/service/DefaultCategorySeeder.java`

- [ ] **Step 1: Escrever os testes que devem falhar**

```java
package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultCategorySeederTest {

    @Mock
    CategoryRepository categoryRepository;

    @InjectMocks
    DefaultCategorySeeder seeder;

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Tenant Teste");

        when(categoryRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("seedForTenant cria exatamente 66 categorias")
    void seedForTenant_creates66Categories() {
        seeder.seedForTenant(tenant);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        assertThat(captor.getValue()).hasSize(66);
    }

    @Test
    @DisplayName("seedForTenant cria exatamente 14 categorias raiz")
    void seedForTenant_creates14RootCategories() {
        seeder.seedForTenant(tenant);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        long rootCount = captor.getValue().stream()
                .filter(c -> c.getParent() == null)
                .count();
        assertThat(rootCount).isEqualTo(14);
    }

    @Test
    @DisplayName("todas as 66 categorias têm taxonomy_code não nulo")
    void seedForTenant_allCategoriesHaveTaxonomyCode() {
        seeder.seedForTenant(tenant);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        assertThat(captor.getValue())
                .allSatisfy(c -> assertThat(c.getTaxonomyCode()).isNotNull());
    }

    @Test
    @DisplayName("nenhuma categoria tem tenant nulo")
    void seedForTenant_allCategoriesHaveTenant() {
        seeder.seedForTenant(tenant);

        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).saveAll(captor.capture());

        assertThat(captor.getValue())
                .allSatisfy(c -> assertThat(c.getTenant()).isEqualTo(tenant));
    }

    @Test
    @DisplayName("saveAll é chamado exatamente uma vez (batch único)")
    void seedForTenant_callsSaveAllOnce() {
        seeder.seedForTenant(tenant);

        verify(categoryRepository, times(1)).saveAll(anyList());
    }
}
```

- [ ] **Step 2: Rodar para confirmar que falham (classe não existe ainda)**

```bash
cd backend && ./mvnw test -pl . -Dtest=DefaultCategorySeederTest -q 2>&1 | tail -5
```

Esperado: erro de compilação (`DefaultCategorySeeder` não encontrado).

- [ ] **Step 3: Atualizar `Category.java` para adicionar o campo `taxonomyCode`**

Adicionar após o campo `color` (linha ~38 de `Category.java`):

```java
@Column(name = "taxonomy_code", length = 50)
private String taxonomyCode;
```

- [ ] **Step 4: Implementar `DefaultCategorySeeder`**

```java
package com.fintech.api.service;

import com.fintech.api.domain.category.Category;
import com.fintech.api.domain.category.CategoryTemplateNode;
import com.fintech.api.domain.category.CategoryTaxonomy;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.fintech.api.domain.category.CategoryTaxonomy.*;

@Service
@RequiredArgsConstructor
public class DefaultCategorySeeder {

    private final CategoryRepository categoryRepository;

    private static final List<CategoryTemplateNode> TEMPLATE = List.of(
        new CategoryTemplateNode("Renda", "attach_money", "#26A69A", INCOME, List.of(
            new CategoryTemplateNode("Salário",      "payments",          "#26A69A", INCOME_SALARY),
            new CategoryTemplateNode("Freelance",    "work",              "#26A69A", INCOME_FREELANCE),
            new CategoryTemplateNode("Rendimentos",  "trending_up",       "#26A69A", INCOME_INVESTMENT_RETURNS),
            new CategoryTemplateNode("Reembolso",    "currency_exchange", "#26A69A", INCOME_REIMBURSEMENT)
        )),
        new CategoryTemplateNode("Moradia", "home", "#5C6BC0", HOUSING, List.of(
            new CategoryTemplateNode("Aluguel/Prestação", "home",       "#5C6BC0", HOUSING_RENT),
            new CategoryTemplateNode("Condomínio",        "apartment",  "#5C6BC0", HOUSING_CONDO),
            new CategoryTemplateNode("Energia",           "bolt",       "#5C6BC0", HOUSING_ENERGY),
            new CategoryTemplateNode("Água e Gás",        "water_drop", "#5C6BC0", HOUSING_WATER_GAS),
            new CategoryTemplateNode("Internet/TV",       "wifi",       "#5C6BC0", HOUSING_INTERNET)
        )),
        new CategoryTemplateNode("Alimentação", "restaurant", "#66BB6A", FOOD, List.of(
            new CategoryTemplateNode("Supermercado",  "shopping_cart",   "#66BB6A", FOOD_GROCERY),
            new CategoryTemplateNode("Restaurante",   "restaurant",      "#66BB6A", FOOD_RESTAURANT),
            new CategoryTemplateNode("Delivery",      "delivery_dining", "#66BB6A", FOOD_DELIVERY),
            new CategoryTemplateNode("Padaria/Café",  "coffee",          "#66BB6A", FOOD_BAKERY)
        )),
        new CategoryTemplateNode("Transporte", "directions_car", "#FFA726", TRANSPORT, List.of(
            new CategoryTemplateNode("Combustível",          "local_gas_station", "#FFA726", TRANSPORT_FUEL),
            new CategoryTemplateNode("Uber/Apps",            "local_taxi",        "#FFA726", TRANSPORT_RIDESHARE),
            new CategoryTemplateNode("Transporte Público",   "directions_bus",    "#FFA726", TRANSPORT_PUBLIC),
            new CategoryTemplateNode("IPVA/Seguro Auto",     "receipt_long",      "#FFA726", TRANSPORT_VEHICLE_TAX),
            new CategoryTemplateNode("Manutenção do Veículo","build",             "#FFA726", TRANSPORT_VEHICLE_MAINTENANCE)
        )),
        new CategoryTemplateNode("Saúde", "favorite", "#EF5350", HEALTH, List.of(
            new CategoryTemplateNode("Farmácia",          "local_pharmacy",    "#EF5350", HEALTH_PHARMACY),
            new CategoryTemplateNode("Plano de Saúde",    "health_and_safety", "#EF5350", HEALTH_INSURANCE),
            new CategoryTemplateNode("Consultas e Exames","medical_services",  "#EF5350", HEALTH_APPOINTMENTS),
            new CategoryTemplateNode("Academia e Esporte","fitness_center",    "#EF5350", HEALTH_FITNESS),
            new CategoryTemplateNode("Saúde Mental",      "psychology",        "#EF5350", HEALTH_MENTAL)
        )),
        new CategoryTemplateNode("Lazer", "movie", "#AB47BC", LEISURE, List.of(
            new CategoryTemplateNode("Cinema e Shows", "theaters",      "#AB47BC", LEISURE_ENTERTAINMENT),
            new CategoryTemplateNode("Viagens",        "flight",        "#AB47BC", LEISURE_TRAVEL),
            new CategoryTemplateNode("Hobbies",        "palette",       "#AB47BC", LEISURE_HOBBIES),
            new CategoryTemplateNode("Bares e Baladas","nightlife",     "#AB47BC", LEISURE_NIGHTLIFE)
        )),
        new CategoryTemplateNode("Educação", "school", "#26C6DA", EDUCATION, List.of(
            new CategoryTemplateNode("Cursos e Treinamentos", "computer",  "#26C6DA", EDUCATION_COURSES),
            new CategoryTemplateNode("Livros e Material",     "menu_book", "#26C6DA", EDUCATION_BOOKS),
            new CategoryTemplateNode("Escola/Faculdade",      "school",    "#26C6DA", EDUCATION_SCHOOL)
        )),
        new CategoryTemplateNode("Vestuário", "checkroom", "#8D6E63", CLOTHING, List.of(
            new CategoryTemplateNode("Roupas e Calçados",     "checkroom",    "#8D6E63", CLOTHING_APPAREL),
            new CategoryTemplateNode("Acessórios",            "watch",        "#8D6E63", CLOTHING_ACCESSORIES),
            new CategoryTemplateNode("Lavanderia/Tinturaria", "local_laundry_service", "#8D6E63", CLOTHING_LAUNDRY)
        )),
        new CategoryTemplateNode("Casa & Decoração", "weekend", "#A1887F", HOME_GOODS, List.of(
            new CategoryTemplateNode("Móveis e Decoração",      "weekend",     "#A1887F", HOME_GOODS_FURNITURE),
            new CategoryTemplateNode("Utilidades Domésticas",   "kitchen",     "#A1887F", HOME_GOODS_UTILITIES),
            new CategoryTemplateNode("Manutenção e Reparos",    "handyman",    "#A1887F", HOME_GOODS_MAINTENANCE)
        )),
        new CategoryTemplateNode("Assinaturas", "subscriptions", "#BDBDBD", SUBSCRIPTIONS, List.of(
            new CategoryTemplateNode("Streaming de Vídeo",  "play_circle",  "#BDBDBD", SUBSCRIPTIONS_VIDEO),
            new CategoryTemplateNode("Streaming de Música", "music_note",   "#BDBDBD", SUBSCRIPTIONS_MUSIC),
            new CategoryTemplateNode("Games",               "sports_esports","#BDBDBD", SUBSCRIPTIONS_GAMING),
            new CategoryTemplateNode("Apps e Software",     "smartphone",   "#BDBDBD", SUBSCRIPTIONS_SOFTWARE)
        )),
        new CategoryTemplateNode("Cuidados Pessoais", "spa", "#F48FB1", PERSONAL_CARE, List.of(
            new CategoryTemplateNode("Cabelo e Beleza",   "content_cut",   "#F48FB1", PERSONAL_CARE_BEAUTY),
            new CategoryTemplateNode("Higiene e Cuidados","soap",          "#F48FB1", PERSONAL_CARE_HYGIENE),
            new CategoryTemplateNode("Bem-estar e Spa",   "spa",           "#F48FB1", PERSONAL_CARE_WELLNESS)
        )),
        new CategoryTemplateNode("Pets", "pets", "#FFCA28", PETS, List.of(
            new CategoryTemplateNode("Ração e Petshop", "pets",            "#FFCA28", PETS_FOOD),
            new CategoryTemplateNode("Veterinário",     "medical_services","#FFCA28", PETS_VET),
            new CategoryTemplateNode("Banho e Tosa",    "shower",          "#FFCA28", PETS_GROOMING)
        )),
        new CategoryTemplateNode("Financeiro", "account_balance", "#78909C", FINANCIAL, List.of(
            new CategoryTemplateNode("Impostos e Taxas",          "receipt_long",   "#78909C", FINANCIAL_TAXES),
            new CategoryTemplateNode("Tarifas Bancárias",         "account_balance","#78909C", FINANCIAL_BANK_FEES),
            new CategoryTemplateNode("Empréstimos/Financiamentos","trending_down",  "#78909C", FINANCIAL_LOANS),
            new CategoryTemplateNode("Seguros",                   "shield",         "#78909C", FINANCIAL_INSURANCE)
        )),
        new CategoryTemplateNode("Presentes e Doações", "card_giftcard", "#EF9A9A", GIFTS, List.of(
            new CategoryTemplateNode("Presentes", "card_giftcard", "#EF9A9A", GIFTS_PRESENTS),
            new CategoryTemplateNode("Doações",   "volunteer_activism", "#EF9A9A", GIFTS_DONATIONS)
        ))
    );

    public void seedForTenant(Tenant tenant) {
        List<Category> toSave = new ArrayList<>();
        for (CategoryTemplateNode rootNode : TEMPLATE) {
            Category root = buildCategory(rootNode, null, tenant);
            toSave.add(root);
            collectChildren(root, rootNode, tenant, toSave);
        }
        categoryRepository.saveAll(toSave);
    }

    private Category buildCategory(CategoryTemplateNode node, Category parent, Tenant tenant) {
        return Category.builder()
                .name(node.name())
                .icon(node.icon())
                .color(node.color())
                .taxonomyCode(node.taxonomy().name())
                .tenant(tenant)
                .parent(parent)
                .build();
    }

    private void collectChildren(Category parent, CategoryTemplateNode node, Tenant tenant, List<Category> acc) {
        for (CategoryTemplateNode child : node.children()) {
            Category childCategory = buildCategory(child, parent, tenant);
            acc.add(childCategory);
            collectChildren(childCategory, child, tenant, acc);
        }
    }
}
```

> **Por que sem `@Transactional` no seeder?** A transação pertence ao `TenantRegistrationService.register()`. Se o seed falhar (ex: constraint), toda a operação é revertida — tenant e admin user não ficam persistidos parcialmente. Colocar `@Transactional` no seeder criaria uma transação separada, quebrando a atomicidade.

> **Por que `saveAll()` em vez de `save()` em loop?** O JPA pode fazer batching automático com `saveAll()` dependendo da configuração `spring.jpa.properties.hibernate.jdbc.batch_size`. Em todo caso, é uma única chamada ao repositório — mais simples e eficiente.

- [ ] **Step 5: Rodar os testes para confirmar que passam**

```bash
cd backend && ./mvnw test -pl . -Dtest=DefaultCategorySeederTest -q
```

Esperado: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/domain/category/Category.java \
        backend/src/main/java/com/fintech/api/service/DefaultCategorySeeder.java \
        backend/src/test/java/com/fintech/api/service/DefaultCategorySeederTest.java
git commit -m "feat(category): implementa DefaultCategorySeeder com 66 categorias padrão"
```

---

## Task 4: Integrar `DefaultCategorySeeder` no `TenantRegistrationService`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/service/TenantRegistrationService.java`
- Create: `backend/src/test/java/com/fintech/api/service/TenantRegistrationServiceTest.java`

- [ ] **Step 1: Escrever os testes que devem falhar**

```java
package com.fintech.api.service;

import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.dto.TenantRegistrationDTO;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantRegistrationServiceTest {

    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock DefaultCategorySeeder categorySeeder;
    @InjectMocks TenantRegistrationService service;

    private TenantRegistrationDTO dto() {
        return new TenantRegistrationDTO("Família Teste", "Carlos Teste", "carlos@teste.com", "senha123");
    }

    @Test
    @DisplayName("register chama seedForTenant após criar o admin user")
    void register_callsSeedForTenant() {
        Tenant savedTenant = new Tenant();
        savedTenant.setId(UUID.randomUUID());
        savedTenant.setName("Família Teste");

        when(userRepository.existsByEmail("carlos@teste.com")).thenReturn(false);
        when(tenantRepository.save(any())).thenReturn(savedTenant);
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");

        service.register(dto());

        verify(categorySeeder, times(1)).seedForTenant(savedTenant);
    }

    @Test
    @DisplayName("register propaga exceção do seeder (garante rollback)")
    void register_propagatesSeederException() {
        Tenant savedTenant = new Tenant();
        savedTenant.setId(UUID.randomUUID());

        when(userRepository.existsByEmail("carlos@teste.com")).thenReturn(false);
        when(tenantRepository.save(any())).thenReturn(savedTenant);
        when(passwordEncoder.encode("senha123")).thenReturn("hashed");
        doThrow(new RuntimeException("falha no seed")).when(categorySeeder).seedForTenant(any());

        assertThatThrownBy(() -> service.register(dto()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha no seed");
    }
}
```

- [ ] **Step 2: Rodar para confirmar que falham**

```bash
cd backend && ./mvnw test -pl . -Dtest=TenantRegistrationServiceTest -q 2>&1 | tail -5
```

Esperado: erro de compilação (`DefaultCategorySeeder` não injetado em `TenantRegistrationService`).

- [ ] **Step 3: Atualizar `TenantRegistrationService` para injetar o seeder**

Localizar `TenantRegistrationService.java` e fazer duas alterações:

Adicionar `DefaultCategorySeeder` aos campos finais (Lombok `@RequiredArgsConstructor` cria o construtor):

```java
private final TenantRepository tenantRepository;
private final UserRepository userRepository;
private final PasswordEncoder passwordEncoder;
private final DefaultCategorySeeder categorySeeder;   // <- novo
```

Adicionar a chamada ao final do método `register()`, antes do `return tenant`:

```java
        userRepository.save(adminUser);

        // 4. Seed de categorias padrão (dentro da mesma transação — atomicidade total)
        categorySeeder.seedForTenant(tenant);

        // 5. Retorno
        return tenant;
```

- [ ] **Step 4: Rodar os testes para confirmar que passam**

```bash
cd backend && ./mvnw test -pl . -Dtest=TenantRegistrationServiceTest -q
```

Esperado: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Rodar a suite completa para verificar ausência de regressões**

```bash
cd backend && ./mvnw test -q
```

Esperado: todos os testes passando.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/fintech/api/service/TenantRegistrationService.java \
        backend/src/test/java/com/fintech/api/service/TenantRegistrationServiceTest.java
git commit -m "feat(tenant): integra DefaultCategorySeeder no fluxo de registro de tenant"
```

---

## Task 5: Atualizar `CategoryResponseDTO` e `openapi.yaml`

**Files:**
- Modify: `backend/src/main/java/com/fintech/api/dto/category/CategoryResponseDTO.java`
- Modify: `api-spec/openapi.yaml`

- [ ] **Step 1: Atualizar o record `CategoryResponseDTO`**

Substituir a definição completa do record:

```java
package com.fintech.api.dto.category;

import com.fintech.api.domain.category.Category;
import java.util.List;
import java.util.UUID;

public record CategoryResponseDTO(
        UUID id,
        String name,
        String icon,
        String color,
        UUID parentId,
        boolean archived,
        String taxonomyCode,
        List<CategoryResponseDTO> children) {

    public static CategoryResponseDTO fromEntity(Category category) {
        return fromEntity(category, false);
    }

    public static CategoryResponseDTO fromEntity(Category category, boolean includeArchived) {
        return new CategoryResponseDTO(
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.getColor(),
                category.getParent() != null ? category.getParent().getId() : null,
                category.getDeletedAt() != null,
                category.getTaxonomyCode(),
                category.getChildren().stream()
                        .filter(c -> includeArchived || c.getDeletedAt() == null)
                        .map(c -> fromEntity(c, includeArchived))
                        .toList());
    }
}
```

- [ ] **Step 2: Atualizar o schema `CategoryResponseDTO` em `api-spec/openapi.yaml`**

Localizar o bloco do schema (linha ~101) e substituir:

```yaml
    CategoryResponseDTO:
      type: object
      required: [id, name, icon, color, archived, children]
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
        archived:
          type: boolean
        taxonomyCode:
          type: string
          nullable: true
          description: "Código semântico estável para benchmarking cross-tenant. null = categoria criada pelo usuário."
          example: "HOUSING"
        children:
          type: array
          items:
            $ref: '#/components/schemas/CategoryResponseDTO'
```

- [ ] **Step 3: Rodar o codegen para atualizar o código gerado pelo OpenAPI Generator**

```bash
cd backend && ./mvnw generate-sources -q
```

- [ ] **Step 4: Verificar que a compilação e os testes continuam passando**

```bash
cd backend && ./mvnw test -q
```

Esperado: todos os testes passando.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fintech/api/dto/category/CategoryResponseDTO.java \
        api-spec/openapi.yaml
git commit -m "feat(category): expõe taxonomyCode no CategoryResponseDTO e na OpenAPI spec"
```

---

## Task 6: Atualizar `V10__seed_dev.sql` com `taxonomy_code`

**Files:**
- Modify: `backend/src/main/resources/db/seed/V10__seed_dev.sql`

- [ ] **Step 1: Atualizar os INSERTs das categorias raiz**

Localizar a seção `-- Raízes` (linha ~124) e substituir:

```sql
-- Raízes
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, taxonomy_code) VALUES
  (c_moradia,     v_tenant, v_carlos, 'Moradia',       'home',          '#5C6BC0', 'HOUSING'),
  (c_alimentacao, v_tenant, v_carlos, 'Alimentação',   'restaurant',    '#66BB6A', 'FOOD'),
  (c_transporte,  v_tenant, v_carlos, 'Transporte',    'directions_car','#FFA726', 'TRANSPORT'),
  (c_saude,       v_tenant, v_carlos, 'Saúde',         'favorite',      '#EF5350', 'HEALTH'),
  (c_lazer,       v_tenant, v_carlos, 'Lazer',         'movie',         '#AB47BC', 'LEISURE'),
  (c_educacao,    v_tenant, v_carlos, 'Educação',      'school',        '#26C6DA', 'EDUCATION'),
  (c_roupas,      v_tenant, v_carlos, 'Vestuário',     'checkroom',     '#8D6E63', 'CLOTHING'),
  (c_receitas,    v_tenant, v_carlos, 'Renda',         'attach_money',  '#26A69A', 'INCOME'),
  (c_assinaturas, v_tenant, v_carlos, 'Assinaturas',   'subscriptions', '#BDBDBD', 'SUBSCRIPTIONS');
```

- [ ] **Step 2: Atualizar os INSERTs das categorias filhas com `taxonomy_code`**

Localizar cada bloco de filhas e adicionar a coluna e os valores correspondentes:

```sql
-- Filhas — Moradia
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_aluguel,    v_tenant, v_carlos, 'Aluguel',    'home',          '#5C6BC0', c_moradia, 'HOUSING_RENT'),
  (c_condominio, v_tenant, v_carlos, 'Condomínio', 'apartment',     '#5C6BC0', c_moradia, 'HOUSING_CONDO'),
  (c_energia,    v_tenant, v_carlos, 'Energia',    'bolt',          '#5C6BC0', c_moradia, 'HOUSING_ENERGY'),
  (c_agua_gas,   v_tenant, v_carlos, 'Água/Gás',   'water_drop',    '#5C6BC0', c_moradia, 'HOUSING_WATER_GAS'),
  (c_internet,   v_tenant, v_carlos, 'Internet',   'wifi',          '#5C6BC0', c_moradia, 'HOUSING_INTERNET');

-- Filhas — Alimentação
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_supermercado, v_tenant, v_carlos, 'Supermercado', 'shopping_cart',  '#66BB6A', c_alimentacao, 'FOOD_GROCERY'),
  (c_restaurante,  v_tenant, v_carlos, 'Restaurante',  'restaurant',     '#66BB6A', c_alimentacao, 'FOOD_RESTAURANT'),
  (c_delivery,     v_tenant, v_carlos, 'Delivery',     'delivery_dining','#66BB6A', c_alimentacao, 'FOOD_DELIVERY');

-- Filhas — Transporte
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_combustivel, v_tenant, v_carlos, 'Combustível',  'local_gas_station','#FFA726', c_transporte, 'TRANSPORT_FUEL'),
  (c_uber,        v_tenant, v_carlos, 'Uber/99',      'local_taxi',       '#FFA726', c_transporte, 'TRANSPORT_RIDESHARE'),
  (c_ipva,        v_tenant, v_carlos, 'IPVA / Seguro','receipt_long',     '#FFA726', c_transporte, 'TRANSPORT_VEHICLE_TAX');

-- Filhas — Saúde
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_farmacia,    v_tenant, v_carlos, 'Farmácia',       'local_pharmacy',    '#EF5350', c_saude, 'HEALTH_PHARMACY'),
  (c_plano_saude, v_tenant, v_carlos, 'Plano de Saúde', 'health_and_safety', '#EF5350', c_saude, 'HEALTH_INSURANCE'),
  (c_academia,    v_tenant, v_carlos, 'Academia',       'fitness_center',    '#EF5350', c_saude, 'HEALTH_FITNESS');

-- Filhas — Lazer
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_streaming, v_tenant, v_carlos, 'Streaming',    'play_circle', '#AB47BC', c_lazer, 'SUBSCRIPTIONS_VIDEO'),
  (c_cinema,    v_tenant, v_carlos, 'Cinema/Shows', 'theaters',    '#AB47BC', c_lazer, 'LEISURE_ENTERTAINMENT'),
  (c_viagens,   v_tenant, v_carlos, 'Viagens',      'flight',      '#AB47BC', c_lazer, 'LEISURE_TRAVEL');

-- Filhas — Educação
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_cursos, v_tenant, v_carlos, 'Cursos Online', 'computer',  '#26C6DA', c_educacao, 'EDUCATION_COURSES'),
  (c_livros, v_tenant, v_carlos, 'Livros',        'menu_book', '#26C6DA', c_educacao, 'EDUCATION_BOOKS');

-- Filhas — Roupas & Casa (agora Vestuário)
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_compras_gerais, v_tenant, v_carlos, 'Roupas e Calçados', 'shopping_bag', '#8D6E63', c_roupas, 'CLOTHING_APPAREL');

-- Filhas — Renda (era Receitas)
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_salario,     v_tenant, v_carlos, 'Salário',     'payments',    '#26A69A', c_receitas, 'INCOME_SALARY'),
  (c_freelance,   v_tenant, v_carlos, 'Freelance',   'work',        '#26A69A', c_receitas, 'INCOME_FREELANCE'),
  (c_rendimentos, v_tenant, v_carlos, 'Rendimentos', 'trending_up', '#26A69A', c_receitas, 'INCOME_INVESTMENT_RETURNS');

-- Filhas — Assinaturas
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id, taxonomy_code) VALUES
  (c_servicos_dig, v_tenant, v_carlos, 'Serviços Digitais', 'smartphone', '#BDBDBD', c_assinaturas, 'SUBSCRIPTIONS_SOFTWARE');
```

> **Nota:** `c_streaming` no V10 era filho de Lazer. Como a nova taxonomia separa Assinaturas de Lazer, o V10 mantém a estrutura original (sem quebrar cross-references de transações) e apenas adiciona o `taxonomy_code` mais próximo semanticamente. O V10 é um artefato de desenvolvimento — não precisa refletir exatamente a taxonomia nova, apenas ter os códigos para fins de teste.

- [ ] **Step 3: Fazer o reset do banco dev e verificar que o seed funciona**

```bash
docker exec fintech-postgres psql -U admin -d postgres -c "DROP DATABASE fintech; CREATE DATABASE fintech;"
```

Depois iniciar o backend com perfil dev:
```bash
cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

Verificar nos logs que `V10__seed_dev.sql` foi aplicado sem erros (`Successfully applied`).

- [ ] **Step 4: Smoke test via curl**

```bash
# 1. Login
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"carlos@costa.com","password":"costa123"}' | jq -r '.token')

# 2. Buscar categorias e verificar taxonomyCode
curl -s http://localhost:8080/api/categories \
  -H "Authorization: Bearer $TOKEN" | jq '.[0] | {name, taxonomyCode}'
```

Esperado: objeto com `name` e `taxonomyCode` não nulo (ex: `"HOUSING"` para Moradia).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/seed/V10__seed_dev.sql
git commit -m "chore(seed): adiciona taxonomy_code nas categorias do dataset dev (Família Costa)"
```

---

## Task 7: Verificação final e PR

- [ ] **Step 1: Rodar toda a suite de testes**

```bash
cd backend && ./mvnw test -q
```

Esperado: todos os testes passando, sem regressões.

- [ ] **Step 2: Verificar o smoke test de registro de novo tenant**

```bash
# Registrar novo tenant
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Família Teste",
    "adminName": "João Teste",
    "adminEmail": "joao@teste.com",
    "password": "teste123"
  }' | jq .

# Login com o novo tenant
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"joao@teste.com","password":"teste123"}' | jq -r '.token')

# Verificar que 14 categorias raiz foram criadas
curl -s http://localhost:8080/api/categories \
  -H "Authorization: Bearer $TOKEN" | jq 'length'
```

Esperado: `14`

- [ ] **Step 3: Verificar que `taxonomyCode` aparece nas categorias**

```bash
curl -s http://localhost:8080/api/categories \
  -H "Authorization: Bearer $TOKEN" | jq '[.[] | {name, taxonomyCode}]'
```

Esperado: 14 objetos, todos com `taxonomyCode` não nulo.

- [ ] **Step 4: Fechar a issue e criar PR**

```bash
gh pr create \
  --title "feat: seed de categorias padrão ao registrar tenant + campo taxonomyCode" \
  --body "Closes #71" \
  --base develop
```
