package com.fintech.api.config;

import jakarta.persistence.EntityManager;

import java.util.UUID;

/**
 * {@code SET LOCAL app.tenant_id} — usado pelo {@link TenantRlsAspect} (caso geral) e por
 * services cujo método público grava dado antes de ter tenant resolvido via autenticação ou
 * argumento {@code User} (ex.: {@code TenantRegistrationService.register},
 * {@code InvitationService.accept} — o tenant nasce/é resolvido dentro do próprio método).
 */
public final class TenantRlsContext {

    private TenantRlsContext() {
    }

    public static void setLocalTenantId(EntityManager entityManager, UUID tenantId) {
        // SET LOCAL não aceita bind parameter via JDBC — tenantId vem de UUID.toString(), sem
        // risco de injeção (só dígitos hex e hífens).
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '" + tenantId + "'")
                .executeUpdate();
    }
}
