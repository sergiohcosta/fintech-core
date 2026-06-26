-- Motor de Recorrência (núcleo). A "Regra" é atemporal; a recorrência temporal
-- vive na string RRULE (RFC 5545), expandida on-the-fly. Nada é materializado aqui.
CREATE TABLE recurrence_rules (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id),
    description  VARCHAR(255) NOT NULL,
    base_amount  NUMERIC      NOT NULL,
    type         VARCHAR(10)  NOT NULL,                 -- INCOME | EXPENSE
    category_id  UUID         REFERENCES categories(id),
    account_id   UUID         NOT NULL REFERENCES accounts(id),
    rrule        TEXT         NOT NULL,                 -- ex: FREQ=MONTHLY;BYMONTHDAY=15
    start_date   DATE         NOT NULL,                 -- DTSTART (âncora da expansão)
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',-- ACTIVE | CANCELLED
    created_by   UUID         REFERENCES users(id),
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_recurrence_type   CHECK (type   IN ('INCOME','EXPENSE')),
    CONSTRAINT chk_recurrence_status CHECK (status IN ('ACTIVE','CANCELLED'))
);
CREATE INDEX idx_recurrence_rules_tenant_status ON recurrence_rules(tenant_id, status);

-- EXDATE: só ganha linha quando o usuário PULA de fato (tabela esparsa).
CREATE TABLE recurrence_exceptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id         UUID NOT NULL REFERENCES recurrence_rules(id) ON DELETE CASCADE,
    occurrence_date DATE NOT NULL,
    UNIQUE (rule_id, occurrence_date)
);
CREATE INDEX idx_recurrence_exceptions_rule_date ON recurrence_exceptions(rule_id, occurrence_date);

-- Liga a transação materializada à ocorrência da regra que ela satisfaz.
-- recurrence_occurrence é o "slot" canônico da regra (≠ date, que é a data efetiva).
ALTER TABLE transactions
    ADD COLUMN recurrence_rule_id    UUID REFERENCES recurrence_rules(id),
    ADD COLUMN recurrence_occurrence DATE;
-- Impede confirmar a mesma ocorrência duas vezes (índice único parcial).
CREATE UNIQUE INDEX uq_transactions_rule_occurrence
    ON transactions(recurrence_rule_id, recurrence_occurrence)
    WHERE recurrence_rule_id IS NOT NULL;
