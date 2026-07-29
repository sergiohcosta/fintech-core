-- V26 — dedup por arquivo na importação (Fase 2, Onda 4).
--
-- Até aqui (Fase 1) só existia imagem, e reenviar o MESMO comprovante duas vezes era raro e
-- óbvio de perceber na revisão. Com CSV/OFX (arquivos de N transações), reimportar o mesmo
-- extrato sem querer duplica um mês inteiro de lançamentos — o pior desfecho possível num app
-- financeiro (saldo errado silenciosamente). source_hash = SHA-256 dos bytes do arquivo,
-- calculado ANTES de extrair (não gasta compute pra descobrir que era repetido).
--
-- Índice por (tenant_id, source_hash), não só source_hash: o MESMO extrato pode legitimamente
-- existir em dois tenants diferentes (duas famílias no mesmo banco, por exemplo) — o hash
-- nunca pode, sozinho, revelar ou bloquear isso entre tenants.

ALTER TABLE import_batches ADD COLUMN source_hash VARCHAR(64);
ALTER TABLE import_batches ADD COLUMN source_filename VARCHAR(255);

CREATE INDEX idx_import_batches_tenant_source_hash ON import_batches(tenant_id, source_hash);

COMMENT ON COLUMN import_batches.source_hash IS
    'SHA-256 (hex) dos bytes do arquivo original — chave de dedup por (tenant, hash). NULL para batches de mock/legado sem arquivo.';
COMMENT ON COLUMN import_batches.source_filename IS
    'Nome do arquivo enviado pelo usuário — proveniência, exibido na mensagem de conflito (409).';
