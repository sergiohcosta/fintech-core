-- V21: Migra recurring_budget_items → recurrence_rules (big bang).
-- Executa APÓS V16 (seed dev), que popula recurring_budget_items com dados da Família Costa.
-- Após esta migration, a tabela recurring_budget_items não existe mais.

-- 1. Cria RecurrenceRule equivalente para cada RecurringBudgetItem.
--    BYMONTHDAY={day_of_month} é o equivalente RRULE de "todo dia N do mês".
INSERT INTO recurrence_rules (
    id, tenant_id, description, base_amount, type,
    category_id, account_id, rrule, start_date, status,
    created_by, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    tenant_id,
    description,
    amount,
    type,
    category_id,
    account_id,
    'FREQ=MONTHLY;BYMONTHDAY=' || day_of_month,
    CURRENT_DATE,
    CASE WHEN active THEN 'ACTIVE' ELSE 'CANCELLED' END,
    created_by,
    created_at,
    updated_at
FROM recurring_budget_items
WHERE account_id IS NOT NULL;

-- 2. Adiciona colunas novas em budget_items.
ALTER TABLE budget_items
    ADD COLUMN recurrence_rule_id UUID REFERENCES recurrence_rules(id),
    ADD COLUMN recurrence_occurrence_date DATE;

-- 3. Mapeia BudgetItems existentes para as novas RecurrenceRules.
--    Chave de ligação: recurring_item_id → tenant + description + type + base_amount.
--    Funciona para o dataset controlado (sem duplicatas de descrição/tipo/valor por tenant).
UPDATE budget_items bi
SET recurrence_rule_id = rr.id
FROM recurring_budget_items rbi
JOIN recurrence_rules rr
    ON rr.tenant_id = rbi.tenant_id
   AND rr.description = rbi.description
   AND rr.type = rbi.type
   AND rr.base_amount = rbi.amount
WHERE bi.recurring_item_id = rbi.id;

-- 4. Remove coluna legada e tabela.
ALTER TABLE budget_items DROP COLUMN recurring_item_id;
DROP TABLE recurring_budget_items;

-- 5. Índice de suporte para queries de itens por regra.
CREATE INDEX idx_budget_items_recurrence_rule ON budget_items(recurrence_rule_id);
