-- Adiciona status para controle de fluxo de caixa (Pago vs A Pagar)
ALTER TABLE transactions 
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'; -- PENDING, PAID

-- Adiciona suporte a parcelamento
-- Exemplo: Compra de R$ 1000 em 10x
-- installment_number = 1 (1/10)
-- total_installments = 10
ALTER TABLE transactions 
ADD COLUMN installment_number INTEGER DEFAULT 1,
ADD COLUMN total_installments INTEGER DEFAULT 1;

-- Índice para buscar contas a pagar rapidamente
CREATE INDEX idx_transactions_status_date ON transactions(tenant_id, status, date);