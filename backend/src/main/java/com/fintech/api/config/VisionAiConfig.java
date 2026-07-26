package com.fintech.api.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provê o {@link ChatClient} usado pelo {@code VisionExtractor}.
 *
 * <p>O Spring AI auto-configura um {@code ChatClient.Builder} (escopo prototype) a partir do
 * {@code ChatModel} do starter ativo (Ollama). Aqui só o materializamos num {@link ChatClient}
 * pronto — um bean singleton que o extrator injeta. Trocar de provider (Ollama → outro) é trocar
 * o starter Maven; este bean e o código do extrator não mudam.
 */
@Configuration
public class VisionAiConfig {

    @Bean
    public ChatClient visionChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
