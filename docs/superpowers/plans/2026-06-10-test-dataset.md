# Dataset de Testes — Família Costa — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Criar dataset realista cobrindo todas as funcionalidades do sistema via SQL seed (Flyway dev), HTTP collection (IntelliJ/VS Code) e fixture mínima (Testcontainers).

**Architecture:** `V10__seed_dev.sql` carregado pelo Flyway somente no perfil `dev` via `spring.flyway.locations` adicional. Toda a lógica de inserção é encapsulada em um bloco `DO $$` com variáveis UUID predefinidas para garantir referências cruzadas corretas. A fixture de Testcontainers é minimalista: 1 tenant / 1 admin / 3 contas / 4 categorias — cada teste insere seus próprios dados.

**Tech Stack:** PostgreSQL 16, Flyway, Spring Boot (perfil dev), IntelliJ HTTP Client

---

## Mapa de Arquivos

| Ação | Arquivo |
|------|---------|
| Modificar | `backend/src/main/resources/application-dev.properties` |
| Criar | `backend/src/main/resources/db/seed/V10__seed_dev.sql` |
| Criar | `backend/src/test/resources/sql/seed_base.sql` |
| Criar | `docs/http/seed-dataset.http` |
| Criar | `docs/http/README.md` |

---

## UUIDs predefinidos (referência rápida)

```
Tenant:      10000000-0000-0000-0000-000000000001
Carlos:      20000000-0000-0000-0000-000000000001
Ana:         20000000-0000-0000-0000-000000000002
Pedro:       20000000-0000-0000-0000-000000000003
Bradesco:    30000000-0000-0000-0000-000000000001
Carteira:    30000000-0000-0000-0000-000000000002
Nubank:      30000000-0000-0000-0000-000000000003
Inter:       30000000-0000-0000-0000-000000000004
XP:          30000000-0000-0000-0000-000000000005
--- Categorias raízes ---
Moradia:     40000000-0000-0000-0000-000000000001
Alimentação: 40000000-0000-0000-0000-000000000002
Transporte:  40000000-0000-0000-0000-000000000003
Saúde:       40000000-0000-0000-0000-000000000004
Lazer:       40000000-0000-0000-0000-000000000005
Educação:    40000000-0000-0000-0000-000000000006
Roupas&Casa: 40000000-0000-0000-0000-000000000007
Receitas:    40000000-0000-0000-0000-000000000008
Assinaturas: 40000000-0000-0000-0000-000000000009 (ARCHIVED)
--- Categorias filhas ---
Aluguel:     40000000-0000-0000-0000-000000000011
Condomínio:  40000000-0000-0000-0000-000000000012
Energia:     40000000-0000-0000-0000-000000000013
Água/Gás:    40000000-0000-0000-0000-000000000014
Internet:    40000000-0000-0000-0000-000000000015
Supermercado:40000000-0000-0000-0000-000000000021
Restaurante: 40000000-0000-0000-0000-000000000022
Delivery:    40000000-0000-0000-0000-000000000023
Combustível: 40000000-0000-0000-0000-000000000031
Uber/99:     40000000-0000-0000-0000-000000000032
IPVA/Seguro: 40000000-0000-0000-0000-000000000033
Farmácia:    40000000-0000-0000-0000-000000000041
PlanoSaúde:  40000000-0000-0000-0000-000000000042
Academia:    40000000-0000-0000-0000-000000000043
Streaming:   40000000-0000-0000-0000-000000000051
Cinema/Shows:40000000-0000-0000-0000-000000000052
Viagens:     40000000-0000-0000-0000-000000000053
Cursos:      40000000-0000-0000-0000-000000000061
Livros:      40000000-0000-0000-0000-000000000062
ComprasGerais:40000000-0000-0000-0000-000000000071
Salário:     40000000-0000-0000-0000-000000000081
Freelance:   40000000-0000-0000-0000-000000000082
Rendimentos: 40000000-0000-0000-0000-000000000083
ServiçosDig: 40000000-0000-0000-0000-000000000091 (ARCHIVED)
--- Faturas Nubank ---
Nu Dec/2025: 50000000-0000-0000-0000-000000000001
Nu Jan/2026: 50000000-0000-0000-0000-000000000002
Nu Fev/2026: 50000000-0000-0000-0000-000000000003
Nu Mar/2026: 50000000-0000-0000-0000-000000000004
Nu Abr/2026: 50000000-0000-0000-0000-000000000005
Nu Mai/2026: 50000000-0000-0000-0000-000000000006
Nu Jun/2026: 50000000-0000-0000-0000-000000000007
--- Faturas Inter ---
It Mar/2026: 50000000-0000-0000-0000-000000000008
It Abr/2026: 50000000-0000-0000-0000-000000000009
It Mai/2026: 50000000-0000-0000-0000-000000000010
It Jun/2026: 50000000-0000-0000-0000-000000000011
--- Parcelamentos ---
GrpNotebook: 60000000-0000-0000-0000-000000000001
GrpGeladeira:60000000-0000-0000-0000-000000000002
--- Transfer IDs ---
TrfJan:      70000000-0000-0000-0000-000000000001
TrfFev:      70000000-0000-0000-0000-000000000002
TrfMar:      70000000-0000-0000-0000-000000000003
TrfAbr:      70000000-0000-0000-0000-000000000004
TrfMai:      70000000-0000-0000-0000-000000000005
TrfJun:      70000000-0000-0000-0000-000000000006
```

---

## Task 1: Configurar Flyway para carregar seed no perfil dev

**Files:**
- Modify: `backend/src/main/resources/application-dev.properties`
- Create: `backend/src/main/resources/db/seed/` (diretório)

- [ ] **Step 1: Adicionar localização do seed em application-dev.properties**

Acrescentar ao final de `backend/src/main/resources/application-dev.properties`:

```properties
# Flyway seed — carregado apenas no perfil dev
spring.flyway.locations=classpath:db/migration,classpath:db/seed
```

- [ ] **Step 2: Criar o diretório db/seed**

```bash
mkdir -p backend/src/main/resources/db/seed
```

- [ ] **Step 3: Verificar que o app sobe sem erro (sem V10 ainda)**

```bash
cd backend && ./mvnw spring-boot:run -q 2>&1 | grep -E "Started|ERROR|Flyway" | head -5
# Esperado: "Started FintechApplication" sem erros de Flyway
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/application-dev.properties \
        backend/src/main/resources/db/seed/
git commit -m "chore(seed): configura Flyway para carregar seed no perfil dev"
```

---

## Task 2: V10 — DECLARE + Tenant + Usuários + Contas + Convite

**Files:**
- Create: `backend/src/main/resources/db/seed/V10__seed_dev.sql`

- [ ] **Step 1: Criar V10 com DECLARE block, tenant, usuários, contas e convite**

Criar `backend/src/main/resources/db/seed/V10__seed_dev.sql` com o conteúdo:

