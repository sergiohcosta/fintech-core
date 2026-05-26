CREATE TABLE credit_cards (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    
    -- Identificação
    name VARCHAR(100) NOT NULL,
    brand VARCHAR(50) NOT NULL,
    color VARCHAR(7),
    last_four_digits VARCHAR(4),
    
    -- Financeiro
    limit_amount DECIMAL(19, 2),
    closing_day INTEGER NOT NULL,
    due_day INTEGER NOT NULL,
    
    -- Auditoria (Novos Campos)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Relacionamentos
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,

    CONSTRAINT fk_credit_cards_tenant_id FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_credit_cards_user_id FOREIGN KEY (user_id) REFERENCES users(id)
);