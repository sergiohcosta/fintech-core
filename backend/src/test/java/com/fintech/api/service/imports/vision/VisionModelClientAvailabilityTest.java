package com.fintech.api.service.imports.vision;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova de fogo do Onda 2, passo 2.6: sobe o contexto INTEIRO (não um slice) sem
 * {@code GEMINI_API_KEY} no ambiente — o mesmo estado de um clone novo do repositório, ou da
 * suíte de CI, que nunca tem esse segredo — e confirma que:
 *
 * <ol>
 *   <li>o contexto SOBE (não quebra o boot, ver {@code GeminiAutoConfigExclusionPostProcessor});
 *   <li>a lista de {@link VisionModelClient} tem SÓ o Ollama — o Gemini não entra na lista
 *       quando não há chave (garante que um clone novo do repo continua funcionando bit a bit
 *       igual a antes da Onda 2, sem nenhum segredo configurado).
 * </ol>
 *
 * <p>Não testamos aqui o caso "com chave" via {@code @TestPropertySource}: quem decide a
 * exclusão da auto-configuration do Gemini é o {@code GeminiAutoConfigExclusionPostProcessor},
 * que lê a env var {@code GEMINI_API_KEY} REAL do processo — não dá para simular isso por
 * property de teste sem reiniciar a JVM. O caso "com chave" é coberto pelos testes unitários do
 * {@link GeminiVisionClient} (mecânica do client) e pela verificação manual documentada no
 * relatório da Onda (contexto sobe com {@code GEMINI_API_KEY} setada).
 */
@SpringBootTest
class VisionModelClientAvailabilityTest {

    @Autowired
    private List<VisionModelClient> visionModelClients;

    @Test
    void semGeminiApiKeySoOOllamaEstaDisponivel() {
        assertThat(visionModelClients).hasSize(1);
        assertThat(visionModelClients.get(0).providerId()).isEqualTo("ollama");
    }
}
