-- RLS PoC (#116, ADR-006): defesa em profundidade além do WHERE tenant_id da aplicação.
-- FORCE é obrigatório: sem ele, o usuário owner da tabela (mesmo role usado em runtime,
-- migrations e testes neste projeto) ignora a policy silenciosamente.
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions FORCE ROW LEVEL SECURITY;

-- missing_ok=true (2º argumento de current_setting): se app.tenant_id nunca foi setado na
-- sessão, current_setting não estoura exceção. NULLIF trata os DOIS jeitos de "não setado"
-- que o Postgres produz para uma GUC custom: NULL (nunca tocada nesta sessão) OU '' (valor
-- de RESET numa conexão de pool que já usou a GUC antes — comportamento real, não hipotético,
-- confirmado no teste discriminante). Sem o NULLIF, ''::uuid estoura exceção em vez de
-- resultar em "nenhuma linha visível" (fail-safe).
CREATE POLICY tenant_isolation ON transactions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
