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
