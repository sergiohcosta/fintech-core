-- Seed dev (perfil 'dev') — proveniência estruturada dos batches de importação (V28, Onda 3 do
-- plano "extração Gemini primário / Ollama fallback").
--
-- V28 já fez o BACKFILL genérico (extractor_provider/extractor_model derivados de extractor_used
-- por padrão de string) nos batches existentes do V24 (imagem/ollama) e V27 (CSV). Este V29 é a
-- continuação exigida pela regra inviolável de `dataset.md` ("coluna nova em tabela existente →
-- seed atualizado NA MESMA ENTREGA"): como V24/V27 já foram aplicados (migrations imutáveis), a
-- forma de "atualizar os INSERTs" é um UPDATE em vez de reescrevê-los — precedente do V18 sobre
-- o V16.
--
-- O que o backfill do V28 NÃO conseguiu inferir (porque não está codificado em extractor_used)
-- é preenchido aqui: `fallback_from`/`fallback_reason` no batch de imagem (o único caminho com
-- MÚLTIPLOS providers nesta feature — CSV/OFX são parsers determinísticos, sem conceito de
-- fallback) e `extraction_latency_ms`, que é sempre um dado medido, nunca inferível de string.
DO $$
DECLARE
  v_batch_imagem UUID := 'a0000000-0000-0000-0000-000000000001';  -- V24 — vision_ollama_qwen2.5vl
  v_batch_csv    UUID := 'a0000000-0000-0000-0000-000000000002';  -- V27 — csv_generic_v1
BEGIN

-- Batch de imagem (V24): ilustra o caso "Gemini primário falhou (cota do free tier esgotada) e
-- o Ollama do homelab assumiu como fallback" — o cenário central desta feature. extractor_provider/
-- extractor_model já vieram 'ollama'/'qwen2.5vl' do backfill do V28; aqui só somamos o que o
-- backfill não podia saber.
UPDATE import_batches
   SET fallback_from = 'gemini',
       fallback_reason = 'quota: 429 RESOURCE_EXHAUSTED — cota diária do free tier esgotada',
       extraction_latency_ms = 2450
 WHERE id = v_batch_imagem;

-- Batch CSV (V27): caminho feliz, sem fallback (parser determinístico não tem provider
-- alternativo). extractor_provider = 'csv' já veio do backfill do V28; nada a somar aqui além de
-- confirmar a ausência de fallback_from/latência (permanecem NULL — não há "chamada a modelo"
-- para medir num parser determinístico).
UPDATE import_batches
   SET fallback_from = NULL,
       fallback_reason = NULL,
       extraction_latency_ms = NULL
 WHERE id = v_batch_csv;

END $$;
