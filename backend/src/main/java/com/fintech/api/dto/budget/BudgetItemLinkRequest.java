package com.fintech.api.dto.budget;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record BudgetItemLinkRequest(@NotNull UUID transactionId) {}
