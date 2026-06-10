# Spec: Seed de Categorias Padrão + TaxonomyCode

**Data:** 2026-06-10
**Status:** Aprovado
**Motivação:** Novos tenants iniciam com lista de categorias vazia (UX ruim) e não há base semântica comum para benchmarking anônimo entre tenants (roadmap item 5).

---

## 1. Problema

Dois problemas distintos resolvidos pela mesma feature:

### 1.1 Onboarding vazio
Ao completar o cadastro, o usuário vê uma tela de categorias em branco. Precisa criar toda a taxonomia do zero antes de registrar a primeira transação.

### 1.2 Sem base para benchmarking (roadmap item 5)
O [innovation-roadmap.md](../../innovation-roadmap.md#5-benchmarking-anônimo-social-proof) prevê comparativo anônimo entre tenants ("usuários com sua faixa de renda gastam 15% menos com lazer"). Para que isso seja possível, gastos precisam ser comparáveis semanticamente — não apenas por nome de categoria, que cada tenant pode customizar livremente.

---

## 2. Decisão de Design

### 2.1 Por que não categorias globais compartilhadas

A alternativa de ter categorias com `tenant_id IS NULL` visíveis a todos os tenants foi descartada por:

- Toda query de categorias exigiria `WHERE tenant_id = ? OR tenant_id IS NULL` — ponto de falha com risco de vazamento de dados entre tenants
- Semântica de archive/delete torna-se ambígua (tenant pode arquivar categoria global? Só para ele?)
- Viola o princípio de isolamento de tenant, que é o invariante mais crítico do sistema

### 2.2 Solução escolhida: seed por tenant + campo `taxonomy_code`

Ao registrar um novo tenant:
1. O sistema cria **instâncias tenant-owned** das categorias padrão (modelo não muda)
2. Cada categoria recebe um `taxonomy_code` estável (ex: `HOUSING`, `FOOD`)
3. O tenant pode renomear, recolorir, arquivar ou deletar qualquer categoria — o `taxonomy_code` persiste
4. Categorias criadas manualmente pelo tenant têm `taxonomy_code = null` → excluídas de benchmarks

Isso preserva:
- Isolamento total de tenant
- Liberdade de customização
- Base semântica para queries cross-tenant por `taxonomy_code`

---

## 3. Taxonomia Padrão

14 categorias raiz, cada uma com subcategorias predefinidas.

| # | Raiz | `taxonomy_code` | Subcategorias |
|---|------|-----------------|---------------|
| 1 | Renda | `INCOME` | Salário (`INCOME_SALARY`), Freelance (`INCOME_FREELANCE`), Rendimentos (`INCOME_INVESTMENT_RETURNS`), Reembolso (`INCOME_REIMBURSEMENT`) |
| 2 | Moradia | `HOUSING` | Aluguel/Prestação (`HOUSING_RENT`), Condomínio (`HOUSING_CONDO`), Energia (`HOUSING_ENERGY`), Água e Gás (`HOUSING_WATER_GAS`), Internet/TV (`HOUSING_INTERNET`) |
| 3 | Alimentação | `FOOD` | Supermercado (`FOOD_GROCERY`), Restaurante (`FOOD_RESTAURANT`), Delivery (`FOOD_DELIVERY`), Padaria/Café (`FOOD_BAKERY`) |
| 4 | Transporte | `TRANSPORT` | Combustível (`TRANSPORT_FUEL`), Uber/Apps (`TRANSPORT_RIDESHARE`), Transporte Público (`TRANSPORT_PUBLIC`), IPVA/Seguro Auto (`TRANSPORT_VEHICLE_TAX`), Manutenção do Veículo (`TRANSPORT_VEHICLE_MAINTENANCE`) |
| 5 | Saúde | `HEALTH` | Farmácia (`HEALTH_PHARMACY`), Plano de Saúde (`HEALTH_INSURANCE`), Consultas e Exames (`HEALTH_APPOINTMENTS`), Academia e Esporte (`HEALTH_FITNESS`), Saúde Mental (`HEALTH_MENTAL`) |
| 6 | Lazer | `LEISURE` | Cinema e Shows (`LEISURE_ENTERTAINMENT`), Viagens (`LEISURE_TRAVEL`), Hobbies (`LEISURE_HOBBIES`), Bares e Baladas (`LEISURE_NIGHTLIFE`) |
| 7 | Educação | `EDUCATION` | Cursos e Treinamentos (`EDUCATION_COURSES`), Livros e Material (`EDUCATION_BOOKS`), Escola/Faculdade (`EDUCATION_SCHOOL`) |
| 8 | Vestuário | `CLOTHING` | Roupas e Calçados (`CLOTHING_APPAREL`), Acessórios (`CLOTHING_ACCESSORIES`), Lavanderia/Tinturaria (`CLOTHING_LAUNDRY`) |
| 9 | Casa & Decoração | `HOME_GOODS` | Móveis e Decoração (`HOME_GOODS_FURNITURE`), Utilidades Domésticas (`HOME_GOODS_UTILITIES`), Manutenção e Reparos (`HOME_GOODS_MAINTENANCE`) |
| 10 | Assinaturas | `SUBSCRIPTIONS` | Streaming de Vídeo (`SUBSCRIPTIONS_VIDEO`), Streaming de Música (`SUBSCRIPTIONS_MUSIC`), Games (`SUBSCRIPTIONS_GAMING`), Apps e Software (`SUBSCRIPTIONS_SOFTWARE`) |
| 11 | Cuidados Pessoais | `PERSONAL_CARE` | Cabelo e Beleza (`PERSONAL_CARE_BEAUTY`), Higiene e Cuidados (`PERSONAL_CARE_HYGIENE`), Bem-estar e Spa (`PERSONAL_CARE_WELLNESS`) |
| 12 | Pets | `PETS` | Ração e Petshop (`PETS_FOOD`), Veterinário (`PETS_VET`), Banho e Tosa (`PETS_GROOMING`) |
| 13 | Financeiro | `FINANCIAL` | Impostos e Taxas (`FINANCIAL_TAXES`), Tarifas Bancárias (`FINANCIAL_BANK_FEES`), Empréstimos/Financiamentos (`FINANCIAL_LOANS`), Seguros (`FINANCIAL_INSURANCE`) |
| 14 | Presentes e Doações | `GIFTS` | Presentes (`GIFTS_PRESENTS`), Doações (`GIFTS_DONATIONS`) |

**Total: 14 raízes + 52 subcategorias = 66 categorias por tenant.**

### Regra de desambiguação: Seguros

Seguros aparecem em dois contextos. A distinção é:
- **Transporte → IPVA/Seguro Auto** (`TRANSPORT_VEHICLE_TAX`): seguro obrigatório e DPVAT, seguro do veículo — gasto inerente ao carro
- **Financeiro → Seguros** (`FINANCIAL_INSURANCE`): seguro de vida, seguro residencial, seguro saúde privado fora do plano — produto financeiro contratado separadamente

> O V10 (seed da Família Costa) cobre um subconjunto desta taxonomia e precisará ser expandido com as 5 novas raízes e subcategorias adicionais ao ser atualizado.

---

## 4. Modelo de Dados

### 4.1 Migration V11 — nova coluna

```sql
-- V11__category_taxonomy_code.sql
ALTER TABLE categories
    ADD COLUMN taxonomy_code VARCHAR(50) NULL;

COMMENT ON COLUMN categories.taxonomy_code IS
    'Código semântico estável para benchmarking cross-tenant. NULL = categoria criada pelo usuário.';
```

A coluna é:
- **Nullable**: categorias criadas manualmente pelo usuário não têm código
- **Sem FK**: os valores são controlados pelo enum Java, não por tabela de referência
- **Sem unique constraint**: dois tenants podem ter categorias com o mesmo `taxonomy_code` (é exatamente o ponto)
- **Sem índice separado**: queries de benchmarking são raras e analíticas; índice composto (`taxonomy_code, tenant_id`) pode ser adicionado quando a feature de benchmarking for implementada

### 4.2 Atualização da entidade `Category`

```java
@Column(name = "taxonomy_code", length = 50)
private String taxonomyCode;
```

O campo é `String` (não enum) na entidade para evitar falha de deserialização caso o banco contenha um código desconhecido em migrações futuras. O enum `CategoryTaxonomy` existe apenas na camada de aplicação.

### 4.3 `CategoryResponseDTO` — campo adicional

```java
public record CategoryResponseDTO(
    UUID id,
    String name,
    String icon,
    String color,
    UUID parentId,
    boolean archived,
    String taxonomyCode,        // novo — nullable
    List<CategoryResponseDTO> children
) { ... }
```

`taxonomyCode` é exposto no DTO para uso futuro no frontend (ex: exibir badge "padrão do sistema", ou ordenar categorias por tipo na UI de benchmarking). Não é editável via API.

---

## 5. Componentes Backend

### 5.1 `CategoryTaxonomy` (enum)

```
com.fintech.api.domain.category.CategoryTaxonomy
```

Enum com todos os 66 códigos. Serve como fonte da verdade para o seeder e para validação em code review.

```java
public enum CategoryTaxonomy {
    // Renda
    INCOME, INCOME_SALARY, INCOME_FREELANCE, INCOME_INVESTMENT_RETURNS, INCOME_REIMBURSEMENT,
    // Moradia
    HOUSING, HOUSING_RENT, HOUSING_CONDO, HOUSING_ENERGY, HOUSING_WATER_GAS, HOUSING_INTERNET,
    // Alimentação
    FOOD, FOOD_GROCERY, FOOD_RESTAURANT, FOOD_DELIVERY, FOOD_BAKERY,
    // Transporte
    TRANSPORT, TRANSPORT_FUEL, TRANSPORT_RIDESHARE, TRANSPORT_PUBLIC, TRANSPORT_VEHICLE_TAX, TRANSPORT_VEHICLE_MAINTENANCE,
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

### 5.2 `CategoryTemplateNode` (record)

```
com.fintech.api.domain.category.CategoryTemplateNode
```

Estrutura de dados imutável que representa um nó da árvore de categorias padrão. Usada apenas internamente pelo seeder — não persistida diretamente.

```java
public record CategoryTemplateNode(
    String name,
    String icon,
    String color,
    CategoryTaxonomy taxonomy,
    List<CategoryTemplateNode> children
) {
    public CategoryTemplateNode(String name, String icon, String color, CategoryTaxonomy taxonomy) {
        this(name, icon, color, taxonomy, List.of());
    }
}
```

### 5.3 `DefaultCategorySeeder` (service)

```
com.fintech.api.service.DefaultCategorySeeder
```

Responsabilidade única: dado um `Tenant`, criar as 66 categorias padrão e persistir em batch.

```java
@Service
@RequiredArgsConstructor
public class DefaultCategorySeeder {

    private final CategoryRepository categoryRepository;

    // Definição estática da árvore — imutável, carregada uma vez na JVM
    private static final List<CategoryTemplateNode> TEMPLATE = List.of( /* ... */ );

    public void seedForTenant(Tenant tenant) {
        List<Category> toSave = new ArrayList<>();
        for (CategoryTemplateNode root : TEMPLATE) {
            Category rootCategory = buildCategory(root, null, tenant);
            collectSubtree(rootCategory, root, tenant, toSave);
            toSave.add(0, rootCategory); // raiz antes dos filhos para FK
        }
        categoryRepository.saveAll(toSave);
    }

    private Category buildCategory(CategoryTemplateNode node, Category parent, Tenant tenant) { ... }
    private void collectSubtree(Category parent, CategoryTemplateNode node, Tenant tenant, List<Category> acc) { ... }
}
```

**Decisões de implementação:**
- `saveAll()` em batch único (mais eficiente que N saves individuais)
- Sem `createdBy` nas categorias seedadas — campo é nullable (ver `Category.java:48`); categorias do sistema não têm "autor"
- Método é `public` (não `@Transactional`) — a transação pertence ao chamador (`TenantRegistrationService`); atomicidade é responsabilidade do contexto de registro

### 5.4 `TenantRegistrationService` — integração

Adicionar chamada ao seeder ao final do método `register()`, dentro da transação existente:

```java
@Transactional
public Tenant register(TenantRegistrationDTO dto) {
    // ... (código atual: cria tenant + admin user)

    // 4. Seed de categorias padrão
    categorySeeder.seedForTenant(tenant);   // <- novo

    return tenant;
}
```

A atomicidade é garantida pela transação do `register()`: se o seed falhar (ex: violação de constraint), todo o registro é revertido — tenant e usuário admin não ficam persistidos parcialmente.

---

## 6. OpenAPI Spec

Adicionar `taxonomyCode` ao schema `CategoryResponseDTO` em `api-spec/openapi.yaml`:

```yaml
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
    archived:
      type: boolean
    taxonomyCode:
      type: string
      nullable: true
      description: "Código semântico estável para benchmarking. null = categoria criada pelo usuário."
      example: "HOUSING"
    children:
      type: array
      items:
        $ref: '#/components/schemas/CategoryResponseDTO'
```

`taxonomyCode` **não** é adicionado ao `CategoryCreateDTO` nem ao `CategoryUpdateDTO` — é somente leitura, controlado pelo sistema.

---

## 7. Dataset de Testes (`V10__seed_dev.sql`)

O seed da Família Costa já usa a mesma taxonomia. É necessário **adicionar `taxonomy_code` aos INSERTs existentes** após a migration V11, para que o dataset de desenvolvimento reflita a realidade do sistema.

```sql
-- Exemplo de atualização no V10 após V11:
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, taxonomy_code) VALUES
  (c_moradia,     v_tenant, v_carlos, 'Moradia', 'home', '#5C6BC0', 'HOUSING'),
  ...
