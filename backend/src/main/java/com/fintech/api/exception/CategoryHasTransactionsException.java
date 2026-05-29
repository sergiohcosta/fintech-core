package com.fintech.api.exception;

public class CategoryHasTransactionsException extends RuntimeException {

    private final long transactionCount;

    public CategoryHasTransactionsException(long transactionCount) {
        super("Categoria possui " + transactionCount + " transação(ões) associada(s).");
        this.transactionCount = transactionCount;
    }

    public long getTransactionCount() {
        return transactionCount;
    }
}
