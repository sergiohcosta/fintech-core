CREATE TABLE invoices (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID         NOT NULL REFERENCES accounts(id),
    tenant_id        UUID         NOT NULL REFERENCES tenants(id),
    reference_year   INT          NOT NULL,
    reference_month  INT          NOT NULL,
    closing_date     DATE         NOT NULL,
    due_date         DATE         NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    UNIQUE (account_id, reference_year, reference_month)
);

CREATE INDEX idx_invoices_tenant      ON invoices(tenant_id);
CREATE INDEX idx_invoices_account     ON invoices(account_id);

ALTER TABLE transactions
    ADD COLUMN invoice_id UUID REFERENCES invoices(id);

CREATE INDEX idx_transactions_invoice ON transactions(invoice_id);
