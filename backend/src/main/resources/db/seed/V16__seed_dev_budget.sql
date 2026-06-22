-- =============================================================
-- V16__seed_dev_budget.sql — Ciclo de planejamento de Junho 2026
-- Carregado APENAS no perfil dev (spring.flyway.locations).
-- Depende de V13 (transações da Família Costa já inseridas).
--
-- Idempotente: se já existir um ciclo aberto ou itens no ciclo,
-- este script não os altera — preserva dados criados manualmente.
-- Em banco fresh (após reset), cria o cenário completo.
-- =============================================================

DO $$
DECLARE
  -- Referências fixas (mesmas séries do V13)
  v_tenant     UUID := '10000000-0000-0000-0000-000000000001';
  v_carlos     UUID := '20000000-0000-0000-0000-000000000001';
  v_bradesco   UUID := '30000000-0000-0000-0000-000000000001';
  v_nubank     UUID := '30000000-0000-0000-0000-000000000003';
  v_inter      UUID := '30000000-0000-0000-0000-000000000004';

  c_salario        UUID := '40000000-0000-0000-0000-000000000081';
  c_aluguel        UUID := '40000000-0000-0000-0000-000000000011';
  c_condominio     UUID := '40000000-0000-0000-0000-000000000012';
  c_internet       UUID := '40000000-0000-0000-0000-000000000015';
  c_supermercado   UUID := '40000000-0000-0000-0000-000000000021';
  c_combustivel    UUID := '40000000-0000-0000-0000-000000000031';
  c_plano_saude    UUID := '40000000-0000-0000-0000-000000000042';
  c_compras_gerais UUID := '40000000-0000-0000-0000-000000000071';

  v_grp_notebook  UUID := '60000000-0000-0000-0000-000000000001';
  v_grp_geladeira UUID := '60000000-0000-0000-0000-000000000002';

  -- Ciclo e itens com UUIDs fixos (série a0/b0/c0)
  v_cycle_jun     UUID := 'a0000000-0000-0000-0000-000000000001';

  v_ri_salario    UUID := 'c0000000-0000-0000-0000-000000000001';
  v_ri_aluguel    UUID := 'c0000000-0000-0000-0000-000000000002';
  v_ri_condominio UUID := 'c0000000-0000-0000-0000-000000000003';
  v_ri_internet   UUID := 'c0000000-0000-0000-0000-000000000004';

  v_bi_salario      UUID := 'b0000000-0000-0000-0000-000000000001';
  v_bi_aluguel      UUID := 'b0000000-0000-0000-0000-000000000002';
  v_bi_condominio   UUID := 'b0000000-0000-0000-0000-000000000003';
  v_bi_internet     UUID := 'b0000000-0000-0000-0000-000000000004';
  v_bi_supermercado UUID := 'b0000000-0000-0000-0000-000000000005';
  v_bi_combustivel  UUID := 'b0000000-0000-0000-0000-000000000006';
  v_bi_plano_saude  UUID := 'b0000000-0000-0000-0000-000000000007';
  v_bi_notebook     UUID := 'b0000000-0000-0000-0000-000000000008';
  v_bi_geladeira    UUID := 'b0000000-0000-0000-0000-000000000009';

  -- ID do ciclo ativo (descoberto ou criado)
  v_cycle_id UUID;

  -- UUIDs das transações de junho — descobertos por subquery
  v_tx_salario_jun    UUID;
  v_tx_aluguel_jun    UUID;
  v_tx_condominio_jun UUID;
  v_tx_internet_jun   UUID;

BEGIN

-- ── 1. Itens recorrentes ───────────────────────────────────────────────────
-- Insere apenas se ainda não existirem (banco fresh ou após reset)
IF NOT EXISTS (SELECT 1 FROM recurring_budget_items WHERE tenant_id = v_tenant) THEN
  INSERT INTO recurring_budget_items
    (id, tenant_id, description, amount, type, category_id, account_id, day_of_month, active, created_by)
  VALUES
    (v_ri_salario,    v_tenant, 'Salário Carlos', 8500.00, 'INCOME',  c_salario,    v_bradesco,  5, true, v_carlos),
    (v_ri_aluguel,    v_tenant, 'Aluguel',        2200.00, 'EXPENSE', c_aluguel,    v_bradesco,  5, true, v_carlos),
    (v_ri_condominio, v_tenant, 'Condomínio',      350.00, 'EXPENSE', c_condominio, v_bradesco,  5, true, v_carlos),
    (v_ri_internet,   v_tenant, 'Internet',        120.00, 'EXPENSE', c_internet,   v_bradesco, 10, true, v_carlos);
END IF;

-- ── 2. Ciclo de junho — usa existente ou cria novo ─────────────────────────
SELECT id INTO v_cycle_id
FROM budget_cycles
WHERE tenant_id = v_tenant AND status = 'OPEN'
LIMIT 1;

