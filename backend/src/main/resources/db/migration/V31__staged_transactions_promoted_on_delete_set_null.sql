-- Incidente prod (2026-08-13): DELETE /api/transactions/{id} em transação promovida de uma
-- staged_transactions estourava FK RESTRICT (staged_transactions_promoted_transaction_id_fkey)
-- como DataIntegrityViolationException não tratada -> 500. O vínculo é só proveniência (a
-- staged já está CONFIRMED, o fato de negócio não depende dele) -- SET NULL preserva o
-- histórico do batch sem travar a exclusão da transação.
ALTER TABLE staged_transactions
    DROP CONSTRAINT staged_transactions_promoted_transaction_id_fkey,
    ADD CONSTRAINT staged_transactions_promoted_transaction_id_fkey
        FOREIGN KEY (promoted_transaction_id) REFERENCES transactions(id) ON DELETE SET NULL;
