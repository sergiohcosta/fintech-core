package com.fintech.api.config;

import com.fintech.api.domain.user.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.Arrays;

/**
 * Executa {@code SET LOCAL app.tenant_id} no início de cada método de negócio que grava em
 * {@code transactions}, para a policy RLS (migration V33) enxergar o tenant. Escopo do PoC
 * #116 — só os pontos de escrita já cobertos pela suíte existente; rollout pra services que
 * ainda não tocam {@code transactions} é fase 2 (ADR-006).
 *
 * <p>Pointcut cobre TODO método público de {@code TransactionService} (todos recebem
 * {@code User}) e, individualmente, {@code InvoiceService.pay} — o único método de
 * {@code InvoiceService} que grava em {@code transactions} (cria o EXPENSE de pagamento
 * direto pelo repositório, sem passar por {@code TransactionService}; achado real do PoC,
 * não hipótese: sem isso, pagar fatura em produção quebraria com RLS ativo). Os demais
 * métodos de {@code InvoiceService} (getOrCreate, close, findByAccount...) NÃO entram no
 * pointcut de propósito — não recebem {@code User}, e o {@code orElseThrow} abaixo quebraria
 * todos eles se o pointcut fosse a classe inteira.
 *
 * <p>O tenant vem do parâmetro {@code User} que cada método interceptado já recebe — não do
 * {@code SecurityContextHolder}. Motivo: testes de integração ({@code @SpringBootTest}) chamam
 * o service (via bean proxied) passando um {@code User} de fixture sem nunca passar pelo
 * {@code SecurityFilter} (ex.: {@code ImportServiceTest}, que aciona
 * {@code TransactionService.create} dentro de {@code ImportService.commit}). Ler do contexto
 * de autenticação quebraria esses testes; ler do parâmetro já existente reusa exatamente a
 * mesma fonte de verdade que o resto do código já usa para escopar por tenant.
 *
 * <p>{@code @Order} maior que {@link TransactionManagementConfig#TRANSACTION_ADVISOR_ORDER}:
 * precedência MENOR (número maior) = advice mais interno, roda depois que a transação já
 * abriu — exatamente onde o SET LOCAL precisa executar.
 */
@Aspect
@Component
@Order(TransactionManagementConfig.TRANSACTION_ADVISOR_ORDER + 100)
@RequiredArgsConstructor
public class TenantRlsAspect {

    private final EntityManager entityManager;

    @Around("execution(public * com.fintech.api.service.TransactionService.*(..))"
            + " || execution(public com.fintech.api.dto.invoice.InvoiceResponseDTO com.fintech.api.service.InvoiceService.pay(..))")
    public Object setTenantContext(ProceedingJoinPoint joinPoint) throws Throwable {
        User user = Arrays.stream(joinPoint.getArgs())
                .filter(User.class::isInstance)
                .map(User.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TenantRlsAspect: %s não recebeu User — impossível setar app.tenant_id"
                                .formatted(joinPoint.getSignature())));

        String tenantId = user.getTenant().getId().toString();
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (Statement statement = connection.createStatement()) {
                // SET LOCAL não aceita bind parameter via JDBC — tenantId vem de UUID.toString(),
                // sem risco de injeção (só dígitos hex e hífens).
                statement.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            }
        });

        return joinPoint.proceed();
    }
}
