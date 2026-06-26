package com.fintech.api.exception;

/**
 * Contrato explícito para erros de negócio intencionais retornados como 400 Bad Request.

 * <p>Separar erros de negócio de {@link IllegalArgumentException} evita que exceções
 * lançadas por bibliotecas/JDK sejam mapeadas indevidamente para respostas 400
 * e exponham detalhes internos inesperados.</p>
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
