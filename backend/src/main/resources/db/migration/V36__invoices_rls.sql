-- RLS rollout ciclo 3 (#116, ADR-006).
ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoices FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON invoices
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
