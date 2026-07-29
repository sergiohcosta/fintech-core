-- Seed dev (perfil 'dev') — importação via CSV da Família Costa (Fase 2, Onda 5).
-- Roda depois de V26 (source_hash/source_filename) e V24 (import_batches/staged_transactions).
-- Ilustra o caminho CSV, ainda em revisão (EXTRACTED, staged PENDING) — diferente do V24
-- (imagem, já COMMITTED): aqui é o que o usuário vê na tela de revisão antes de lançar,
-- incluindo uma duplicata intra-batch (Onda 4) para o badge "Possível duplicata" ter dado real.
DO $$
DECLARE
  v_tenant  UUID := '10000000-0000-0000-0000-000000000001';  -- Família Costa
  v_carlos  UUID := '20000000-0000-0000-0000-000000000001';  -- Carlos (ADMIN)

  v_batch   UUID := 'a0000000-0000-0000-0000-000000000002';  -- série a0 = import_batches
  v_staged1 UUID := 'a1000000-0000-0000-0000-000000000003';  -- série a1 = staged_transactions
  v_staged2 UUID := 'a1000000-0000-0000-0000-000000000004';
  v_staged3 UUID := 'a1000000-0000-0000-0000-000000000005';
BEGIN

INSERT INTO import_batches
  (id, tenant_id, created_by, import_mode, source_type, extractor_used, extractor_version,
   status, source_hash, source_filename)
VALUES
  (v_batch, v_tenant, v_carlos, 'NEW_TRANSACTIONS', 'CSV',
   'csv_generic_v1', 'v1', 'EXTRACTED',
   'd8e60cfc81302cfbd7edb7ffe99a5392a8fe066f9f0b6f27ce0b8905cd073b4e',
   'extrato-junho-2026.csv');

-- 3 staged PENDING — ainda em revisão (nenhuma promovida). Confiança 1.0/0.7 reflete a régua
-- do CsvExtractor: coluna casada por header = 1.0; direção pelo sinal do valor = 0.7 (inferência,
-- diferente do OFX/FITID que tem contrato formal de sinal).
INSERT INTO staged_transactions
  (id, batch_id, tenant_id, fields, overall_confidence, requires_review, duplicate_candidate_of, status)
VALUES
  (v_staged1, v_batch, v_tenant,
   '{"amount":{"value":245.80,"confidence":1.0},"transaction_date":{"value":"2026-06-10","confidence":1.0},"description":{"value":"SUPERMERCADO ABC","confidence":1.0},"direction":{"value":"debit","confidence":0.7}}'::jsonb,
   1.0, false, NULL, 'PENDING'),
  (v_staged2, v_batch, v_tenant,
   '{"amount":{"value":58.30,"confidence":1.0},"transaction_date":{"value":"2026-06-12","confidence":1.0},"description":{"value":"FARMACIA SAUDE","confidence":1.0},"direction":{"value":"debit","confidence":0.7}}'::jsonb,
   1.0, false, NULL, 'PENDING'),
  -- Mesma data/valor/descrição da 1ª linha — o banco listou a compra duas vezes no extrato
  -- (caso real de dedup intra-batch, Onda 4): duplicate_candidate_of aponta pra primeira.
  (v_staged3, v_batch, v_tenant,
   '{"amount":{"value":245.80,"confidence":1.0},"transaction_date":{"value":"2026-06-10","confidence":1.0},"description":{"value":"SUPERMERCADO ABC","confidence":1.0},"direction":{"value":"debit","confidence":0.7}}'::jsonb,
   1.0, false, v_staged1, 'PENDING');

END $$;
