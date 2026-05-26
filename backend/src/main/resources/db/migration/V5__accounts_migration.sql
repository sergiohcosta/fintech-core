-- V5__accounts_migration.sql
-- Migra credit_cards para accounts + credit_card_details
-- Atualiza transactions para referenciar accounts diretamente

-- 1. Criar tabela accounts
CREATE TABLE accounts (
    id                      UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name                    VARCHAR(100) NOT NULL,
    type                    VARCHAR(20)  NOT NULL,
    color                   VARCHAR(7),
    icon                    VARCHAR(50),
    count_in_liquid_balance BOOLEAN NOT NULL DEFAULT true,
    count_in_net_worth      BOOLEAN NOT NULL DEFAULT true,
    active                  BOOLEAN NOT NULL DEFAULT true,
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    created_by              UUID REFERENCES users(id),
    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_accounts_tenant       ON accounts(tenant_id);
CREATE INDEX idx_accounts_tenant_type  ON accounts(tenant_id, type);

-- 2. Criar tabela credit_card_details
CREATE TABLE credit_card_details (
    id               UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    account_id       UUID NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    brand            VARCHAR(20),
    last_four_digits VARCHAR(4),
    limit_amount     DECIMAL(19, 2),
    closing_day      INTEGER,
    due_day          INTEGER
);

-- 3. Migrar credit_cards → accounts
INSERT INTO accounts (
    id, name, type, color,
    count_in_liquid_balance, count_in_net_worth, active,
    tenant_id, created_by, created_at, updated_at
)
SELECT
    id, name, 'CREDIT_CARD', color,
    false, true, true,
    tenant_id, user_id, created_at, updated_at
FROM credit_cards;

-- 4. Migrar credit_cards → credit_card_details
INSERT INTO credit_card_details (account_id, brand, last_four_digits, limit_amount, closing_day, due_day)
SELECT id, brand, last_four_digits, limit_amount, closing_day, due_day
FROM credit_cards;

-- 5. Criar conta-padrão por tenant com transações sem credit_card_id
INSERT INTO accounts (name, type, count_in_liquid_balance, count_in_net_worth, active, tenant_id, created_at, updated_at)
SELECT DISTINCT
    'Conta Padrão',
    'CHECKING',
    true,
    true,
    true,
    t.tenant_id,
    NOW(),
    NOW()
FROM transactions t
WHERE t.credit_card_id IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM accounts a
      WHERE a.tenant_id = t.tenant_id
        AND a.name = 'Conta Padrão'
  );

-- 6. Adicionar colunas em transactions
ALTER TABLE transactions ADD COLUMN account_id UUID REFERENCES accounts(id);
ALTER TABLE transactions ADD COLUMN transfer_id UUID;

-- 7. Vincular transações com credit_card_id ao account correspondente
UPDATE transactions
SET account_id = credit_card_id
WHERE credit_card_id IS NOT NULL;

-- 8. Vincular transações órfãs à conta-padrão do tenant
UPDATE transactions t
SET account_id = (
    SELECT a.id FROM accounts a
    WHERE a.tenant_id = t.tenant_id
      AND a.name = 'Conta Padrão'
    LIMIT 1
)
WHERE t.credit_card_id IS NULL;

-- 9. Tornar account_id obrigatório e remover credit_card_id
ALTER TABLE transactions ALTER COLUMN account_id SET NOT NULL;
ALTER TABLE transactions DROP COLUMN credit_card_id;

-- 10. Remover tabela legada
DROP TABLE credit_cards;