```sql
-- =============================================================
-- V10__seed_dev.sql  —  Dataset de testes: Família Costa
-- Carregado APENAS no perfil dev (spring.flyway.locations).
-- Reset: DROP DATABASE fintech; CREATE DATABASE fintech; + reiniciar app.
-- Senha de todos os usuários: costa123
-- =============================================================

DO $$
DECLARE
  -- Tenant
  v_tenant    UUID := '10000000-0000-0000-0000-000000000001';

  -- Usuários
  v_carlos    UUID := '20000000-0000-0000-0000-000000000001';
  v_ana       UUID := '20000000-0000-0000-0000-000000000002';
  v_pedro     UUID := '20000000-0000-0000-0000-000000000003';
  -- BCrypt de "costa123" (rounds=10)
  v_pw        TEXT := '$2b$10$gYpbpa/LsoPLo/93XTltUuAKcVE.CFseR2kw9o7jAvkO6MpZEgZaC';

  -- Contas
  v_bradesco  UUID := '30000000-0000-0000-0000-000000000001';
  v_carteira  UUID := '30000000-0000-0000-0000-000000000002';
  v_nubank    UUID := '30000000-0000-0000-0000-000000000003';
  v_inter     UUID := '30000000-0000-0000-0000-000000000004';
  v_xp        UUID := '30000000-0000-0000-0000-000000000005';

  -- Categorias — raízes
  c_moradia        UUID := '40000000-0000-0000-0000-000000000001';
  c_alimentacao    UUID := '40000000-0000-0000-0000-000000000002';
  c_transporte     UUID := '40000000-0000-0000-0000-000000000003';
  c_saude          UUID := '40000000-0000-0000-0000-000000000004';
  c_lazer          UUID := '40000000-0000-0000-0000-000000000005';
  c_educacao       UUID := '40000000-0000-0000-0000-000000000006';
  c_roupas         UUID := '40000000-0000-0000-0000-000000000007';
  c_receitas       UUID := '40000000-0000-0000-0000-000000000008';
  c_assinaturas    UUID := '40000000-0000-0000-0000-000000000009';

  -- Categorias — filhas
  c_aluguel        UUID := '40000000-0000-0000-0000-000000000011';
  c_condominio     UUID := '40000000-0000-0000-0000-000000000012';
  c_energia        UUID := '40000000-0000-0000-0000-000000000013';
  c_agua_gas       UUID := '40000000-0000-0000-0000-000000000014';
  c_internet       UUID := '40000000-0000-0000-0000-000000000015';
  c_supermercado   UUID := '40000000-0000-0000-0000-000000000021';
  c_restaurante    UUID := '40000000-0000-0000-0000-000000000022';
  c_delivery       UUID := '40000000-0000-0000-0000-000000000023';
  c_combustivel    UUID := '40000000-0000-0000-0000-000000000031';
  c_uber           UUID := '40000000-0000-0000-0000-000000000032';
  c_ipva           UUID := '40000000-0000-0000-0000-000000000033';
  c_farmacia       UUID := '40000000-0000-0000-0000-000000000041';
  c_plano_saude    UUID := '40000000-0000-0000-0000-000000000042';
  c_academia       UUID := '40000000-0000-0000-0000-000000000043';
  c_streaming      UUID := '40000000-0000-0000-0000-000000000051';
  c_cinema         UUID := '40000000-0000-0000-0000-000000000052';
  c_viagens        UUID := '40000000-0000-0000-0000-000000000053';
  c_cursos         UUID := '40000000-0000-0000-0000-000000000061';
  c_livros         UUID := '40000000-0000-0000-0000-000000000062';
  c_compras_gerais UUID := '40000000-0000-0000-0000-000000000071';
  c_salario        UUID := '40000000-0000-0000-0000-000000000081';
  c_freelance      UUID := '40000000-0000-0000-0000-000000000082';
  c_rendimentos    UUID := '40000000-0000-0000-0000-000000000083';
  c_servicos_dig   UUID := '40000000-0000-0000-0000-000000000091';

  -- Faturas Nubank (fechamento dia 2, vencimento dia 10)
  v_inv_nu_dec2025 UUID := '50000000-0000-0000-0000-000000000001';
  v_inv_nu_jan     UUID := '50000000-0000-0000-0000-000000000002';
  v_inv_nu_feb     UUID := '50000000-0000-0000-0000-000000000003';
  v_inv_nu_mar     UUID := '50000000-0000-0000-0000-000000000004';
  v_inv_nu_apr     UUID := '50000000-0000-0000-0000-000000000005';
  v_inv_nu_may     UUID := '50000000-0000-0000-0000-000000000006';
  v_inv_nu_jun     UUID := '50000000-0000-0000-0000-000000000007';

  -- Faturas Inter (fechamento dia 5, vencimento dia 15)
  v_inv_it_mar     UUID := '50000000-0000-0000-0000-000000000008';
  v_inv_it_apr     UUID := '50000000-0000-0000-0000-000000000009';
  v_inv_it_may     UUID := '50000000-0000-0000-0000-000000000010';
  v_inv_it_jun     UUID := '50000000-0000-0000-0000-000000000011';

  -- Grupos de parcelamento
  v_grp_notebook   UUID := '60000000-0000-0000-0000-000000000001';
  v_grp_geladeira  UUID := '60000000-0000-0000-0000-000000000002';

  -- Transfer IDs (Jan–Jun)
  v_tr_jan UUID := '70000000-0000-0000-0000-000000000001';
  v_tr_feb UUID := '70000000-0000-0000-0000-000000000002';
  v_tr_mar UUID := '70000000-0000-0000-0000-000000000003';
  v_tr_apr UUID := '70000000-0000-0000-0000-000000000004';
  v_tr_may UUID := '70000000-0000-0000-0000-000000000005';
  v_tr_jun UUID := '70000000-0000-0000-0000-000000000006';

BEGIN

-- ── 1. Tenant ──────────────────────────────────────────────────────────────
INSERT INTO tenants (id, name)
VALUES (v_tenant, 'Família Costa');

-- ── 2. Usuários ────────────────────────────────────────────────────────────
INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES
  (v_carlos, v_tenant, 'Carlos Costa', 'carlos@costa.com', v_pw, 'ADMIN'),
  (v_ana,    v_tenant, 'Ana Costa',    'ana@costa.com',    v_pw, 'USER'),
  (v_pedro,  v_tenant, 'Pedro Costa',  'pedro@costa.com',  v_pw, 'USER');

-- ── 3. Contas ──────────────────────────────────────────────────────────────
INSERT INTO accounts (id, tenant_id, name, type, icon, color,
                      count_in_liquid_balance, count_in_net_worth, created_by) VALUES
  (v_bradesco, v_tenant, 'Bradesco Corrente', 'CHECKING',    'account_balance', '#1565C0', true,  true,  v_carlos),
  (v_carteira, v_tenant, 'Carteira',          'CASH',        'wallet',          '#2E7D32', true,  true,  v_carlos),
  (v_nubank,   v_tenant, 'Nubank',            'CREDIT_CARD', 'credit_card',     '#820AD1', false, true,  v_carlos),
  (v_inter,    v_tenant, 'Inter',             'CREDIT_CARD', 'credit_card',     '#FF6900', false, true,  v_carlos),
  (v_xp,       v_tenant, 'XP Investimentos',  'INVESTMENT',  'trending_up',     '#00796B', false, true,  v_carlos);

INSERT INTO credit_card_details (account_id, brand, last_four_digits, limit_amount, closing_day, due_day) VALUES
  (v_nubank, 'MASTERCARD', '1234', 15000.00, 2,  10),
  (v_inter,  'MASTERCARD', '5678',  8000.00, 5,  15);

-- ── 4. Convite pendente (João) ─────────────────────────────────────────────
INSERT INTO invitations (id, tenant_id, email, token, expires_at, used) VALUES
  ('80000000-0000-0000-0000-000000000001', v_tenant,
   'joao@costa.com', 'seed-invite-joao-pending',
   NOW() + INTERVAL '30 days', false);
```

> O arquivo ainda não está fechado — as próximas Tasks acrescentam seções dentro do BEGIN...END.

---

## Task 3: V10 — Categorias

**Files:**
- Modify: `backend/src/main/resources/db/seed/V10__seed_dev.sql`

- [ ] **Step 1: Acrescentar seção de categorias ao V10 (antes do `END $$`)**

Adicionar ao final do arquivo (antes de fechar com `END $$`):

