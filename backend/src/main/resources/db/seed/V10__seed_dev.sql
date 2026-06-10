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
  (c_supermercado, v_tenant, v_carlos, 'Supermercado', 'shopping_cart',  '#66BB6A', c_alimentacao),
  (c_restaurante,  v_tenant, v_carlos, 'Restaurante',  'restaurant',     '#66BB6A', c_alimentacao),
  (c_delivery,     v_tenant, v_carlos, 'Delivery',     'delivery_dining','#66BB6A', c_alimentacao);

-- Filhas — Transporte
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_combustivel, v_tenant, v_carlos, 'Combustível',  'local_gas_station','#FFA726', c_transporte),
  (c_uber,        v_tenant, v_carlos, 'Uber/99',      'local_taxi',       '#FFA726', c_transporte),
  (c_ipva,        v_tenant, v_carlos, 'IPVA / Seguro','receipt_long',     '#FFA726', c_transporte);

-- Filhas — Saúde
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_farmacia,    v_tenant, v_carlos, 'Farmácia',       'local_pharmacy',    '#EF5350', c_saude),
  (c_plano_saude, v_tenant, v_carlos, 'Plano de Saúde', 'health_and_safety', '#EF5350', c_saude),
  (c_academia,    v_tenant, v_carlos, 'Academia',       'fitness_center',    '#EF5350', c_saude);

-- Filhas — Lazer
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_streaming, v_tenant, v_carlos, 'Streaming',    'play_circle', '#AB47BC', c_lazer),
  (c_cinema,    v_tenant, v_carlos, 'Cinema/Shows', 'theaters',    '#AB47BC', c_lazer),
  (c_viagens,   v_tenant, v_carlos, 'Viagens',      'flight',      '#AB47BC', c_lazer);

-- Filhas — Educação
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_cursos, v_tenant, v_carlos, 'Cursos Online', 'computer',  '#26C6DA', c_educacao),
  (c_livros, v_tenant, v_carlos, 'Livros',        'menu_book', '#26C6DA', c_educacao);

-- Filhas — Roupas & Casa
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_compras_gerais, v_tenant, v_carlos, 'Compras Gerais', 'shopping_bag', '#8D6E63', c_roupas);

-- Filhas — Receitas
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_salario,     v_tenant, v_carlos, 'Salário',     'payments',       '#26A69A', c_receitas),
  (c_freelance,   v_tenant, v_carlos, 'Freelance',   'work',           '#26A69A', c_receitas),
  (c_rendimentos, v_tenant, v_carlos, 'Rendimentos', 'account_balance','#26A69A', c_receitas);

-- Filhas — Assinaturas
INSERT INTO categories (id, tenant_id, created_by, name, icon, color, parent_id) VALUES
  (c_servicos_dig, v_tenant, v_carlos, 'Serviços Digitais', 'cloud', '#BDBDBD', c_assinaturas);

-- Soft-delete em Assinaturas e Serviços Digitais (arquivadas)
UPDATE categories SET deleted_at = '2025-11-01 00:00:00'
WHERE id IN (c_assinaturas, c_servicos_dig);

-- ── 6. Faturas ─────────────────────────────────────────────────────────────
-- Nubank: closing_day=2, due_day=10
INSERT INTO invoices (id, account_id, tenant_id, reference_year, reference_month,
                      closing_date, due_date, status) VALUES
  (v_inv_nu_dec2025, v_nubank, v_tenant, 2025, 12, '2025-12-02', '2025-12-10', 'PAID'),
  (v_inv_nu_jan,     v_nubank, v_tenant, 2026,  1, '2026-01-02', '2026-01-10', 'PAID'),
  (v_inv_nu_feb,     v_nubank, v_tenant, 2026,  2, '2026-02-02', '2026-02-10', 'PAID'),
  (v_inv_nu_mar,     v_nubank, v_tenant, 2026,  3, '2026-03-02', '2026-03-10', 'PAID'),
  (v_inv_nu_apr,     v_nubank, v_tenant, 2026,  4, '2026-04-02', '2026-04-10', 'PAID'),
  (v_inv_nu_may,     v_nubank, v_tenant, 2026,  5, '2026-05-02', '2026-05-10', 'CLOSED'),
  (v_inv_nu_jun,     v_nubank, v_tenant, 2026,  6, '2026-06-02', '2026-06-10', 'OPEN');

-- Inter: closing_day=5, due_day=15
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

-- Farmácia — Ana (Mar/2026)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_ana,v_carteira,c_farmacia,'Farmácia',65.00,'2026-03-12','EXPENSE','PAID');

-- Compra diversa (sem categoria)
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_carteira,NULL,'Compra diversa',50.00,'2026-01-15','EXPENSE','PAID');

-- ── 10. Transações Nubank recorrentes (Jan–Jun) ────────────────────────────────
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
-- Spotify Dez/2025 — categoria arquivada
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status,invoice_id) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_servicos_dig,'Spotify Premium',
   21.90,'2025-12-01','EXPENSE','PAID',v_inv_nu_dec2025);

-- Ingresso show CANCELLED — sem invoice_id
INSERT INTO transactions (id,tenant_id,user_id,account_id,category_id,description,amount,date,type,status) VALUES
  (gen_random_uuid(),v_tenant,v_carlos,v_nubank,c_cinema,'Ingresso Show',120.00,'2026-01-15','EXPENSE','CANCELLED');
