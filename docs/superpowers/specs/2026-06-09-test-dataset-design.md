# Design: Dataset de Testes — Família Costa

**Data:** 2026-06-09  
**Status:** Aprovado  
**Período coberto:** Dezembro 2025 – Junho 2026

---

## Objetivo

Criar um dataset realista que cubra todas as funcionalidades do sistema em três formas:

1. **SQL seed** (`V10__seed_dev.sql`) — carregado automaticamente pelo Flyway no perfil `dev`
2. **HTTP Collection** (`docs/http/seed-dataset.http`) — fluxo completo via API, executável no IntelliJ/VS Code
3. **Fixture mínima** (`seed_base.sql`) — base estável para testes de integração com Testcontainers

---

## Artefatos e localização

```
backend/src/main/resources/db/seed/
  V10__seed_dev.sql

backend/src/test/resources/sql/
  seed_base.sql

docs/http/
  seed-dataset.http
  README.md
```

### Ativação do seed SQL

Adicionar em `application-dev.properties`:

```properties
spring.flyway.locations=classpath:db/migration,classpath:db/seed
```

O Flyway aplica `V10` apenas no perfil `dev`. Em `test` e `prod` a pasta `db/seed` não está no classpath.

**Reset do banco dev:** `DROP DATABASE fintech; CREATE DATABASE fintech;` + reiniciar a app.

---

## Cenário narrativo — Família Costa

### Tenant

| Campo | Valor |
|-------|-------|
| name | Família Costa |

### Usuários

| Nome | Role | Email | Senha | Situação |
|------|------|-------|-------|----------|
| Carlos Costa | ADMIN | carlos@costa.com | costa123 | Criador do tenant |
| Ana Costa | USER | ana@costa.com | costa123 | Aceita convite |
| Pedro Costa | USER | pedro@costa.com | costa123 | Aceita convite |
| João Costa | — | joao@costa.com | — | Convite PENDING (não aceitou) |

### Contas

| Nome | Tipo | Detalhes do cartão | `countInLiquidBalance` | `countInNetWorth` |
|------|------|--------------------|------------------------|-------------------|
| Bradesco Corrente | CHECKING | — | true | true |
| Carteira | CASH | — | true | true |
| Nubank | CREDIT_CARD | MASTERCARD, \*\*\*\*1234, limite R$15.000, fechamento dia 2, vencimento dia 10 | false | true |
| Inter | CREDIT_CARD | MASTERCARD, \*\*\*\*5678, limite R$8.000, fechamento dia 5, vencimento dia 15 | false | true |
| XP Investimentos | INVESTMENT | — | false | true |

**Dashboard `totalAccountBalance`** = saldo Bradesco + saldo Carteira (únicos com `countInLiquidBalance = true`).

---

## Categorias

Árvore hierárquica com 8 raízes ativas + 1 raiz arquivada.

```
Moradia            icon: home            color: #5C6BC0
  ├─ Aluguel
  ├─ Condomínio
  ├─ Energia
  ├─ Água/Gás
  └─ Internet

Alimentação        icon: restaurant      color: #66BB6A
  ├─ Supermercado
  ├─ Restaurante
  └─ Delivery

Transporte         icon: directions_car  color: #FFA726
  ├─ Combustível
  ├─ Uber/99
  └─ IPVA / Seguro

Saúde              icon: favorite        color: #EF5350
  ├─ Farmácia
  ├─ Plano de Saúde
  └─ Academia

Lazer              icon: movie           color: #AB47BC
  ├─ Streaming
  ├─ Cinema/Shows
  └─ Viagens

Educação           icon: school          color: #26C6DA
  ├─ Cursos Online
  └─ Livros

Roupas & Casa      icon: checkroom       color: #8D6E63
  └─ Compras Gerais

Receitas           icon: attach_money    color: #26A69A
  ├─ Salário
  ├─ Freelance
  └─ Rendimentos

──── ARQUIVADA (deleted_at preenchido) ────
Assinaturas        icon: smartphone      color: #BDBDBD
  └─ Serviços Digitais                   (também arquivada)
```

**Cenários cobertos:**
- `categoryPath` exibe `"Moradia → Aluguel"` no breakdown de fatura
- Toggle "Mostrar arquivadas" revela `Assinaturas` com nome taxado
- Transação de Dez/2025 vinculada a `Assinaturas → Serviços Digitais` — aparece disabled no select ao editar
- 1 transação sem categoria (campo `category_id` null) — testa ícone vazio na listagem

---

## Transações

