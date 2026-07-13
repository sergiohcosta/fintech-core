-- V22: marcador do pagamento de fatura (#145).
-- Identifica o EXPENSE que quita uma fatura. Permite ao dashboard excluí-lo dos agregados de
-- income/expense (evita dupla contagem: as compras do cartão já entram via invoice.dueDate).
-- O índice único parcial garante no schema no máximo UM pagamento por fatura (reforça #139).
-- Coluna nasce nullable: transações antigas não são pagamentos, então NULL é o valor correto.
ALTER TABLE transactions ADD COLUMN paid_invoice_id UUID;

ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_paid_invoice
    FOREIGN KEY (paid_invoice_id) REFERENCES invoices(id);

CREATE UNIQUE INDEX uq_transactions_paid_invoice
    ON transactions(paid_invoice_id)
    WHERE paid_invoice_id IS NOT NULL;