```sql
-- ── 5. Categorias ──────────────────────────────────────────────────────────
-- Raízes
INSERT INTO categories (id, tenant_id, created_by, name, icon, color) VALUES
  (c_moradia,     v_tenant, v_carlos, 'Moradia',       'home',          '#5C6BC0'),
  (c_alimentacao, v_tenant, v_carlos, 'Alimentação',   'restaurant',    '#66BB6A'),
  (c_transporte,  v_tenant, v_carlos, 'Transporte',    'directions_car','#FFA726'),
  (c_saude,       v_tenant, v_carlos, 'Saúde',         'favorite',      '#EF5350'),
  (c_lazer,       v_tenant, v_carlos, 'Lazer',         'movie',         '#AB47BC'),
  (c_educacao,    v_tenant, v_carlos, 'Educação',      'school',        '#26C6DA'),
  (c_roupas,      v_tenant, v_carlos, 'Roupas & Casa', 'checkroom',     '#8D6E63'),
  (c_receitas,    v_tenant, v_carlos, 'Receitas',      'attach_money',  '#26A69A'),
  (c_assinaturas, v_tenant, v_carlos, 'Assinaturas',   'smartphone',    '#BDBDBD');

-- Filhas — Moradia
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_aluguel,    v_tenant, v_carlos, 'Aluguel',    'home',          '#5C6BC0', c_moradia),
  (c_condominio, v_tenant, v_carlos, 'Condomínio', 'apartment',     '#5C6BC0', c_moradia),
  (c_energia,    v_tenant, v_carlos, 'Energia',    'bolt',          '#5C6BC0', c_moradia),
  (c_agua_gas,   v_tenant, v_carlos, 'Água/Gás',   'water_drop',    '#5C6BC0', c_moradia),
  (c_internet,   v_tenant, v_carlos, 'Internet',   'wifi',          '#5C6BC0', c_moradia);

-- Filhas — Alimentação
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_supermercado, v_tenant, v_carlos, 'Supermercado', 'shopping_cart', '#66BB6A', c_alimentacao),
  (c_restaurante,  v_tenant, v_carlos, 'Restaurante',  'restaurant',   '#66BB6A', c_alimentacao),
  (c_delivery,     v_tenant, v_carlos, 'Delivery',     'delivery_dining','#66BB6A', c_alimentacao);

-- Filhas — Transporte
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_combustivel, v_tenant, v_carlos, 'Combustível',  'local_gas_station','#FFA726', c_transporte),
  (c_uber,        v_tenant, v_carlos, 'Uber/99',      'local_taxi',       '#FFA726', c_transporte),
  (c_ipva,        v_tenant, v_carlos, 'IPVA / Seguro','receipt_long',     '#FFA726', c_transporte);

-- Filhas — Saúde
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_farmacia,   v_tenant, v_carlos, 'Farmácia',       'local_pharmacy', '#EF5350', c_saude),
  (c_plano_saude,v_tenant, v_carlos, 'Plano de Saúde', 'health_and_safety','#EF5350',c_saude),
  (c_academia,   v_tenant, v_carlos, 'Academia',       'fitness_center', '#EF5350', c_saude);

-- Filhas — Lazer
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_streaming, v_tenant, v_carlos, 'Streaming',    'play_circle',  '#AB47BC', c_lazer),
  (c_cinema,    v_tenant, v_carlos, 'Cinema/Shows', 'theaters',     '#AB47BC', c_lazer),
  (c_viagens,   v_tenant, v_carlos, 'Viagens',      'flight',       '#AB47BC', c_lazer);

-- Filhas — Educação
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_cursos, v_tenant, v_carlos, 'Cursos Online', 'computer',    '#26C6DA', c_educacao),
  (c_livros, v_tenant, v_carlos, 'Livros',        'menu_book',   '#26C6DA', c_educacao);

-- Filhas — Roupas & Casa
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_compras_gerais, v_tenant, v_carlos, 'Compras Gerais', 'shopping_bag', '#8D6E63', c_roupas);

-- Filhas — Receitas
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_salario,    v_tenant, v_carlos, 'Salário',     'payments',       '#26A69A', c_receitas),
  (c_freelance,  v_tenant, v_carlos, 'Freelance',   'work',           '#26A69A', c_receitas),
  (c_rendimentos,v_tenant, v_carlos, 'Rendimentos', 'account_balance','#26A69A', c_receitas);

-- Filhas — Assinaturas
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_servicos_dig, v_tenant, v_carlos, 'Serviços Digitais', 'cloud', '#BDBDBD', c_assinaturas);

-- Soft-delete em Assinaturas e Serviços Digitais
UPDATE categories SET deleted_at = '2025-11-01 00:00:00'
WHERE id IN (c_assinaturas, c_servicos_dig);
```

---

## Task 4: V10 — Faturas + Grupos de Parcelamento

**Files:**
- Modify: `backend/src/main/resources/db/seed/V10__seed_dev.sql`

- [ ] **Step 1: Acrescentar faturas e grupos de parcelamento**

Adicionar ao final do arquivo (antes de `END $$`):

```sql
-- ── 6. Faturas ─────────────────────────────────────────────────────────────
-- Nubank: closing_day=2, due_day=10  → closing_date = YYYY-MM-02, due_date = YYYY-MM-10
INSERT INTO invoices (id, account_id, tenant_id, reference_year, reference_month,
                      closing_date, due_date, status) VALUES
  (v_inv_nu_dec2025, v_nubank, v_tenant, 2025, 12, '2025-12-02', '2025-12-10', 'PAID'),
  (v_inv_nu_jan,     v_nubank, v_tenant, 2026,  1, '2026-01-02', '2026-01-10', 'PAID'),
  (v_inv_nu_feb,     v_nubank, v_tenant, 2026,  2, '2026-02-02', '2026-02-10', 'PAID'),
  (v_inv_nu_mar,     v_nubank, v_tenant, 2026,  3, '2026-03-02', '2026-03-10', 'PAID'),
  (v_inv_nu_apr,     v_nubank, v_tenant, 2026,  4, '2026-04-02', '2026-04-10', 'PAID'),
  (v_inv_nu_may,     v_nubank, v_tenant, 2026,  5, '2026-05-02', '2026-05-10', 'CLOSED'),
  (v_inv_nu_jun,     v_nubank, v_tenant, 2026,  6, '2026-06-02', '2026-06-10', 'OPEN');

-- Inter: closing_day=5, due_day=15 → closing_date = YYYY-MM-05, due_date = YYYY-MM-15
INSERT INTO invoices (id, account_id, tenant_id, reference_year, reference_month,
                      closing_date, due_date, status) VALUES
  (v_inv_it_mar, v_inter, v_tenant, 2026, 3, '2026-03-05', '2026-03-15', 'PAID'),
  (v_inv_it_apr, v_inter, v_tenant, 2026, 4, '2026-04-05', '2026-04-15', 'PAID'),
  (v_inv_it_may, v_inter, v_tenant, 2026, 5, '2026-05-05', '2026-05-15', 'CLOSED'),
  (v_inv_it_jun, v_inter, v_tenant, 2026, 6, '2026-06-05', '2026-06-15', 'OPEN');

-- ── 7. Grupos de parcelamento ──────────────────────────────────────────────
INSERT INTO installment_groups (id, tenant_id, account_id, category_id,
                                 description, total_amount, total_installments) VALUES
  (v_grp_notebook,  v_tenant, v_nubank, c_compras_gerais,
   'Notebook Samsung', 4200.00, 12),
  (v_grp_geladeira, v_tenant, v_inter,  c_compras_gerais,
   'Geladeira Brastemp', 1680.00, 6);
```

---

## Task 5: V10 — Transações Bradesco + Carteira (recorrentes + especiais)

**Files:**
- Modify: `backend/src/main/resources/db/seed/V10__seed_dev.sql`

Lógica de status: transações Jan–Mai = PAID; Jun: Energia e Água/Gás = PENDING, demais = PAID.

- [ ] **Step 1: Acrescentar transações Bradesco + Carteira**

