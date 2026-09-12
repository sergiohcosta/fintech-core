-- Adiciona coluna de versão para optimistic locking (JPA @Version).
-- Impede promoção dupla de staged transactions em commits concorrentes.
ALTER TABLE staged_transactions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
