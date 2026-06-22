-- =============================================================
-- V18__fix_dev_budget_opening_balance.sql — perfil dev
-- Corrige o opening_balance do ciclo seed de junho/2026.
--
-- Contexto: a regra de openingBalance passou a somar apenas o caixa
-- líquido PAID ANTERIOR ao ciclo (t.date < start_date). Para o dataset
-- Família Costa (contas líquidas Bradesco Corrente + Carteira, transações
-- PAID até 2026-05-31) o valor fiel é 18123.10 — o V16 semeava 1200.00.
--
-- V16 é imutável (já aplicado); a correção vem por nova versão.
-- Idempotente: só toca no ciclo seed (UUID fixo) se ainda estiver no valor antigo,
-- preservando ajustes manuais em bancos com dados reais.
-- =============================================================

UPDATE budget_cycles
SET opening_balance = 18123.10
WHERE id = 'a0000000-0000-0000-0000-000000000001'
  AND opening_balance = 1200.00;