```sql
-- ── 8. Transações Bradesco — recorrentes Jan–Jun ───────────────────────────
-- Salário Carlos
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_salario,'Salário Carlos',8500.00,'2026-01-05','INCOME','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_salario,'Salário Carlos',8500.00,'2026-02-05','INCOME','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_salario,'Salário Carlos',8500.00,'2026-03-05','INCOME','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_salario,'Salário Carlos',8500.00,'2026-04-05','INCOME','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_salario,'Salário Carlos',8500.00,'2026-05-05','INCOME','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_salario,'Salário Carlos',8500.00,'2026-06-05','INCOME','PAID');

-- Aluguel
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_aluguel,'Aluguel',2200.00,'2026-01-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_aluguel,'Aluguel',2200.00,'2026-02-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_aluguel,'Aluguel',2200.00,'2026-03-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_aluguel,'Aluguel',2200.00,'2026-04-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_aluguel,'Aluguel',2200.00,'2026-05-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_aluguel,'Aluguel',2200.00,'2026-06-05','EXPENSE','PAID');

-- Condomínio
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_condominio,'Condomínio',350.00,'2026-01-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_condominio,'Condomínio',350.00,'2026-02-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_condominio,'Condomínio',350.00,'2026-03-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_condominio,'Condomínio',350.00,'2026-04-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_condominio,'Condomínio',350.00,'2026-05-05','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_condominio,'Condomínio',350.00,'2026-06-05','EXPENSE','PAID');

-- Energia (Jun = PENDING)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_energia,'Energia',180.00,'2026-01-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_energia,'Energia',180.00,'2026-02-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_energia,'Energia',180.00,'2026-03-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_energia,'Energia',180.00,'2026-04-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_energia,'Energia',180.00,'2026-05-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_energia,'Energia',180.00,'2026-06-10','EXPENSE','PENDING');

-- Água/Gás (Jun = PENDING)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_agua_gas,'Água/Gás',90.00,'2026-01-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_agua_gas,'Água/Gás',90.00,'2026-02-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_agua_gas,'Água/Gás',90.00,'2026-03-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_agua_gas,'Água/Gás',90.00,'2026-04-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_agua_gas,'Água/Gás',90.00,'2026-05-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_agua_gas,'Água/Gás',90.00,'2026-06-10','EXPENSE','PENDING');

-- Internet
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_internet,'Internet',120.00,'2026-01-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_internet,'Internet',120.00,'2026-02-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_internet,'Internet',120.00,'2026-03-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_internet,'Internet',120.00,'2026-04-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_internet,'Internet',120.00,'2026-05-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_internet,'Internet',120.00,'2026-06-10','EXPENSE','PAID');

-- Supermercado #1
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Pão de Açúcar',280.00,'2026-01-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Pão de Açúcar',280.00,'2026-02-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Pão de Açúcar',280.00,'2026-03-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Pão de Açúcar',280.00,'2026-04-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Pão de Açúcar',280.00,'2026-05-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Pão de Açúcar',280.00,'2026-06-10','EXPENSE','PAID');

-- Supermercado #2
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Extra',195.00,'2026-01-20','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Extra',195.00,'2026-02-20','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Extra',195.00,'2026-03-20','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Extra',195.00,'2026-04-20','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Extra',195.00,'2026-05-20','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_supermercado,'Supermercado Extra',195.00,'2026-06-20','EXPENSE','PAID');

-- Combustível
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_combustivel,'Combustível',220.00,'2026-01-15','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_combustivel,'Combustível',220.00,'2026-02-15','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_combustivel,'Combustível',220.00,'2026-03-15','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_combustivel,'Combustível',220.00,'2026-04-15','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_combustivel,'Combustível',220.00,'2026-05-15','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,c_combustivel,'Combustível',220.00,'2026-06-15','EXPENSE','PAID');

-- ── 9. Transações Carteira ─────────────────────────────────────────────────
-- Farmácia — Carlos (todos os meses)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_carteira,c_farmacia,'Farmácia',75.00,'2026-01-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_carteira,c_farmacia,'Farmácia',75.00,'2026-02-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_carteira,c_farmacia,'Farmácia',75.00,'2026-03-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_carteira,c_farmacia,'Farmácia',75.00,'2026-04-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_carteira,c_farmacia,'Farmácia',75.00,'2026-05-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_carteira,c_farmacia,'Farmácia',75.00,'2026-06-10','EXPENSE','PAID');

-- Farmácia — Ana (Mar/2026, user_id = v_ana)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_ana,v_carteira,c_farmacia,'Farmácia',65.00,'2026-03-12','EXPENSE','PAID');

-- Compra diversa (sem categoria — testa category_id null)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_carteira,NULL,'Compra diversa',50.00,'2026-01-15','EXPENSE','PAID');
```

---

## Task 6: V10 — Transações Nubank (recorrentes + Spotify + Cancelada)

**Files:**
- Modify: `backend/src/main/resources/db/seed/V10__seed_dev.sql`

Regras de invoice_id e status:
- Fatura Dec/Jan/Feb/Mar/Abr = PAID → status='PAID'
- Fatura Mai = CLOSED → status='PENDING'
- Fatura Jun = OPEN → status='PENDING'
- Datas no dia 1 do mês (antes do fechamento dia 2) → invoice do mesmo mês

- [ ] **Step 1: Acrescentar transações Nubank ao V10**

```sql
-- ── 10. Transações Nubank recorrentes (Jan–Jun) ────────────────────────────
-- Netflix (dia 1 → fatura do próprio mês)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,invoice_id) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_streaming,'Netflix',45.00,'2026-01-01','EXPENSE','PAID',   v_inv_nu_jan),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_streaming,'Netflix',45.00,'2026-02-01','EXPENSE','PAID',   v_inv_nu_feb),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_streaming,'Netflix',45.00,'2026-03-01','EXPENSE','PAID',   v_inv_nu_mar),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_streaming,'Netflix',45.00,'2026-04-01','EXPENSE','PAID',   v_inv_nu_apr),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_streaming,'Netflix',45.00,'2026-05-01','EXPENSE','PENDING',v_inv_nu_may),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_streaming,'Netflix',45.00,'2026-06-01','EXPENSE','PENDING',v_inv_nu_jun);

-- iFood #1
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,invoice_id) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood',68.00,'2026-01-01','EXPENSE','PAID',   v_inv_nu_jan),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood',68.00,'2026-02-01','EXPENSE','PAID',   v_inv_nu_feb),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood',68.00,'2026-03-01','EXPENSE','PAID',   v_inv_nu_mar),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood',68.00,'2026-04-01','EXPENSE','PAID',   v_inv_nu_apr),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood',68.00,'2026-05-01','EXPENSE','PENDING',v_inv_nu_may),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood',68.00,'2026-06-01','EXPENSE','PENDING',v_inv_nu_jun);

-- iFood #2
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,invoice_id) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Express',82.00,'2026-01-02','EXPENSE','PAID',   v_inv_nu_jan),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Express',82.00,'2026-02-02','EXPENSE','PAID',   v_inv_nu_feb),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Express',82.00,'2026-03-02','EXPENSE','PAID',   v_inv_nu_mar),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Express',82.00,'2026-04-02','EXPENSE','PAID',   v_inv_nu_apr),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Express',82.00,'2026-05-02','EXPENSE','PENDING',v_inv_nu_may),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Express',82.00,'2026-06-02','EXPENSE','PENDING',v_inv_nu_jun);

-- iFood #3
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,invoice_id) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Mercado',55.00,'2026-01-01','EXPENSE','PAID',   v_inv_nu_jan),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Mercado',55.00,'2026-02-01','EXPENSE','PAID',   v_inv_nu_feb),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Mercado',55.00,'2026-03-01','EXPENSE','PAID',   v_inv_nu_mar),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Mercado',55.00,'2026-04-01','EXPENSE','PAID',   v_inv_nu_apr),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Mercado',55.00,'2026-05-01','EXPENSE','PENDING',v_inv_nu_may),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_delivery,'iFood Mercado',55.00,'2026-06-01','EXPENSE','PENDING',v_inv_nu_jun);

-- Restaurante
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,invoice_id) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_restaurante,'Restaurante',145.00,'2026-01-01','EXPENSE','PAID',   v_inv_nu_jan),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_restaurante,'Restaurante',145.00,'2026-02-01','EXPENSE','PAID',   v_inv_nu_feb),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_restaurante,'Restaurante',145.00,'2026-03-01','EXPENSE','PAID',   v_inv_nu_mar),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_restaurante,'Restaurante',145.00,'2026-04-01','EXPENSE','PAID',   v_inv_nu_apr),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_restaurante,'Restaurante',145.00,'2026-05-01','EXPENSE','PENDING',v_inv_nu_may),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_restaurante,'Restaurante',145.00,'2026-06-01','EXPENSE','PENDING',v_inv_nu_jun);

-- ── 11. Cenários especiais Nubank ──────────────────────────────────────────
-- Spotify Dez/2025 — categoria arquivada (Dec 1 ≤ Dec 2 → invoice Dez/2025)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,invoice_id) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_servicos_dig,'Spotify Premium',
   21.90,'2025-12-01','EXPENSE','PAID',v_inv_nu_dec2025);

-- Ingresso show CANCELLED — Jan/2026, sem invoice_id (cancelada antes de ser liquidada)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_cinema,'Ingresso Show',120.00,'2026-01-15','EXPENSE','CANCELLED');
```

