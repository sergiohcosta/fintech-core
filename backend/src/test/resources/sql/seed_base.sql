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
