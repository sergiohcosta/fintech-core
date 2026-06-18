-- Corrige reference_month/reference_year de faturas criadas antes da correção de
-- InvoiceService.resolveInvoiceMonth: o bug fazia reference_month = mês do fechamento,
-- quando o correto é reference_month = mês anterior (período a que a fatura pertence).
--
-- Detecção: closing_date caindo no mesmo mês que reference_month indica dado errado.
-- Na lógica correta, closing_date deve cair no mês SEGUINTE ao reference_month.
--
-- Passo 1: soma 100 para sair do intervalo válido (1-12) e evitar colisão na
-- unique constraint (account_id, reference_year, reference_month) durante o shift.
-- Passo 2: subtrai 101; quando cai em 101 (era janeiro) → dezembro do ano anterior.

UPDATE invoices
SET reference_month = reference_month + 100
WHERE EXTRACT(MONTH FROM closing_date) = reference_month;

UPDATE invoices
SET
  reference_year  = CASE WHEN reference_month = 101 THEN reference_year - 1 ELSE reference_year END,
  reference_month = CASE WHEN reference_month = 101 THEN 12 ELSE reference_month - 101 END
WHERE reference_month > 12;
