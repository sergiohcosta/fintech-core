-- Adiciona reference_month e colunas de snapshot ao budget_cycles

ALTER TABLE budget_cycles
    ADD COLUMN reference_month VARCHAR(7);

ALTER TABLE budget_cycles
    ADD COLUMN snapshot_projected_balance NUMERIC(19,2),
    ADD COLUMN snapshot_available_to_spend NUMERIC(19,2),
    ADD COLUMN snapshot_realized_income NUMERIC(19,2),
    ADD COLUMN snapshot_realized_expense NUMERIC(19,2),
    ADD COLUMN snapshot_unplanned_expenses NUMERIC(19,2);

-- Preenche reference_month para ciclos existentes:
--   Se start_date cai no dia 1, reference_month = ano-mês do start_date
--   Caso contrário, reference_month = ano-mês de (start_date + 1 mês)
UPDATE budget_cycles
SET reference_month = CASE
    WHEN EXTRACT(DAY FROM start_date) = 1
        THEN TO_CHAR(start_date, 'YYYY-MM')
    ELSE
        TO_CHAR(start_date + INTERVAL '1 month', 'YYYY-MM')
    END
WHERE reference_month IS NULL;
