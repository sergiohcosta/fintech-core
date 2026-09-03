package com.fintech.api.config;

import com.fintech.api.domain.user.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Executa {@code SET LOCAL app.tenant_id} no início de todo método {@code @Transactional} de
 * {@code com.fintech.api.service}, para as policies RLS (defesa em profundidade, #116,
 * ADR-006) enxergarem o tenant. Pointcut genérico por anotação — não por classe/método
 * enumerado — de propósito: um pointcut enumerado já deixou passar um write path real
 * (achado do PoC, {@code InvoiceService.pay}); um novo service/método `@Transactional` entra
 * automaticamente aqui, sem precisar lembrar de atualizar este arquivo.
 *
 * <p><b>Resolução de tenant, nesta ordem:</b>
 * <ol>
 *   <li>{@link SecurityUtils#currentUserOrNull()} — cobre todo tráfego HTTP autenticado
 *       (a grande maioria), sem depender da assinatura do método.</li>
 *   <li>Parâmetro {@code User} nos argumentos do método (mecanismo original do PoC) — cobre
 *       testes de integração que chamam o service direto, sem HTTP (ex.: {@code
 *       ImportServiceTest}, que aciona {@code TransactionService.create} de dentro de
 *       {@code ImportService.commit} passando um {@code User} de fixture).</li>
 * </ol>
 * Se nenhuma das duas resolver, o método segue sem {@code app.tenant_id} setado — sem
 * exceção. Isso é seguro por construção: se o método tocar tabela com RLS, a policy nega
 * tudo (fail-safe deny), erro alto e visível; se não tocar nenhuma, não faz diferença. Dois
 * casos conhecidos de método público que grava dado sem nenhuma das duas fontes disponíveis
 * ({@code TenantRegistrationService.register}, {@code InvitationService.accept} — tenant
 * nasce/é resolvido DENTRO do método) recebem {@code SET LOCAL} manual inline, não este
 * aspect.
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

    @Around("within(com.fintech.api.service..*) && @annotation(org.springframework.transaction.annotation.Transactional)")
    public Object setTenantContext(ProceedingJoinPoint joinPoint) throws Throwable {
        resolveTenantId(joinPoint)
                .ifPresent(tenantId -> TenantRlsContext.setLocalTenantId(entityManager, tenantId));
        return joinPoint.proceed();
    }

    private Optional<UUID> resolveTenantId(ProceedingJoinPoint joinPoint) {
        User authenticated = SecurityUtils.currentUserOrNull();
        if (authenticated != null) {
            return Optional.of(authenticated.getTenant().getId());
        }

        return Arrays.stream(joinPoint.getArgs())
                .filter(User.class::isInstance)
                .map(User.class::cast)
                .map(user -> user.getTenant().getId())
                .findFirst();
    }
}
