-- V25 — motivo da falha de extração no batch (#193).
--
-- Até aqui o batch FAILED era opaco: o frontend só sabia "falhou, use o formulário manual".
-- Com o guarda-corpo de imagem multi-transação, a recusa passa a ter CAUSAS DISTINTAS que pedem
-- AÇÕES DISTINTAS do usuário ("suba um comprovante por vez" != "a imagem está ilegível").
-- Recusar sem dizer por quê é meio erro silencioso — o oposto do princípio do roadmap
-- ("erro explícito > erro silencioso").
--
-- É texto pronto para exibição (PT-BR), não um código de erro enumerado: hoje há 2-3 causas e um
-- enum seria estrutura sem demanda. Só o backend escreve aqui, e apenas mensagens que ele mesmo
-- redige — nunca a mensagem crua de uma exceção de infra (evita vazar interno para a borda).

ALTER TABLE import_batches ADD COLUMN failure_reason VARCHAR(500);

COMMENT ON COLUMN import_batches.failure_reason IS
    'Motivo legível da falha de extração (só preenchido quando status = FAILED).';