---

## Task 7: V10 — Parcelamentos (Notebook + Geladeira)

**Files:**
- Modify: `backend/src/main/resources/db/seed/V10__seed_dev.sql`

Regras de data e invoice:
- **Notebook** (Nubank, compra 2026-02-02 ≤ closing_day 2 → invoice Fev): parcelas 1-3 em Fev/Mar/Abr (PAID), parcela 4 Mai (PENDING/CLOSED), parcela 5 Jun (PENDING/OPEN), parcelas 6-12 sem invoice.
- **Geladeira** (Inter, compra 2026-03-05 ≤ closing_day 5 → invoice Mar): parcelas 1-2 em Mar/Abr (PAID), parcela 3 Mai (PENDING/CLOSED), parcela 4 Jun (PENDING/OPEN), parcelas 5-6 sem invoice.
- Todas as parcelas têm `date = data da compra` (comportamento do sistema).

- [ ] **Step 1: Acrescentar parcelamentos ao V10**

```sql
-- ── 12. Parcelamentos ──────────────────────────────────────────────────────
-- Notebook Samsung 12x R$350 (compra 2026-02-02, Nubank)
INSERT INTO transactions
  (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,
   installment_group_id,installment_number,total_installments,invoice_id)
VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PAID',   v_grp_notebook, 1,12,v_inv_nu_feb),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PAID',   v_grp_notebook, 2,12,v_inv_nu_mar),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PAID',   v_grp_notebook, 3,12,v_inv_nu_apr),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook, 4,12,v_inv_nu_may),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook, 5,12,v_inv_nu_jun),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook, 6,12,NULL),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook, 7,12,NULL),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook, 8,12,NULL),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook, 9,12,NULL),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook,10,12,NULL),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook,11,12,NULL),
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_compras_gerais,'Notebook Samsung',350.00,'2026-02-02','EXPENSE','PENDING',v_grp_notebook,12,12,NULL);

-- Geladeira Brastemp 6x R$280 (compra 2026-03-05, Inter)
INSERT INTO transactions
  (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,
   installment_group_id,installment_number,total_installments,invoice_id)
VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_inter,c_compras_gerais,'Geladeira Brastemp',280.00,'2026-03-05','EXPENSE','PAID',   v_grp_geladeira,1,6,v_inv_it_mar),
  (gen_random_uuid(),v_tenant,v_carlos,v_inter,c_compras_gerais,'Geladeira Brastemp',280.00,'2026-03-05','EXPENSE','PAID',   v_grp_geladeira,2,6,v_inv_it_apr),
  (gen_random_uuid(),v_tenant,v_carlos,v_inter,c_compras_gerais,'Geladeira Brastemp',280.00,'2026-03-05','EXPENSE','PENDING',v_grp_geladeira,3,6,v_inv_it_may),
  (gen_random_uuid(),v_tenant,v_carlos,v_inter,c_compras_gerais,'Geladeira Brastemp',280.00,'2026-03-05','EXPENSE','PENDING',v_grp_geladeira,4,6,v_inv_it_jun),
  (gen_random_uuid(),v_tenant,v_carlos,v_inter,c_compras_gerais,'Geladeira Brastemp',280.00,'2026-03-05','EXPENSE','PENDING',v_grp_geladeira,5,6,NULL),
  (gen_random_uuid(),v_tenant,v_carlos,v_inter,c_compras_gerais,'Geladeira Brastemp',280.00,'2026-03-05','EXPENSE','PENDING',v_grp_geladeira,6,6,NULL);
```

---

## Task 8: V10 — Transferências + Pagamentos de faturas + Fechar DO block

**Files:**
- Modify: `backend/src/main/resources/db/seed/V10__seed_dev.sql`

