-- V30 — fatura-alvo do documento importado (Itaú), spec 2026-08-09-itau-fatura-ancora-por-
-- documento.
--
-- Motivação: o commit de importação reusava resolveInvoiceMonth (mesmo caminho de um
-- lançamento manual) para decidir em que fatura cada linha cai — recalculando uma decisão que
-- o PRÓPRIO documento já tomou (a fatura Itaú tem um vencimento único, impresso). Isso mandava
-- parcelas em andamento (>1/N, data de compra antiga) para faturas erradas, mesmo com o
-- closingDay da conta corretamente configurado (spec §1).
--
-- NULLABLE, sem backfill: só ItauFaturaTemplate tem o conceito de "1 documento = 1 fatura com
-- vencimento único" (spec, decisão d). CSV/OFX/imagem/heurística genérica de PDF são
-- extratos/comprovantes sem esse conceito — permanecem NULL, e o commit cai no caminho
-- existente (resolveInvoiceMonth), comportamento idêntico ao de hoje.
--
-- Por que V24 (seed batch de imagem) e V27 (seed batch CSV) NÃO são tocados aqui: são
-- migrations imutáveis, e o conceito de fatura-alvo genuinamente NÃO SE APLICA a nenhum dos
-- dois — mesmo precedente do V28, que só fez backfill onde o dado era DERIVÁVEL do que já
-- existia (extractor_provider a partir de extractor_used). Aqui não há nada a derivar: NULL é
-- o valor correto, não uma lacuna.

ALTER TABLE import_batches ADD COLUMN target_invoice_reference_year INTEGER;
ALTER TABLE import_batches ADD COLUMN target_invoice_reference_month INTEGER;

COMMENT ON COLUMN import_batches.target_invoice_reference_year IS
    'Ano de referência da fatura que O DOCUMENTO IMPORTADO representa (vencimento impresso, não recalculado por closingDay) — só ItauFaturaTemplate popula. NULL = extrator sem esse conceito, commit cai no caminho existente (resolveInvoiceMonth por transação).';
COMMENT ON COLUMN import_batches.target_invoice_reference_month IS
    'Mês de referência (1-12), par de target_invoice_reference_year. Ambos NULL ou ambos preenchidos.';
