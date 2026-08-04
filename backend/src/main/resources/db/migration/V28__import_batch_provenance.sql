-- V28 — proveniência estruturada do extrator no batch (Onda 3 do plano "extração Gemini
-- primário / Ollama fallback").
--
-- Motivação: `extractor_used` (ex.: "vision_ollama_qwen2.5vl") já carrega a proveniência, mas só
-- como STRING legível para humano — não dá pra fazer `GROUP BY provider` nem responder "quantos
-- batches caíram no fallback?" sem um `LIKE` frágil. Este V28 adiciona colunas ESTRUTURADAS,
-- ADITIVAS ao lado de `extractor_used` (que permanece — é o texto que humano lê no log/suporte;
-- as colunas novas são a forma CONSULTÁVEL do mesmo fato, não substituem uma pela outra).
--
-- O schema vem ANTES da lógica de fallback (Onda 4) de propósito: quando o fallback for
-- implementado, ele já grava no lugar definitivo, em vez de produzir o dado e voltar depois para
-- persistir.
--
-- Todas as colunas são NULLABLE: nenhuma migration imutável é reescrita, e cada uma tem um motivo
-- concreto para não ter dado em algum batch (ver comentários por coluna).

ALTER TABLE import_batches ADD COLUMN extractor_provider VARCHAR(30);
ALTER TABLE import_batches ADD COLUMN extractor_model VARCHAR(100);
ALTER TABLE import_batches ADD COLUMN fallback_from VARCHAR(30);
ALTER TABLE import_batches ADD COLUMN fallback_reason VARCHAR(200);
ALTER TABLE import_batches ADD COLUMN extraction_latency_ms INTEGER;

COMMENT ON COLUMN import_batches.extractor_provider IS
    'Provider que gerou o dado final (gemini, ollama, csv, ofx, pdf_text) — fato de primeira classe para GROUP BY/estatística. NULL só em batches muito antigos ainda não backfillados (nenhum nesta base, ver UPDATE abaixo).';
COMMENT ON COLUMN import_batches.extractor_model IS
    'Modelo efetivo usado pelo provider (ex.: qwen2.5vl, gemini-2.5-flash). NULL para parser determinístico (CSV/OFX/PDF texto) — não existe "modelo" nesse caminho.';
COMMENT ON COLUMN import_batches.fallback_from IS
    'Provider que foi tentado ANTES e falhou por indisponibilidade, cedendo a vez a este. NULL = não houve fallback (caminho feliz do primário, ou extrator sem conceito de fallback). Só este campo decide "houve fallback?" — de propósito, em vez de um booleano separado que pudesse divergir dele.';
COMMENT ON COLUMN import_batches.fallback_reason IS
    'Motivo curto do fallback (quota, unavailable, auth, rejected_input) quando fallback_from não é NULL. Populado só a partir da Onda 4 (política de fallback); esta Onda só abre a coluna.';
COMMENT ON COLUMN import_batches.extraction_latency_ms IS
    'Tempo (ms) da chamada ao provider que VENCEU (gerou o dado usado) — não soma tentativas de fallback. NULL em parser determinístico ou em batch legado onde a métrica não foi medida (honesto: fingir um número seria pior que o NULL).';

-- Backfill: deriva extractor_provider do extractor_used já existente nos batches seed (V24, V27).
-- Determinístico — não é "inventar" dado, é extrair o que já estava implícito na string. Os
-- demais campos novos (extractor_model, fallback_*, extraction_latency_ms) ficam NULL aqui: não
-- foram medidos nesses batches, e fingir o contrário seria pior que a ausência (ver spec §5.1).
-- extractor_model do Ollama é preenchido junto porque também está codificado na própria string.
UPDATE import_batches
   SET extractor_provider = 'ollama',
       extractor_model = 'qwen2.5vl'
 WHERE extractor_used LIKE 'vision_ollama_%';

UPDATE import_batches
   SET extractor_provider = 'csv'
 WHERE extractor_used LIKE 'csv_%';

UPDATE import_batches
   SET extractor_provider = 'ofx'
 WHERE extractor_used LIKE 'ofx_%';

-- Nenhum batch PDF_TEXT existe ainda nesta base (Fase 3 não tem seed próprio), mas a regra é a
-- mesma dos parsers determinísticos acima — incluída por completude/consistência, sem custo.
UPDATE import_batches
   SET extractor_provider = 'pdf_text'
 WHERE extractor_used LIKE 'pdf_text_%';