Valores de pagamento de fatura (soma das transações PAID em cada fatura):
- Nu Dec/2025: R$21,90 (Spotify)
- Nu Jan/2026: R$395,00 (45+68+82+55+145)
- Nu Feb/2026: R$745,00 (395+350 Notebook#1)
- Nu Mar/2026: R$745,00 (395+350 Notebook#2)
- Nu Apr/2026: R$745,00 (395+350 Notebook#3)
- It Mar/2026: R$280,00 (Geladeira#1)
- It Apr/2026: R$280,00 (Geladeira#2)

- [ ] **Step 1: Acrescentar transferências, pagamentos e fechamento do bloco**

```sql
-- ── 13. Transferências Bradesco → XP (aporte mensal, Jan–Jun) ─────────────
-- Cada mês: 2 transações ligadas pelo transfer_id (EXPENSE em Bradesco + INCOME em XP)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,transfer_id) VALUES
  -- Janeiro
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Aporte XP Investimentos', 500.00,'2026-01-10','EXPENSE','PAID',v_tr_jan),
  (gen_random_uuid(),v_tenant,v_carlos,v_xp,      NULL,'Aporte XP Investimentos', 500.00,'2026-01-10','INCOME', 'PAID',v_tr_jan),
  -- Fevereiro
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Aporte XP Investimentos', 500.00,'2026-02-10','EXPENSE','PAID',v_tr_feb),
  (gen_random_uuid(),v_tenant,v_carlos,v_xp,      NULL,'Aporte XP Investimentos', 500.00,'2026-02-10','INCOME', 'PAID',v_tr_feb),
  -- Março
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Aporte XP Investimentos', 500.00,'2026-03-10','EXPENSE','PAID',v_tr_mar),
  (gen_random_uuid(),v_tenant,v_carlos,v_xp,      NULL,'Aporte XP Investimentos', 500.00,'2026-03-10','INCOME', 'PAID',v_tr_mar),
  -- Abril
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Aporte XP Investimentos', 500.00,'2026-04-10','EXPENSE','PAID',v_tr_apr),
  (gen_random_uuid(),v_tenant,v_carlos,v_xp,      NULL,'Aporte XP Investimentos', 500.00,'2026-04-10','INCOME', 'PAID',v_tr_apr),
  -- Maio
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Aporte XP Investimentos', 500.00,'2026-05-10','EXPENSE','PAID',v_tr_may),
  (gen_random_uuid(),v_tenant,v_carlos,v_xp,      NULL,'Aporte XP Investimentos', 500.00,'2026-05-10','INCOME', 'PAID',v_tr_may),
  -- Junho
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Aporte XP Investimentos', 500.00,'2026-06-10','EXPENSE','PAID',v_tr_jun),
  (gen_random_uuid(),v_tenant,v_carlos,v_xp,      NULL,'Aporte XP Investimentos', 500.00,'2026-06-10','INCOME', 'PAID',v_tr_jun);

-- ── 14. Pagamentos de fatura (EXPENSE em Bradesco — replica InvoiceService.pay()) ──
-- Cada entrada corresponde a uma fatura PAID; date = due_date da fatura
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Pagamento fatura Nubank Dez/2025', 21.90, '2025-12-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Pagamento fatura Nubank Jan/2026',395.00, '2026-01-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Pagamento fatura Nubank Fev/2026',745.00, '2026-02-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Pagamento fatura Nubank Mar/2026',745.00, '2026-03-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Pagamento fatura Nubank Abr/2026',745.00, '2026-04-10','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Pagamento fatura Inter Mar/2026',  280.00, '2026-03-15','EXPENSE','PAID'),
  (gen_random_uuid(),v_tenant,v_carlos,v_bradesco,NULL,'Pagamento fatura Inter Abr/2026',  280.00, '2026-04-15','EXPENSE','PAID');

END $$;
```

---

## Task 9: Aplicar seed e verificar

**Files:** nenhum novo

- [ ] **Step 1: Parar o backend se estiver rodando**

```bash
# Ctrl+C no terminal do Spring Boot, ou:
pkill -f 'spring-boot:run' 2>/dev/null; true
```

- [ ] **Step 2: Reset do banco dev**

```bash
docker exec -it $(docker ps -qf "name=postgres") \
  psql -U admin -c "DROP DATABASE fintech; CREATE DATABASE fintech;"
```

- [ ] **Step 3: Subir o backend (Flyway aplica V1–V10)**

```bash
cd backend && ./mvnw spring-boot:run 2>&1 | grep -E "Applied|Started|ERROR|migration" | head -20
# Esperado: "Applied migration V10__seed_dev.sql" e "Started FintechApplication"
```

- [ ] **Step 4: Obter token de Carlos**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"carlos@costa.com","password":"costa123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
echo "Token: ${TOKEN:0:30}..."
```

- [ ] **Step 5: Verificar contagens básicas**

```bash
# Contas (esperado: 5)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/accounts | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Contas: {len(d)}')"

# Categorias (esperado: 25 ativas, assinaturas/serviços não retornados por padrão)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/categories | python3 -c "import sys,json; print('Categorias raízes:', len(json.load(sys.stdin)))"

# Transações Nubank Jun (esperado: 5 recorrentes + Notebook parcela 5)
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/transactions?accountId=30000000-0000-0000-0000-000000000003&startDate=2026-06-01&endDate=2026-06-30" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Transações Nubank Jun: {len(d.get(\"content\",[]) if isinstance(d,dict) else d)}')"

# Faturas Nubank (esperado: 7)
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/invoices?accountId=30000000-0000-0000-0000-000000000003" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Faturas Nubank: {len(d)}')"

# Fatura Nubank Mai deve estar CLOSED
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/api/invoices/50000000-0000-0000-0000-000000000006" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Status Nubank Mai: {d[\"status\"]}')"
# Esperado: CLOSED
```

- [ ] **Step 6: Commit após verificação**

```bash
git add backend/src/main/resources/db/seed/V10__seed_dev.sql
git commit -m "feat(seed): adiciona dataset de testes Família Costa (V10)"
```

---

## Task 10: seed_base.sql — Fixture mínima para Testcontainers

**Files:**
- Create: `backend/src/test/resources/sql/seed_base.sql`
- Create: `backend/src/test/resources/sql/cleanup.sql`

- [ ] **Step 1: Criar diretório**

```bash
mkdir -p backend/src/test/resources/sql
```

- [ ] **Step 2: Criar seed_base.sql**

```sql
-- seed_base.sql — fixture mínima para testes de integração com Testcontainers
-- Uso: @Sql(scripts="/sql/seed_base.sql", executionPhase=BEFORE_TEST_METHOD)
-- Senha do admin: admin123

DO $$
DECLARE
  v_tenant UUID := 'aaaaaaaa-0000-0000-0000-000000000001';
  v_admin  UUID := 'bbbbbbbb-0000-0000-0000-000000000001';
  v_chk    UUID := 'cccccccc-0000-0000-0000-000000000001';
  v_cc     UUID := 'cccccccc-0000-0000-0000-000000000002';
  v_cash   UUID := 'cccccccc-0000-0000-0000-000000000003';
  c_root1  UUID := 'dddddddd-0000-0000-0000-000000000001';
  c_root2  UUID := 'dddddddd-0000-0000-0000-000000000002';
  c_child1 UUID := 'dddddddd-0000-0000-0000-000000000011';
  c_child2 UUID := 'dddddddd-0000-0000-0000-000000000012';
  -- BCrypt de "admin123"
  v_pw     TEXT := '$2b$10$Tpwp7BwL3CREMmQBBEo3BumZ9g3ubrcMobnFjLChHPOFmjSPnKNR.';
BEGIN
  INSERT INTO tenants (id, name) VALUES (v_tenant, 'Tenant Test');

  INSERT INTO users (id, tenant_id, name, email, password_hash, role)
  VALUES (v_admin, v_tenant, 'Admin Test', 'admin@test.com', v_pw, 'ADMIN');

  INSERT INTO accounts (id, tenant_id, name, type, count_in_liquid_balance, count_in_net_worth, created_by)
  VALUES
    (v_chk,  v_tenant, 'Conta Corrente', 'CHECKING',    true,  true,  v_admin),
    (v_cc,   v_tenant, 'Cartão Teste',   'CREDIT_CARD', false, true,  v_admin),
    (v_cash, v_tenant, 'Carteira',       'CASH',        true,  true,  v_admin);

  INSERT INTO credit_card_details (account_id, brand, last_four_digits, limit_amount, closing_day, due_day)
  VALUES (v_cc, 'VISA', '0000', 5000.00, 10, 20);

  INSERT INTO categories (id, tenant_id, created_by, name, icon, color) VALUES
    (c_root1, v_tenant, v_admin, 'Despesas', 'remove_circle', '#EF5350'),
    (c_root2, v_tenant, v_admin, 'Receitas', 'add_circle',    '#66BB6A');

  INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
    (c_child1, v_tenant, v_admin, 'Alimentação', 'restaurant', '#EF5350', c_root1),
    (c_child2, v_tenant, v_admin, 'Salário',     'payments',   '#66BB6A', c_root2);
END $$;
```

- [ ] **Step 3: Criar cleanup.sql**

```sql
-- cleanup.sql — limpa dados entre testes de integração
-- A ordem respeita as FKs (filhas antes de pais)
DELETE FROM transactions;
DELETE FROM installment_groups;
DELETE FROM invoices;
DELETE FROM invitations;
DELETE FROM credit_card_details;
DELETE FROM accounts;
DELETE FROM categories;
DELETE FROM users;
DELETE FROM tenants;
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/resources/sql/
git commit -m "test(seed): adiciona seed_base.sql e cleanup.sql para Testcontainers"
```

---

## Task 11: seed-dataset.http — HTTP Collection

**Files:**
- Create: `docs/http/seed-dataset.http`
- Create: `docs/http/README.md`

- [ ] **Step 1: Criar diretório**

```bash
mkdir -p docs/http
```

- [ ] **Step 2: Criar seed-dataset.http**

```http
### ═══════════════════════════════════════════════════════════════════════
### seed-dataset.http — Dataset Família Costa
### Execute os blocos em ordem no IntelliJ HTTP Client ou VS Code REST Client
### Variáveis capturadas em client.global ficam disponíveis nos blocos seguintes
### ═══════════════════════════════════════════════════════════════════════

### ── Bloco 1: Auth ────────────────────────────────────────────────────────

### 1.1 Registrar tenant + Carlos (ADMIN)
POST http://localhost:8080/auth/register
Content-Type: application/json

{
  "name": "Família Costa",
  "adminName": "Carlos Costa",
  "adminEmail": "carlos@costa.com",
  "password": "costa123"
}

###

### 1.2 Login Carlos → captura token
POST http://localhost:8080/auth/login
Content-Type: application/json

{
  "email": "carlos@costa.com",
  "password": "costa123"
}

> {%
client.global.set("token_carlos", response.body.token);
%}

### ── Bloco 2: Contas ──────────────────────────────────────────────────────

### 2.1 Bradesco Corrente
POST http://localhost:8080/api/accounts
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "name": "Bradesco Corrente",
  "type": "CHECKING",
  "icon": "account_balance",
  "color": "#1565C0",
  "countInLiquidBalance": true,
  "countInNetWorth": true
}

> {%
client.global.set("id_bradesco", response.body.id);
%}

###

### 2.2 Carteira
POST http://localhost:8080/api/accounts
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "name": "Carteira",
  "type": "CASH",
  "icon": "wallet",
  "color": "#2E7D32",
  "countInLiquidBalance": true,
  "countInNetWorth": true
}

> {%
client.global.set("id_carteira", response.body.id);
%}

###

### 2.3 Nubank (CREDIT_CARD)
POST http://localhost:8080/api/accounts
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "name": "Nubank",
  "type": "CREDIT_CARD",
  "icon": "credit_card",
  "color": "#820AD1",
  "countInLiquidBalance": false,
  "countInNetWorth": true,
  "creditCardDetails": {
    "brand": "MASTERCARD",
    "lastFourDigits": "1234",
    "limitAmount": 15000.00,
    "closingDay": 2,
    "dueDay": 10
  }
}

> {%
client.global.set("id_nubank", response.body.id);
%}

###

### 2.4 Inter (CREDIT_CARD)
POST http://localhost:8080/api/accounts
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "name": "Inter",
  "type": "CREDIT_CARD",
  "icon": "credit_card",
  "color": "#FF6900",
  "countInLiquidBalance": false,
  "countInNetWorth": true,
  "creditCardDetails": {
    "brand": "MASTERCARD",
    "lastFourDigits": "5678",
    "limitAmount": 8000.00,
    "closingDay": 5,
    "dueDay": 15
  }
}

> {%
client.global.set("id_inter", response.body.id);
%}

###

### 2.5 XP Investimentos
POST http://localhost:8080/api/accounts
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "name": "XP Investimentos",
  "type": "INVESTMENT",
  "icon": "trending_up",
  "color": "#00796B",
  "countInLiquidBalance": false,
  "countInNetWorth": true
}

