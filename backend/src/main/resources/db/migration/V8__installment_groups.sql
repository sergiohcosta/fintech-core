CREATE TABLE installment_groups (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    description        VARCHAR(255) NOT NULL,
    total_amount       DECIMAL(15,2) NOT NULL,
    total_installments INTEGER      NOT NULL,
    account_id         UUID         NOT NULL REFERENCES accounts(id),
    category_id        UUID         REFERENCES categories(id),
    tenant_id          UUID         NOT NULL REFERENCES tenants(id),
    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT now()
);

ALTER TABLE transactions
    ADD COLUMN installment_group_id UUID REFERENCES installment_groups(id);

CREATE INDEX idx_transactions_group     ON transactions(tenant_id, installment_group_id);
CREATE INDEX idx_installment_groups_tenant ON installment_groups(tenant_id);
