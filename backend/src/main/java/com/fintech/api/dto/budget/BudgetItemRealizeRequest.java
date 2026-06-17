package com.fintech.api.dto.budget;

import java.util.UUID;

public record BudgetItemRealizeRequest(
    UUID transactionId // nullable — if null, creates a new transaction
) {}