### Recorrentes mensais (Jan–Jun 2026)

Todas criadas para os 6 meses, exceto onde indicado.

| Descrição | Conta | Categoria | Valor | Status em Jun |
|-----------|-------|-----------|-------|---------------|
| Salário Carlos | Bradesco | Receitas → Salário | R$ 8.500,00 | PAID |
| Aluguel | Bradesco | Moradia → Aluguel | R$ 2.200,00 | PAID |
| Condomínio | Bradesco | Moradia → Condomínio | R$ 350,00 | PAID |
| Energia | Bradesco | Moradia → Energia | R$ 180,00 | **PENDING** |
| Água/Gás | Bradesco | Moradia → Água/Gás | R$ 90,00 | **PENDING** |
| Internet | Bradesco | Moradia → Internet | R$ 120,00 | PAID |
| Supermercado #1 | Bradesco | Alimentação → Supermercado | R$ 280,00 | PAID |
| Supermercado #2 | Bradesco | Alimentação → Supermercado | R$ 195,00 | PAID |
| Combustível | Bradesco | Transporte → Combustível | R$ 220,00 | PAID |
| Farmácia | Carteira | Saúde → Farmácia | R$ 75,00 | PAID |
| Netflix | Nubank | Lazer → Streaming | R$ 45,00 | — (fatura) |
| iFood #1 | Nubank | Alimentação → Delivery | R$ 68,00 | — (fatura) |
| iFood #2 | Nubank | Alimentação → Delivery | R$ 82,00 | — (fatura) |
| iFood #3 | Nubank | Alimentação → Delivery | R$ 55,00 | — (fatura) |
| Restaurante | Nubank | Alimentação → Restaurante | R$ 145,00 | — (fatura) |

### Parcelamentos

**Notebook Samsung — 12x R$ 350,00** (compra: 15 Fev 2026, Nubank)
- InstallmentGroup: total R$ 4.200,00, 12 parcelas
- Parcelas 1–3 (Fev–Abr, faturas PAID): PAID | Parcelas 4–12 (Mai–Jan/2027, fatura CLOSED ou OPEN): PENDING
- Categoria: Roupas & Casa → Compras Gerais

**Geladeira Brastemp — 6x R$ 280,00** (compra: 10 Mar 2026, Inter)
- InstallmentGroup: total R$ 1.680,00, 6 parcelas
- Parcelas 1–2 (Mar–Abr, faturas PAID): PAID | Parcelas 3–6 (Mai–Ago, fatura CLOSED ou OPEN): PENDING
- Categoria: Roupas & Casa → Compras Gerais

### Cenários especiais (um só vez)

| Mês | Descrição | Conta | Valor | Detalhe |
|-----|-----------|-------|-------|---------|
| Dez/2025 | Spotify Premium | Nubank | R$ 21,90 | Categoria: `Assinaturas → Serviços Digitais` (arquivada) |
| Jan/2026 | Ingresso show (cancelado) | Nubank | R$ 120,00 | Status: **CANCELLED** |
| Jan/2026 | Compra diversa | Carteira | R$ 50,00 | `category_id = null` |
| Mar/2026 | Farmácia (por Ana) | Carteira | R$ 65,00 | `user_id = ana` (testa autoria diferente) |
| Jun/2026 | Convite João Costa | — | — | Status PENDING, email joao@costa.com |

---

## Faturas

### Timeline

```
         Jan    Fev    Mar    Abr    Mai    Jun
Nubank   PAID   PAID   PAID   PAID   CLOSED OPEN
Inter    —      —      PAID   PAID   CLOSED OPEN
```

### Datas de referência

**Nubank** (fechamento dia 2, vencimento dia 10):

| Fatura | closing_date | due_date | Status |
|--------|-------------|----------|--------|
| Jan/2026 | 2026-01-02 | 2026-01-10 | PAID |
| Fev/2026 | 2026-02-02 | 2026-02-10 | PAID |
| Mar/2026 | 2026-03-02 | 2026-03-10 | PAID |
| Abr/2026 | 2026-04-02 | 2026-04-10 | PAID |
| Mai/2026 | 2026-05-02 | 2026-05-10 | CLOSED |
| Jun/2026 | 2026-06-02 | 2026-06-10 | OPEN |

**Inter** (fechamento dia 5, vencimento dia 15):

