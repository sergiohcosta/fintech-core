-- Fundação da Extração/Conciliação (Fase 0). Staging SEPARADO do núcleo:
-- o dado extraído é probabilístico (carrega confidence, requires_review) e não pode
-- contaminar `transactions`, de onde todo saldo/fatura/dashboard lê sem esperar dado
-- incompleto. A staged só é PROMOVIDA para `transactions` no commit (Fase 1). Assim,
-- `transactions` continua sendo, linha a linha, apenas fato confirmado.

CREATE TABLE import_batches (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants(id),   -- isolamento de tenant
    created_by        UUID         REFERENCES users(id),             -- autoria
    import_mode       VARCHAR(20)  NOT NULL,                         -- NEW_TRANSACTIONS | RECONCILIATION
    source_type       VARCHAR(20)  NOT NULL,                         -- IMAGE | PDF_TEXT | PDF_SCANNED | CSV | OFX | AUDIO
    extractor_used    VARCHAR(100),                                  -- proveniência (ex: vision_ollama_qwen2.5vl)
    extractor_version VARCHAR(50),                                   -- proveniência (versão do prompt/modelo)
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',       -- PENDING | EXTRACTED | REVIEWED | COMMITTED | FAILED
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_import_batch_mode   CHECK (import_mode IN ('NEW_TRANSACTIONS','RECONCILIATION')),
    CONSTRAINT chk_import_batch_source CHECK (source_type IN ('IMAGE','PDF_TEXT','PDF_SCANNED','CSV','OFX','AUDIO')),
    CONSTRAINT chk_import_batch_status CHECK (status IN ('PENDING','EXTRACTED','REVIEWED','COMMITTED','FAILED'))
);
-- Consulta comum: "batches de tal status do tenant" (ex.: pendentes de revisão).
CREATE INDEX idx_import_batches_tenant_status ON import_batches(tenant_id, status);

CREATE TABLE staged_transactions (
    id                            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id                      UUID         NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    -- tenant_id DENORMALIZADO de propósito (spec §3): é a defesa nº1 contra vazamento de
    -- tenant. Toda leitura de staged filtra tenant_id DIRETAMENTE, sem depender de um JOIN
    -- correto em import_batches estar presente em todo repository method escrito hoje ou no
    -- futuro. Um JOIN esquecido passa despercebido em revisão; um WHERE tenant_id ausente é
    -- muito mais fácil de pegar em teste e code review.
    tenant_id                     UUID         NOT NULL REFERENCES tenants(id),
    fields                        JSONB        NOT NULL,   -- {value, confidence} por campo (amount, currency, transaction_date, ...)
    suggested_category_code       VARCHAR(100),            -- sugestão heurística (Fase 4)
    suggested_category_confidence NUMERIC,
    overall_confidence            NUMERIC,                 -- agregado da transação
    requires_review               BOOLEAN      NOT NULL,   -- DERIVADO no código por threshold, nunca gravado pelo modelo (decisão f)
    duplicate_candidate_of        UUID,                    -- reservado — dedup real só na Fase 4 (custo zero agora)
    promoted_transaction_id       UUID         REFERENCES transactions(id),  -- preenchido no commit (Fase 1)
    status                        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING | CONFIRMED | DISCARDED
    created_at                    TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_staged_transaction_status CHECK (status IN ('PENDING','CONFIRMED','DISCARDED'))
);
CREATE INDEX idx_staged_transactions_batch ON staged_transactions(batch_id);

-- Data de LANÇAMENTO/fechamento, distinta da data da COMPRA (`date`). Separadas desde o
-- dia 1 porque a conciliação de cartão (Fase 5) precisa das duas; adicionar a coluna agora
-- (nullable, ainda não consumida) custa uma migration trivial — adiar custaria uma migração
-- dolorosa numa tabela já com volume. Mesmo racional do `recurrence_occurrence`.
ALTER TABLE transactions ADD COLUMN posting_date DATE NULL;
