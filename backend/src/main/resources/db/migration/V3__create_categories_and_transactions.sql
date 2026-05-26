-- V3__create_categories_and_transactions.sql

-- 1. Categorias pertencem ao TENANT (Família/Empresa), não ao indivíduo.
CREATE TABLE categories (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    
    name VARCHAR(255) NOT NULL,
    icon VARCHAR(50) NOT NULL,
    color VARCHAR(7) NOT NULL,
    
    parent_id UUID,
    
    tenant_id UUID NOT NULL,   -- O DONO do dado (A Família)
    created_by UUID,           -- Quem criou (Auditoria - opcional mas útil)

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_categories_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_categories_creator FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories(id) ON DELETE CASCADE
);

-- Garante que não existam duas categorias com mesmo nome DENTRO DO MESMO TENANT
CREATE UNIQUE INDEX uk_categories_name_tenant ON categories(tenant_id, name, parent_id);


-- 2. Transações
CREATE TABLE transactions (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    
    description VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    date DATE NOT NULL,
    type VARCHAR(20) NOT NULL, 
    
    tenant_id UUID NOT NULL,    -- Agrupador principal
    user_id UUID NOT NULL,      -- Quem gastou (Diferente de quem criou a categoria)
    
    category_id UUID,
    credit_card_id UUID,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_transactions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_transactions_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_transactions_category FOREIGN KEY (category_id) REFERENCES categories(id),
    CONSTRAINT fk_transactions_credit_card FOREIGN KEY (credit_card_id) REFERENCES credit_cards(id)
);