```

> **Atenção:** V10 é um seed de perfil `dev` e não é imutável como migrations de schema. Pode e deve ser atualizado ao adicionar colunas.

---

## 8. Testes

### 8.1 `DefaultCategorySeederTest` (unitário)

Arquivo: `backend/src/test/java/com/fintech/api/service/DefaultCategorySeederTest.java`

Cenários obrigatórios:
- `seedForTenant` cria exatamente 66 categorias (14 raízes + 52 filhas)
- Todas as 14 raízes têm `parent == null`
- Todas as 66 categorias têm `taxonomy_code != null`
- Nenhuma categoria tem `tenant` nulo
- `categoryRepository.saveAll()` é chamado uma única vez (batch)

### 8.2 `TenantRegistrationServiceTest` (unitário existente)

Verificar que a integração com `DefaultCategorySeeder` é chamada:
- `categorySeeder.seedForTenant(tenant)` é chamado após criação do admin user
- Se `seedForTenant` lançar exceção, `register()` propaga a exceção (rollback)

### 8.3 `TenantRegistrationIntegrationTest` (Testcontainers — novo ou existente)

Cenário de smoke test end-to-end:
- Registrar novo tenant via `TenantRegistrationService.register()`
- Consultar `GET /api/categories` com token do admin criado
- Verificar que a resposta contém 14 categorias raiz
- Verificar que ao menos uma categoria raiz tem `taxonomyCode` não nulo

---

## 9. Impacto em Código Existente

| Componente | Impacto | Ação |
|---|---|---|
| `Category.java` | +1 campo `taxonomyCode` | Adicionar campo |
| `CategoryResponseDTO.java` | +1 campo no record | Atualizar record e `fromEntity()` |
| `CategoryService.java` | Nenhum | — |
| `CategoryController.java` | Nenhum | — |
| `CategoryRepository.java` | Nenhum | — |
| `TenantRegistrationService.java` | +1 dependência + 1 linha | Injetar seeder, chamar `seedForTenant()` |
| `openapi.yaml` | +1 campo no schema | Atualizar spec |
| `V10__seed_dev.sql` | Adicionar `taxonomy_code` nos INSERTs | Atualizar seed dev |
| `seed_base.sql` (Testcontainers) | Verificar se há INSERTs de categorias | Atualizar se necessário |

**O que NÃO muda:**
- Regras de isolamento de tenant (queries existentes não precisam de alteração)
- API pública de categorias (nenhum endpoint novo, nenhum parâmetro novo)
- Lógica de archive/delete (funciona igual para categorias seedadas e manuais)
- Frontend (nenhuma mudança obrigatória; `taxonomyCode` fica disponível para uso futuro)

---

## 10. Perguntas em Aberto (fora do escopo desta feature)

- **Novos tenants em produção:** tenants criados antes desta feature ficam sem categorias. Estratégia de migração (backfill) é decisão futura — out of scope aqui.
- **Query de benchmarking:** a infra está pronta (`taxonomy_code` na tabela), mas a query cross-tenant com anonimização LGPD é implementada junto com a feature de Benchmarking (roadmap item 5).
- **Internacionalização:** a taxonomia é em PT-BR. Se o sistema suportar múltiplos idiomas no futuro, os `taxonomy_code` são estáveis como chaves de tradução.
- **Atualização do catálogo padrão:** tenants existentes não recebem novas categorias automaticamente quando o template evolui. Essa decisão é intencional — evita sobrescrever customizações do usuário.
