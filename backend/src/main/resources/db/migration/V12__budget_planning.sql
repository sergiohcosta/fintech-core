ALTER TABLE tenants ADD COLUMN budget_cycle_start_day INT NOT NULL DEFAULT 1;

CREATE TABLE budget_cycles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    opening_balance NUMERIC(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_cycle_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE UNIQUE INDEX uq_tenant_one_open_cycle
    ON budget_cycles(tenant_id)
    WHERE status = 'OPEN';

CREATE TABLE recurring_budget_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    type VARCHAR(10) NOT NULL,
    category_id UUID REFERENCES categories(id),
    account_id UUID REFERENCES accounts(id),
    day_of_month INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_recurring_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT chk_recurring_day CHECK (day_of_month BETWEEN 1 AND 28)
);

CREATE TABLE budget_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id UUID NOT NULL REFERENCES budget_cycles(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    description VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    type VARCHAR(10) NOT NULL,
    category_id UUID REFERENCES categories(id),
    account_id UUID REFERENCES accounts(id),
    expected_date DATE NOT NULL,
    source VARCHAR(15) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    recurring_item_id UUID REFERENCES recurring_budget_items(id),
    transaction_id UUID REFERENCES transactions(id),
    installment_group_id UUID REFERENCES installment_groups(id),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_item_type CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT chk_item_source CHECK (source IN ('MANUAL', 'RECURRING', 'INSTALLMENT')),
    CONSTRAINT chk_item_status CHECK (status IN ('PENDING', 'REALIZED', 'SKIPPED'))
);

CREATE INDEX idx_budget_cycles_tenant            ON budget_cycles(tenant_id);
CREATE INDEX idx_recurring_budget_items_tenant   ON recurring_budget_items(tenant_id);
CREATE INDEX idx_budget_items_tenant             ON budget_items(tenant_id);
CREATE INDEX idx_budget_items_cycle              ON budget_items(cycle_id);
