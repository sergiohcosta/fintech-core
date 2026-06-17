-- V15__budget_cycle_ended_status.sql
-- Adiciona o status ENDED ao ciclo de planejamento.
-- ENDED = período encerrado mas ainda não fechado pelo usuário (ajustes ainda permitidos).

ALTER TABLE budget_cycles
    DROP CONSTRAINT chk_cycle_status,
    ADD CONSTRAINT chk_cycle_status
        CHECK (status IN ('OPEN', 'ENDED', 'CLOSED'));
