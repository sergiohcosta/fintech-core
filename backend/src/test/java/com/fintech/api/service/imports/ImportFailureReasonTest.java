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
import static org.mockito.Mockito.when;

/**
 * #193 (V25) — o batch FAILED passa a carregar o MOTIVO da falha, porque causas distintas pedem
 * ações distintas do usuário ("imagem ilegível" != "extrato com lançamentos demais"). Mecanismo
 * genérico, testado aqui num nível acima do extrator específico (mock de
 * {@link TransactionExtractor}) — o exemplo de mensagem usado é o do #194 (limite de linhas do
 * caminho de extrato), mas qualquer {@link ExtractionException} exercitaria o mesmo caminho.
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

    // Nomeado: desde a Onda 2 (OfxExtractor) há 2 beans de TransactionExtractor no contexto —
    // sem o nome, o @MockitoBean não sabe qual dos dois substituir.
    @MockitoBean(name = "visionExtractor") TransactionExtractor extractor;

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
        when(extractor.supports(any())).thenReturn(true);
        when(extractor.sourceType()).thenReturn(com.fintech.api.domain.enums.ImportSourceType.IMAGE);
        when(extractor.extract(any())).thenThrow(error);
        ExtractionInput input = new ExtractionInput(IMAGE, "recibo.jpg", "image/jpeg", ImportMode.NEW_TRANSACTIONS);
        return importService.createFromFile(input, false, persistUser());
    }

    /** A mensagem que o extrator redige (nossa, não do provider) chega íntegra ao usuário. */
    @Test
    void extractionExceptionViraMotivoExibivelNoBatch() {
        String motivo = "O extrato tem mais de 60 lançamentos — recorte a imagem em partes menores.";
        ImportBatchResponseDTO batch = uploadFailingWith(new ExtractionException(motivo));

        assertThat(batch.status()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.failureReason()).isEqualTo(motivo);
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
