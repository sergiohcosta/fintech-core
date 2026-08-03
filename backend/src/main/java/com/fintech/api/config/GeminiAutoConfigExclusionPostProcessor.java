package com.fintech.api.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Impede a auto-configuration do Gemini (starter {@code spring-ai-starter-model-google-genai})
 * de sequer REGISTRAR seus beans quando não há {@code GEMINI_API_KEY} — descoberta feita
 * TESTANDO (plano "extração Gemini primário / Ollama fallback", Onda 2, passo 2.2 "verificar
 * primeiro"), não suposição.
 *
 * <p><b>O problema real (mais grave que a ambiguidade de {@code ChatClient.Builder} prevista no
 * plano):</b> {@code GoogleGenAiChatAutoConfiguration} não tem NENHUMA condição sobre chave/
 * segredo — só sobre classpath e sobre a property seletora {@code spring.ai.model.chat}
 * (que teria que escolher UM provider entre Ollama/Gemini, desligando o outro — inviável, os
 * dois precisam conviver para o fallback). Os beans {@code googleGenAiClient}/
 * {@code googleGenAiChatModel} são singletons comuns (não-lazy): o Spring os PRÉ-INSTANCIA no
 * boot independente de alguém usá-los, e o factory method deles lança
 * {@code IllegalArgumentException("Google GenAI project-id must be set!")} sempre que
 * {@code api-key} E {@code project-id} estão vazios. Ou seja: só ADICIONAR o starter no
 * classpath já derruba o boot de um clone novo sem segredo — mesmo que NENHUM código nosso
 * (nem {@code GeminiVisionClient}) chegue a referenciar o {@code GoogleGenAiChatModel}.
 *
 * <p><b>Por que não dá para resolver com {@code @ConditionalOnProperty}/{@code @ConditionalOnBean}
 * no nosso lado:</b> o bean já existe e já FALHA durante a criação, antes de qualquer condição
 * nossa entrar em jogo — a única forma de evitar a falha é impedir que a auto-configuration
 * inteira seja sequer IMPORTADA. {@code spring.autoconfigure.exclude} é o mecanismo do Spring
 * Boot para isso, e precisa ser decidido no {@link ConfigurableEnvironment} ANTES do container
 * escolher quais auto-configurations importar — daí o {@link EnvironmentPostProcessor} (mesmo
 * idioma já usado neste projeto por {@code NeonFallbackEnvironmentPostProcessor}), não uma
 * anotação em bean.
 *
 * <p>Com a chave presente, este post-processor não faz nada — a auto-configuration do Gemini
 * segue seu curso normal (autenticação por api-key, sem project-id/location — modo AI Studio).
 */
public class GeminiAutoConfigExclusionPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String GEMINI_CHAT_AUTOCONFIG =
            "org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration";

    @Override
    public int getOrder() {
        // Mesma faixa do NeonFallbackEnvironmentPostProcessor: cedo o suficiente para que a
        // exclusão valha antes do container resolver quais auto-configurations importar.
        return Ordered.HIGHEST_PRECEDENCE + 15;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Lê a env var diretamente (não a property já processada com default vazio de
        // application.properties) — é o sinal cru de "a chave foi configurada por quem opera
        // este ambiente", sem depender de nenhum outro property source já ter carregado.
        String apiKey = environment.getProperty("GEMINI_API_KEY");
        if (StringUtils.hasText(apiKey)) {
            return;
        }

        environment.getPropertySources().addFirst(new MapPropertySource(
                "gemini-autoconfig-exclusion",
                Map.of("spring.autoconfigure.exclude", GEMINI_CHAT_AUTOCONFIG)));
        System.out.println(">>> [GeminiAutoConfigExclusion] GEMINI_API_KEY ausente — excluindo "
                + "a auto-configuration do Gemini (extração de visão segue só com Ollama)");
    }
}
