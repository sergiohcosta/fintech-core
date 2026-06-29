-- Seed dev (perfil 'dev') — recorrências da Família Costa.
-- Roda depois de V19 (schema do motor de recorrência). UUIDs predefinidos na série 9x
-- para o motor; tenant/conta/categoria/usuário referenciam os literais já semeados no V13.

-- Netflix: assinatura mensal infinita no dia 15, na conta corrente do Carlos (Bradesco).
-- Conta CHECKING (não cartão) para que "Confirmar" no dev não dependa de fatura.
INSERT INTO recurrence_rules
    (id, tenant_id, description, base_amount, type, category_id, account_id, rrule, start_date, status, created_by)
VALUES
    ('90000000-0000-0000-0000-000000000001',
     '10000000-0000-0000-0000-000000000001',  -- tenant Família Costa
     'Netflix', 55.90, 'EXPENSE',
     '40000000-0000-0000-0000-000000000051',  -- categoria Streaming
     '30000000-0000-0000-0000-000000000001',  -- conta Bradesco Corrente (CHECKING)
     'FREQ=MONTHLY;BYMONTHDAY=15', '2026-01-15', 'ACTIVE',
     '20000000-0000-0000-0000-000000000001'); -- Carlos

-- Exemplo de pulo (EXDATE): a ocorrência de fevereiro/2026 foi pulada. A projeção deve
-- omitir 2026-02-15 mas voltar a desenhar a fantasma nos meses seguintes.
INSERT INTO recurrence_exceptions (id, rule_id, occurrence_date) VALUES
    ('91000000-0000-0000-0000-000000000001',
     '90000000-0000-0000-0000-000000000001', '2026-02-15');
