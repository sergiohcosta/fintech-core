-- Tabela de Cartões de Crédito
CREATE TABLE credit_cards (
    id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    name VARCHAR(50) NOT NULL, -- Ex: "Nubank", "Visa Infinite"
    limit_amount DECIMAL(19, 2) NOT NULL,
    closing_day INTEGER NOT NULL CHECK (closing_day BETWEEN 1 AND 31), -- Dia que fecha a fatura
    due_day INTEGER NOT NULL CHECK (due_day BETWEEN 1 AND 31), -- Dia que vence
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_cards_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_cards_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Tabela de Transações (O Livro Razão)
CREATE TABLE transactions (
    id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    
    -- Se for compra no crédito, preenchemos o card_id. Se for débito/dinheiro, fica NULL.
    credit_card_id UUID, 
    
    description VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL, -- Valores positivos (receita) ou negativos (despesa)
    
    date DATE NOT NULL, -- A data da compra ou do pagamento
    
    -- Controle de Parcelas
    installment_number INTEGER DEFAULT 1, -- Parcela 1
    total_installments INTEGER DEFAULT 1, -- De 10
    
    -- Categorização simples por enquanto
    category VARCHAR(50), 
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_tx_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_tx_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_tx_card FOREIGN KEY (credit_card_id) REFERENCES credit_cards(id)
);

-- Índices são vitais para extratos rápidos
CREATE INDEX idx_tx_date ON transactions(date);
CREATE INDEX idx_tx_user_date ON transactions(user_id, date);