> {%
client.global.set("id_xp", response.body.id);
%}

### ── Bloco 3: Categorias ──────────────────────────────────────────────────

### 3.1 Moradia (raiz)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Moradia","icon":"home","color":"#5C6BC0"}

> {%
client.global.set("id_cat_moradia", response.body.id);
%}

###

### 3.2 Alimentação (raiz)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Alimentação","icon":"restaurant","color":"#66BB6A"}

> {%
client.global.set("id_cat_alimentacao", response.body.id);
%}

###

### 3.3 Transporte (raiz)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Transporte","icon":"directions_car","color":"#FFA726"}

> {%
client.global.set("id_cat_transporte", response.body.id);
%}

###

### 3.4 Saúde (raiz)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Saúde","icon":"favorite","color":"#EF5350"}

> {%
client.global.set("id_cat_saude", response.body.id);
%}

###

### 3.5 Lazer (raiz)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Lazer","icon":"movie","color":"#AB47BC"}

> {%
client.global.set("id_cat_lazer", response.body.id);
%}

###

### 3.6 Educação (raiz)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Educação","icon":"school","color":"#26C6DA"}

> {%
client.global.set("id_cat_educacao", response.body.id);
%}

###

### 3.7 Roupas & Casa (raiz)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Roupas & Casa","icon":"checkroom","color":"#8D6E63"}

> {%
client.global.set("id_cat_roupas", response.body.id);
%}

###

### 3.8 Receitas (raiz)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Receitas","icon":"attach_money","color":"#26A69A"}

> {%
client.global.set("id_cat_receitas", response.body.id);
%}

###

### 3.9 Assinaturas (raiz — será arquivada)
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Assinaturas","icon":"smartphone","color":"#BDBDBD"}

> {%
client.global.set("id_cat_assinaturas", response.body.id);
%}

###

### 3.10–14 Filhas de Moradia
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Aluguel","icon":"home","color":"#5C6BC0","parentId":"{{id_cat_moradia}}"}

