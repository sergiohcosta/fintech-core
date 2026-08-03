package com.fintech.api.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provê o {@link ChatClient} usado pelo {@code OllamaVisionClient} (implementação da porta
 * {@code VisionModelClient} sobre o Ollama do homelab).
 *
 * <p>O Spring AI auto-configura um {@code ChatClient.Builder} (escopo prototype) a partir do
 * {@code ChatModel} do starter ativo (Ollama). Aqui só o materializamos num {@link ChatClient}
 * pronto — um bean singleton que o client injeta.
 *
 * <p><b>Nuance introduzida na Onda 1</b> (plano "extração Gemini primário / Ollama fallback"):
 * o javadoc original desta classe dizia que "trocar de provider é trocar o starter Maven, sem
 * tocar no código" — isso era verdade enquanto só existia UM client. A partir de agora, um
 * provider gerenciado (ex.: Gemini) NÃO troca este bean nem o starter Ollama — ele SOMA um novo
 * {@code VisionModelClient} (com sua própria configuração), e os dois convivem via
 * {@code @Order}. "Trocar provider" continua trivial para quem só quer o Ollama; "adicionar um
 * segundo provider" é aditivo, não substitutivo.
 */
@Configuration
public class VisionAiConfig {

    @Bean
    public ChatClient visionChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
