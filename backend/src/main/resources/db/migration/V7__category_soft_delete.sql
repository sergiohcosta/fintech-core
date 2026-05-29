-- V7__category_soft_delete.sql
--
-- Adiciona suporte a soft delete em categorias.
-- A exclusão de uma categoria com transações associadas passa a marcar
-- deleted_at em vez de remover a linha, preservando a integridade referencial
-- da FK em transactions.category_id sem precisar alterar a constraint.

ALTER TABLE categories ADD COLUMN deleted_at TIMESTAMP;
