-- RLS rollout ciclo 2 (#116, ADR-006).
ALTER TABLE import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_batches FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON import_batches
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
