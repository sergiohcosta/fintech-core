package com.fintech.api.service.imports;

/**
 * Falha de extração — o modelo não retornou dados utilizáveis, ou a saída não passou no
 * guarda-corpo de sanidade (§2.g). Sinaliza ao {@code ImportService} que o batch deve ir para
 * {@code FAILED}; o fallback é o usuário lançar a transação manualmente no formulário existente.
 *
 * <p>É uma exceção de INFRA do extrator — o {@code ImportService} a captura e a converte num
 * batch {@code FAILED}, sem propagar 500 para o cliente.
 */
public class ExtractionException extends RuntimeException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
