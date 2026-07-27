package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportBatchStatus;
import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.UserRole;
import com.fintech.api.domain.tenant.Tenant;
import com.fintech.api.domain.user.User;
import com.fintech.api.dto.imports.ImportBatchResponseDTO;
import com.fintech.api.repository.TenantRepository;
import com.fintech.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * #193 — o batch FAILED passa a carregar o MOTIVO da falha (coluna V25), porque causas distintas
 * pedem ações distintas do usuário ("suba um comprovante por vez" != "a imagem está ilegível").
 *
 * <p>O {@link TransactionExtractor} é substituído por mock ({@code @MockitoBean}) — a suíte nunca
 * bate no Ollama real. Classe separada de propósito: o mock troca o bean para todo o contexto.
 */
@SpringBootTest
@Transactional
class ImportFailureReasonTest {

    private static final byte[] IMAGE = {1, 2, 3};

    @Autowired ImportService importService;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;

    @MockitoBean TransactionExtractor extractor;

    private User persistUser() {
        Tenant t = new Tenant();
        t.setName("Tenant Falha");
        Tenant tenant = tenantRepository.save(t);

        User u = new User();
        u.setTenant(tenant);
        u.setName("Owner");
        u.setEmail("owner-failure@import.test");
        u.setPasswordHash("irrelevant-hash");
        u.setRole(UserRole.ADMIN);
        u.setActive(true);
        return userRepository.save(u);
    }

    private ImportBatchResponseDTO uploadFailingWith(RuntimeException error) {
        when(extractor.extract(any(), eq("image/jpeg"), any())).thenThrow(error);
        return importService.createFromImage(IMAGE, "image/jpeg", ImportMode.NEW_TRANSACTIONS, persistUser());
    }

    /** Recusa de escopo (#193): a mensagem que redigimos chega íntegra ao usuário. */
    @Test
    void extractionExceptionViraMotivoExibivelNoBatch() {
        ImportBatchResponseDTO batch =
                uploadFailingWith(new ExtractionException(VisionExtractor.MULTIPLE_TRANSACTIONS_MESSAGE));

        assertThat(batch.status()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.failureReason()).isEqualTo(VisionExtractor.MULTIPLE_TRANSACTIONS_MESSAGE);
    }

    /**
     * Fronteira de confiança: falha de infra NÃO vaza detalhe interno para a borda da API.
     * A mensagem crua da exceção (host, stack, driver) vira texto genérico.
     */
    @Test
    void falhaDeInfraNaoVazaMensagemInternaParaOUsuario() {
        ImportBatchResponseDTO batch = uploadFailingWith(
                new IllegalStateException("Connection refused: ollama.homelab.internal:11434"));

        assertThat(batch.status()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.failureReason())
                .isNotNull()
                .doesNotContain("ollama.homelab.internal")
                .doesNotContain("Connection refused");
    }
}
