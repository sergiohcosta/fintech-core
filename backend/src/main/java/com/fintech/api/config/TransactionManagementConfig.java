package com.fintech.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Fixa a precedência do advisor de {@code @Transactional} explicitamente. Sem isso, ele e o
 * {@link TenantRlsAspect} ficariam empatados em {@code Ordered.LOWEST_PRECEDENCE} (default do
 * Spring), com ordem relativa indefinida — e o {@code SET LOCAL app.tenant_id} do aspect
 * precisa rodar DEPOIS que a transação já abriu, nunca antes. Valor baixo aqui = maior
 * precedência = advice mais externo (abre a transação primeiro).
 */
@Configuration
@EnableTransactionManagement(order = TransactionManagementConfig.TRANSACTION_ADVISOR_ORDER)
public class TransactionManagementConfig {
    public static final int TRANSACTION_ADVISOR_ORDER = Ordered.LOWEST_PRECEDENCE - 200;
}