> {%
client.global.set("id_cat_aluguel", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Condomínio","icon":"apartment","color":"#5C6BC0","parentId":"{{id_cat_moradia}}"}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Energia","icon":"bolt","color":"#5C6BC0","parentId":"{{id_cat_moradia}}"}

> {%
client.global.set("id_cat_energia", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Água/Gás","icon":"water_drop","color":"#5C6BC0","parentId":"{{id_cat_moradia}}"}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Internet","icon":"wifi","color":"#5C6BC0","parentId":"{{id_cat_moradia}}"}

###

### 3.15–17 Filhas de Alimentação
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Supermercado","icon":"shopping_cart","color":"#66BB6A","parentId":"{{id_cat_alimentacao}}"}

> {%
client.global.set("id_cat_supermercado", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Restaurante","icon":"restaurant","color":"#66BB6A","parentId":"{{id_cat_alimentacao}}"}

> {%
client.global.set("id_cat_restaurante", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Delivery","icon":"delivery_dining","color":"#66BB6A","parentId":"{{id_cat_alimentacao}}"}

> {%
client.global.set("id_cat_delivery", response.body.id);
%}

###

### 3.18–20 Filhas de Transporte
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Combustível","icon":"local_gas_station","color":"#FFA726","parentId":"{{id_cat_transporte}}"}

> {%
client.global.set("id_cat_combustivel", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Uber/99","icon":"local_taxi","color":"#FFA726","parentId":"{{id_cat_transporte}}"}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"IPVA / Seguro","icon":"receipt_long","color":"#FFA726","parentId":"{{id_cat_transporte}}"}

###

### 3.21–23 Filhas de Saúde
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Farmácia","icon":"local_pharmacy","color":"#EF5350","parentId":"{{id_cat_saude}}"}

> {%
client.global.set("id_cat_farmacia", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Plano de Saúde","icon":"health_and_safety","color":"#EF5350","parentId":"{{id_cat_saude}}"}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Academia","icon":"fitness_center","color":"#EF5350","parentId":"{{id_cat_saude}}"}

###

### 3.24–26 Filhas de Lazer
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Streaming","icon":"play_circle","color":"#AB47BC","parentId":"{{id_cat_lazer}}"}

> {%
client.global.set("id_cat_streaming", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Cinema/Shows","icon":"theaters","color":"#AB47BC","parentId":"{{id_cat_lazer}}"}

> {%
client.global.set("id_cat_cinema", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Viagens","icon":"flight","color":"#AB47BC","parentId":"{{id_cat_lazer}}"}

###

### 3.27–28 Filhas de Educação
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Cursos Online","icon":"computer","color":"#26C6DA","parentId":"{{id_cat_educacao}}"}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Livros","icon":"menu_book","color":"#26C6DA","parentId":"{{id_cat_educacao}}"}

###

### 3.29 Filha de Roupas & Casa
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Compras Gerais","icon":"shopping_bag","color":"#8D6E63","parentId":"{{id_cat_roupas}}"}

> {%
client.global.set("id_cat_compras_gerais", response.body.id);
%}

###

### 3.30–32 Filhas de Receitas
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Salário","icon":"payments","color":"#26A69A","parentId":"{{id_cat_receitas}}"}

> {%
client.global.set("id_cat_salario", response.body.id);
%}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Freelance","icon":"work","color":"#26A69A","parentId":"{{id_cat_receitas}}"}

###

POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Rendimentos","icon":"account_balance","color":"#26A69A","parentId":"{{id_cat_receitas}}"}

###

### 3.33 Filha de Assinaturas
POST http://localhost:8080/api/categories
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"name":"Serviços Digitais","icon":"cloud","color":"#BDBDBD","parentId":"{{id_cat_assinaturas}}"}

> {%
client.global.set("id_cat_servicos_dig", response.body.id);
%}

###

### 3.34 Arquivar Assinaturas (soft-delete em cascata)
POST http://localhost:8080/api/categories/{{id_cat_assinaturas}}/archive
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{}

### ── Bloco 4: Membros ─────────────────────────────────────────────────────

### 4.1 Convidar Ana
POST http://localhost:8080/invites
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"email":"ana@costa.com"}

> {%
client.global.set("token_invite_ana", response.body.token);
%}

###

### 4.2 Ana aceita o convite
POST http://localhost:8080/auth/accept-invite
Content-Type: application/json

{
  "token": "{{token_invite_ana}}",
  "name": "Ana Costa",
  "password": "costa123"
}

###

### 4.3 Convidar Pedro
POST http://localhost:8080/invites
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"email":"pedro@costa.com"}

> {%
client.global.set("token_invite_pedro", response.body.token);
%}

###

### 4.4 Pedro aceita o convite
POST http://localhost:8080/auth/accept-invite
Content-Type: application/json

{
  "token": "{{token_invite_pedro}}",
  "name": "Pedro Costa",
  "password": "costa123"
}

###

### 4.5 Convidar João (permanece PENDING — não executa accept-invite)
POST http://localhost:8080/invites
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"email":"joao@costa.com"}

### ── Bloco 5: Transações recorrentes (Bradesco + Carteira, Jan–Jun) ────────
### Execute este bloco 6 vezes trocando o campo "date" para cada mês:
### Jan=2026-01-05, Fev=2026-02-05, Mar=2026-03-05, Abr=2026-04-05, Mai=2026-05-05, Jun=2026-06-05
### Para Energia e Água/Gás em Jun: use "status":"PENDING"

### 5.1 Salário Carlos (repita para cada mês — aqui: Janeiro)
POST http://localhost:8080/api/transactions
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "description": "Salário Carlos",
  "amount": 8500.00,
  "date": "2026-01-05",
  "type": "INCOME",
  "status": "PAID",
  "accountId": "{{id_bradesco}}",
  "categoryId": "{{id_cat_salario}}"
}

### 5.2 Aluguel
POST http://localhost:8080/api/transactions
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "description": "Aluguel",
  "amount": 2200.00,
  "date": "2026-01-05",
  "type": "EXPENSE",
  "status": "PAID",
  "accountId": "{{id_bradesco}}",
  "categoryId": "{{id_cat_aluguel}}"
}

### [... demais transações mensais seguem o mesmo padrão — ver V10__seed_dev.sql para lista completa]

### ── Bloco 6: Parcelamentos ───────────────────────────────────────────────

### 6.1 Notebook Samsung 12x (Nubank, 2026-02-02)
POST http://localhost:8080/api/transactions
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "description": "Notebook Samsung",
  "amount": 350.00,
  "date": "2026-02-02",
  "type": "EXPENSE",
  "status": "PENDING",
  "accountId": "{{id_nubank}}",
  "categoryId": "{{id_cat_compras_gerais}}",
  "totalInstallments": 12
}

###

### 6.2 Geladeira Brastemp 6x (Inter, 2026-03-05)
POST http://localhost:8080/api/transactions
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "description": "Geladeira Brastemp",
  "amount": 280.00,
  "date": "2026-03-05",
  "type": "EXPENSE",
  "status": "PENDING",
  "accountId": "{{id_inter}}",
  "categoryId": "{{id_cat_compras_gerais}}",
  "totalInstallments": 6
}

### ── Bloco 7: Transferências (Bradesco → XP, Jan–Jun) ────────────────────
### Execute para cada mês trocando "date" (2026-01-10 a 2026-06-10)

### 7.1 Aporte mensal (Janeiro)
POST http://localhost:8080/api/transfers
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{
  "fromAccountId": "{{id_bradesco}}",
  "toAccountId": "{{id_xp}}",
  "amount": 500.00,
  "date": "2026-01-10",
  "description": "Aporte XP Investimentos"
}

### ── Bloco 8: Ciclo de vida das faturas ──────────────────────────────────
### Primeiro busque os IDs das faturas:

### 8.1 Listar faturas Nubank (anote os IDs antes de pagar/fechar)
GET http://localhost:8080/api/invoices?accountId={{id_nubank}}
Authorization: Bearer {{token_carlos}}

###

### 8.2 Pagar fatura Nubank Jan (substitua {ID} pelo id retornado no 8.1)
POST http://localhost:8080/api/invoices/{ID_NUBANK_JAN}/pay
Authorization: Bearer {{token_carlos}}
Content-Type: application/json

{"sourceAccountId":"{{id_bradesco}}"}

### [repita para Nubank Fev, Mar, Abr e Inter Mar, Abr]

###

### 8.3 Fechar fatura Nubank Mai
POST http://localhost:8080/api/invoices/{ID_NUBANK_MAI}/close
Authorization: Bearer {{token_carlos}}

### [repita para Inter Mai]

### ── Bloco 9: Verificações ────────────────────────────────────────────────

### 9.1 Dashboard
GET http://localhost:8080/api/dashboard?startDate=2026-06-01&endDate=2026-06-30
Authorization: Bearer {{token_carlos}}

###

### 9.2 Transações Nubank Junho
GET http://localhost:8080/api/transactions?accountId={{id_nubank}}&startDate=2026-06-01&endDate=2026-06-30
Authorization: Bearer {{token_carlos}}

###

### 9.3 Faturas Nubank (deve mostrar 7 faturas: Dez/2025 a Jun/2026)
GET http://localhost:8080/api/invoices?accountId={{id_nubank}}
Authorization: Bearer {{token_carlos}}

###

### 9.4 Detalhe fatura Nubank Mai (deve estar CLOSED com total 745)
GET http://localhost:8080/api/invoices/{ID_NUBANK_MAI}
Authorization: Bearer {{token_carlos}}

###

### 9.5 Membros do tenant (deve mostrar Carlos, Ana, Pedro)
GET http://localhost:8080/api/members
Authorization: Bearer {{token_carlos}}

###

### 9.6 Convites (deve mostrar João como PENDING)
GET http://localhost:8080/invites
Authorization: Bearer {{token_carlos}}
```

- [ ] **Step 3: Criar docs/http/README.md**

```markdown
# HTTP Collection — Dataset Família Costa

Arquivo: `seed-dataset.http`

Compatível com **IntelliJ HTTP Client** e **VS Code REST Client** (extensão REST Client).

## Pré-requisitos

- Backend rodando em `http://localhost:8080`
- Banco de dados limpo (sem dados prévios)

## Ordem de execução

Execute os 9 blocos na sequência. Variáveis são capturadas automaticamente via `client.global.set`.

| Bloco | Descrição | Requests |
|-------|-----------|----------|
| 1 | Auth (register + login) | 2 |
| 2 | Contas (5 contas) | 5 |
| 3 | Categorias (33 categorias + archive) | 34 |
| 4 | Membros (convites + aceites) | 5 |
| 5 | Transações recorrentes (6 meses × 10 tipos) | 60+ |
| 6 | Parcelamentos (2 grupos) | 2 |
| 7 | Transferências (6 meses) | 6 |
| 8 | Ciclo de faturas (pay + close) | ~9 |
| 9 | Verificações (GETs) | 6 |

## Alternativa: SQL seed

Para dev com reset completo, use `V10__seed_dev.sql` via Flyway:

```bash
# Reset banco
docker exec -it $(docker ps -qf "name=postgres") psql -U admin -c "DROP DATABASE fintech; CREATE DATABASE fintech;"
# Subir app (aplica V10 automaticamente)
cd backend && ./mvnw spring-boot:run
```

Credenciais: `carlos@costa.com` / `costa123`
```

- [ ] **Step 4: Commit**

```bash
git add docs/http/
git commit -m "docs(seed): adiciona HTTP collection e README para dataset Família Costa"
```

---

## Checklist de cobertura final

Após aplicar o seed, confirmar via UI ou API:

| Feature | Como verificar |
|---------|----------------|
| Categorias arquivadas | `GET /api/categories?includeArchived=true` → Assinaturas com `archived:true` |
| Transação categoria arquivada | Editar Spotify Dez/2025 → select de categoria mostra "Assinaturas → Serviços Digitais" disabled |
| Transação null category | Listagem: "Compra diversa" sem ícone de categoria |
| CANCELLED | Listagem Nubank Jan → "Ingresso Show" com chip CANCELLED |
| Multi-autor | Transações Carteira Mar → Carlos R$75 e Ana R$65 |
| Parcelamento 12x | Nubank → expandir Notebook → progress bar 3/12 |
| Fatura CLOSED pronta | Faturas → Nubank Mai → botão "Pagar" disponível |
| countInLiquidBalance | Dashboard → totalAccountBalance = Bradesco + Carteira (sem Nubank/Inter/XP) |
