-- RLS rollout ciclo 2 (#116, ADR-006). Segunda camada sobre o denormalized tenant_id (V23)
-- que já existia como defesa nº1 contra vazamento nesta tabela.
ALTER TABLE staged_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE staged_transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON staged_transactions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
