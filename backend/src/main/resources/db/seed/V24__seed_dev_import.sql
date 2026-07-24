-- Seed dev (perfil 'dev') — importação/extração da Família Costa (Fase 0).
-- Roda depois de V23 (schema de import). Ilustra um batch JÁ COMMITTED: uma "foto do extrato"
-- foi extraída e suas 2 transações promovidas para transações reais que o V13 já semeou.
--
-- promoted_transaction_id resolve as transações-alvo por CHAVE NATURAL (descrição + data),
-- porque o V13 gera os UUIDs de transação com gen_random_uuid() — não há literal fixo a
-- referenciar. As duas transações escolhidas (Salário e Aluguel de jun/2026) existem sempre.
DO $$
DECLARE
  v_tenant  UUID := '10000000-0000-0000-0000-000000000001';  -- Família Costa
  v_carlos  UUID := '20000000-0000-0000-0000-000000000001';  -- Carlos (ADMIN)

  v_batch   UUID := 'a0000000-0000-0000-0000-000000000001';  -- série a0 = import_batches
  v_staged1 UUID := 'a1000000-0000-0000-0000-000000000001';  -- série a1 = staged_transactions
  v_staged2 UUID := 'a1000000-0000-0000-0000-000000000002';

  v_txn_salario UUID;
  v_txn_aluguel UUID;
BEGIN

SELECT id INTO v_txn_salario FROM transactions
  WHERE tenant_id = v_tenant AND description = 'Salário Carlos' AND date = '2026-06-05';
SELECT id INTO v_txn_aluguel FROM transactions
  WHERE tenant_id = v_tenant AND description = 'Aluguel' AND date = '2026-06-05';

INSERT INTO import_batches
  (id, tenant_id, created_by, import_mode, source_type, extractor_used, extractor_version, status)
VALUES
  (v_batch, v_tenant, v_carlos, 'NEW_TRANSACTIONS', 'IMAGE',
   'vision_ollama_qwen2.5vl', '2026-07-24', 'COMMITTED');

-- 2 staged CONFIRMED (já promovidas). requires_review=false é coerente com a derivação por
-- threshold (overall ≥ 0.90 e amount conf ≥ 0.95); aqui o valor é literal por ser seed.
INSERT INTO staged_transactions
  (id, batch_id, tenant_id, fields, suggested_category_code, suggested_category_confidence,
   overall_confidence, requires_review, promoted_transaction_id, status)
VALUES
  (v_staged1, v_batch, v_tenant,
   '{"amount":{"value":8500.00,"confidence":0.99},"transaction_date":{"value":"2026-06-05","confidence":0.98},"description":{"value":"Salário Carlos","confidence":0.96},"direction":{"value":"credit","confidence":0.99}}'::jsonb,
   'salario', 0.90, 0.98, false, v_txn_salario, 'CONFIRMED'),
  (v_staged2, v_batch, v_tenant,
   '{"amount":{"value":2200.00,"confidence":0.97},"transaction_date":{"value":"2026-06-05","confidence":0.95},"description":{"value":"Aluguel","confidence":0.93},"direction":{"value":"debit","confidence":0.98}}'::jsonb,
   'aluguel', 0.85, 0.95, false, v_txn_aluguel, 'CONFIRMED');

END $$;