| Fatura | closing_date | due_date | Status |
|--------|-------------|----------|--------|
| Mar/2026 | 2026-03-05 | 2026-03-15 | PAID |
| Abr/2026 | 2026-04-05 | 2026-04-15 | PAID |
| Mai/2026 | 2026-05-05 | 2026-05-15 | CLOSED |
| Jun/2026 | 2026-06-05 | 2026-06-15 | OPEN |

**Mai CLOSED nos dois cartões** → botão "Pagar" disponível no frontend.

---

## Transferências

Transferências regulares entre contas (poupança mensal). Cobrem o feature de transfer sem confundir com pagamento de fatura.

| Mês | De | Para | Valor | Descrição |
|-----|-----|------|-------|-----------|
| Jan–Jun (todo mês) | Bradesco | XP Investimentos | R$ 500,00 | Aporte mensal |

**Pagamento de faturas:** todas as faturas Jan–Abr são pagas via `POST /api/invoices/{id}/pay` com `sourceAccountId = bradesco`. O `InvoiceService` cria o EXPENSE em Bradesco automaticamente — não é registrada uma transferência separada para isso.

---

## HTTP Collection — estrutura de blocos

Arquivo: `docs/http/seed-dataset.http`  
Variáveis capturadas via `> {% client.global.set(...) %}` entre requests.

```
Bloco 1: Auth
  POST /auth/register       → captura nenhum (tenant criado)
  POST /auth/login          → @token_carlos

Bloco 2: Contas
  POST /api/accounts ×5    → @id_bradesco, @id_carteira, @id_nubank, @id_inter, @id_xp

Bloco 3: Categorias
  POST /api/categories ×8  (raízes)
  POST /api/categories ×20 (filhas)
  POST /api/categories/archive ×2  (Assinaturas + Serviços Digitais)

Bloco 4: Membros
  POST /api/invitations     → @token_invite_ana
  POST /auth/register       (Ana aceita + cria conta)
  POST /api/invitations     → @token_invite_pedro
  POST /auth/register       (Pedro aceita + cria conta)
  POST /api/invitations     (João — não aceita, permanece PENDING)

Bloco 5: Transações recorrentes (Jan–Jun)
  POST /api/transactions ×N por mês

Bloco 6: Parcelamentos
  POST /api/transactions    (Notebook 12x)
  POST /api/transactions    (Geladeira 6x)

Bloco 7: Transferências (Jan–Jun)
  POST /api/transfers ×6  (Bradesco → XP, aporte mensal)

Bloco 8: Ciclo de vida de faturas
  POST /api/invoices/{id}/pay    (Nubank Jan–Abr com sourceAccountId=bradesco, Inter Mar–Abr)
  POST /api/invoices/{id}/close  (Nubank Mai, Inter Mai)

Bloco 9: Verificações
  GET /api/dashboard
  GET /api/transactions?accountId=@id_nubank&startDate=2026-06-01&endDate=2026-06-30
  GET /api/invoices?accountId=@id_nubank
  GET /api/invoices/{id_nubank_mai}
  GET /api/members
  GET /api/invitations
```

---

## Fixture de testes de integração

Arquivo: `backend/src/test/resources/sql/seed_base.sql`

Conteúdo mínimo: 1 tenant, 1 usuário ADMIN, 3 contas (CHECKING, CREDIT_CARD, CASH), 4 categorias (2 raiz + 2 filha). Sem transações — cada teste insere seus próprios dados via `@Sql` inline ou builder.

Uso em testes:

```java
@Sql(scripts = "/sql/seed_base.sql", executionPhase = BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/cleanup.sql",   executionPhase = AFTER_TEST_METHOD)
class TransactionServiceIntegrationTest { ... }
```

---

## Cobertura de funcionalidades

| Feature | Coberta |
|---------|---------|
| Auth — register, login, JWT | ✅ |
| Contas — 4 tipos, CRUD, credit card details | ✅ |
| Categorias — hierarquia, archive, toggle | ✅ |
| Transações — PAID / PENDING / CANCELLED | ✅ |
| Transação sem categoria | ✅ |
| Filtros — conta, status, tipo, período, descrição | ✅ |
| Agrupamento por período | ✅ |
| Agrupamento por fatura | ✅ |
| Parcelamentos — criar, parcelas distribuídas | ✅ |
| Transferências | ✅ |
| Faturas — OPEN / CLOSED / PAID | ✅ |
| Fatura CLOSED pronta para pagar | ✅ |
| Dashboard — saldo, posição financeira | ✅ |
| Membros e convites — PENDING + aceitos | ✅ |
| Multi-usuário (transação criada por Ana) | ✅ |
| Categoria arquivada em transação histórica | ✅ |