IF v_cycle_id IS NULL THEN
  -- opening_balance = caixa líquido PAID acumulado ANTES do ciclo (date < start_date),
  -- exatamente o que open() calcula via sumLiquidBalanceByTenant. Para o dataset Família Costa
  -- (contas líquidas Bradesco Corrente + Carteira, transações PAID até 2026-05-31) = 18123.10.
  INSERT INTO budget_cycles (id, tenant_id, start_date, end_date, opening_balance, status, created_by)
  VALUES (v_cycle_jun, v_tenant, '2026-06-01', '2026-06-30', 18123.10, 'OPEN', v_carlos);
  v_cycle_id := v_cycle_jun;
END IF;

-- ── 3. Budget items — só insere se o ciclo ainda não tiver itens ───────────
-- Preserva itens criados manualmente em banco com dados reais
IF NOT EXISTS (SELECT 1 FROM budget_items WHERE cycle_id = v_cycle_id) THEN

  -- Localiza transações de junho pelo conteúdo (V13 usou gen_random_uuid())
  SELECT id INTO v_tx_salario_jun    FROM transactions WHERE tenant_id=v_tenant AND account_id=v_bradesco AND description='Salário Carlos' AND date='2026-06-05' LIMIT 1;
  SELECT id INTO v_tx_aluguel_jun    FROM transactions WHERE tenant_id=v_tenant AND account_id=v_bradesco AND description='Aluguel'         AND date='2026-06-05' LIMIT 1;
  SELECT id INTO v_tx_condominio_jun FROM transactions WHERE tenant_id=v_tenant AND account_id=v_bradesco AND description='Condomínio'      AND date='2026-06-05' LIMIT 1;
  SELECT id INTO v_tx_internet_jun   FROM transactions WHERE tenant_id=v_tenant AND account_id=v_bradesco AND description='Internet'        AND date='2026-06-10' AND transfer_id IS NULL LIMIT 1;

  -- RECURRING realizados (vinculados às transações existentes)
  INSERT INTO budget_items
    (id, cycle_id, tenant_id, description, amount, type, category_id, account_id,
     expected_date, source, status, recurring_item_id, transaction_id)
  VALUES
    (v_bi_salario,    v_cycle_id, v_tenant, 'Salário Carlos', 8500.00, 'INCOME',  c_salario,    v_bradesco, '2026-06-05', 'RECURRING', 'REALIZED', v_ri_salario,    v_tx_salario_jun),
    (v_bi_aluguel,    v_cycle_id, v_tenant, 'Aluguel',        2200.00, 'EXPENSE', c_aluguel,    v_bradesco, '2026-06-05', 'RECURRING', 'REALIZED', v_ri_aluguel,    v_tx_aluguel_jun),
    (v_bi_condominio, v_cycle_id, v_tenant, 'Condomínio',      350.00, 'EXPENSE', c_condominio, v_bradesco, '2026-06-05', 'RECURRING', 'REALIZED', v_ri_condominio, v_tx_condominio_jun),
    (v_bi_internet,   v_cycle_id, v_tenant, 'Internet',        120.00, 'EXPENSE', c_internet,   v_bradesco, '2026-06-10', 'RECURRING', 'REALIZED', v_ri_internet,   v_tx_internet_jun),
    -- MANUAL pendentes (planejados, sem transação vinculada)
    (v_bi_supermercado, v_cycle_id, v_tenant, 'Supermercado',   550.00, 'EXPENSE', c_supermercado, v_bradesco, '2026-06-15', 'MANUAL', 'PENDING', NULL, NULL),
    (v_bi_combustivel,  v_cycle_id, v_tenant, 'Combustível',    250.00, 'EXPENSE', c_combustivel,  v_bradesco, '2026-06-15', 'MANUAL', 'PENDING', NULL, NULL),
    -- SKIPPED: planejado e ignorado neste ciclo
    (v_bi_plano_saude,  v_cycle_id, v_tenant, 'Plano de Saúde', 890.00, 'EXPENSE', c_plano_saude,  v_bradesco, '2026-06-20', 'MANUAL', 'SKIPPED', NULL, NULL);

  -- INSTALLMENT: reproduz o que populateInstallmentItems() faz ao abrir o ciclo
  INSERT INTO budget_items
    (id, cycle_id, tenant_id, description, amount, type, category_id, account_id,
     expected_date, source, status, installment_group_id)
  VALUES
    (v_bi_notebook,  v_cycle_id, v_tenant, 'Notebook Samsung',   350.00, 'EXPENSE', c_compras_gerais, v_nubank, '2026-06-10', 'INSTALLMENT', 'PENDING', v_grp_notebook),
    (v_bi_geladeira, v_cycle_id, v_tenant, 'Geladeira Brastemp', 280.00, 'EXPENSE', c_compras_gerais, v_inter,  '2026-06-15', 'INSTALLMENT', 'PENDING', v_grp_geladeira);

END IF;

END $$;
