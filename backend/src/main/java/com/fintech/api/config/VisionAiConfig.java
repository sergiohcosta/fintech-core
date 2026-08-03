package com.fintech.api.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provê os {@link ChatClient} usados pelos {@code VisionModelClient} (Ollama e, a partir da
 * Onda 2, Gemini).
 *
 * <p><b>Onda 1 → Onda 2, mudança de forma (verificado empiricamente, plano "Onda 2" passo 2.2):
 * </b> com um único starter Spring AI no classpath, o próprio Spring AI auto-configurava um
 * {@code ChatClient.Builder} (a partir do único {@code ChatModel} candidato) e esta classe só
 * precisava materializá-lo num {@link ChatClient} singleton. Ao somar o starter do Gemini, essa
 * auto-configuração QUEBRA: ela é condicional a um candidato ÚNICO de {@code ChatModel}, e agora
 * há dois ({@code OllamaChatModel} e {@code GoogleGenAiChatModel} — nenhum starter cede essa
 * ambiguidade sozinho, os dois convivem "always-on" nos respectivos autoconfigure). Confirmado
 * subindo o contexto: {@code NoUniqueBeanDefinitionException} — "found 2: googleGenAiChatModel,
 * ollamaChatModel".
 *
 * <p>Solução (plano B do brief): construir o {@link ChatClient} de cada provider explicitamente
 * A PARTIR do {@code ChatModel} concreto (não do {@code ChatClient.Builder} agnóstico), com um
 * bean NOMEADO por provider. Cada {@code VisionModelClient} injeta o seu por
 * {@code @Qualifier} — não há mais um {@code ChatClient} "genérico" no contexto.
 *
 * <p><b>Por que NÃO leva {@code @ConditionalOnBean}</b> (tentado e descartado nesta Onda): esta
 * classe é um {@code @Configuration} comum, não um {@code @AutoConfiguration} — o container
 * processa {@code @Configuration}s de usuário ANTES de aplicar as auto-configurations importadas
 * (Ollama/Gemini), então {@code @ConditionalOnBean(OllamaChatModel.class)} aqui vê ZERO beans
 * daquele tipo ainda registrados e nunca casa — confirmado subindo o contexto (o bean
 * simplesmente não existia, nem em modo degradado, mesmo para o Ollama que sempre está presente).
 *
 * <p><b>{@code @ConditionalOnExpression} no bean do Gemini — achado mais grave que a ambiguidade
 * prevista (ver {@link com.fintech.api.service.imports.vision.GeminiVisionClient} para o
 * detalhe completo):</b> a auto-configuration do Gemini pré-instancia
 * {@code GoogleGenAiChatModel} (e sua dependência {@code googleGenAiClient}) como singleton
 * comum, incondicional a chave — ela FALHA já na criação quando não há {@code api-key} nem
 * {@code project-id} ("project-id must be set!"). Isso é neutralizado no
 * {@code GeminiAutoConfigExclusionPostProcessor} (exclui a auto-configuration inteira quando
 * falta {@code GEMINI_API_KEY}); a mesma expressão aqui garante que, MESMO SE algum dia a
 * exclusão for removida ou o comportamento do autoconfigure mudar, este bean nunca tenta
 * resolver um {@code GoogleGenAiChatModel} sem chave — dupla proteção, não redundância inútil.
 *
 * <p><b>Fonte ÚNICA da decisão "tem chave?" (revisão pós-Onda 2):</b> a expressão lê
 * {@code ${GEMINI_API_KEY:}} — a env var CRUA, a MESMA que
 * {@code GeminiAutoConfigExclusionPostProcessor} lê via {@code System.getenv}/{@code Environment}
 * — e não {@code spring.ai.google.genai.api-key} (a property derivada dela). Na primeira versão
 * desta Onda os dois liam fontes diferentes que hoje coincidem só porque a property É definida
 * como {@code ${GEMINI_API_KEY:}} em {@code application.properties}; se algo um dia popular
 * {@code spring.ai.google.genai.api-key} por outra via (um profile de teste, um
 * {@code @TestPropertySource}) sem tocar a env var, as duas decisões divergiam: o post-processor
 * excluiria a auto-configuration (sem `GoogleGenAiChatModel`) enquanto este bean tentaria criá-lo
 * mesmo assim — `UnsatisfiedDependencyException` confuso no lugar da mensagem clara que a Onda
 * pretende. Ler a MESMA env var nos dois lugares elimina a divergência pela raiz, em vez de só
 * documentá-la. Se este bean e o {@code GeminiAutoConfigExclusionPostProcessor} algum dia
 * precisarem decidir de fontes diferentes, os dois PRECISAM mudar juntos.
 */
@Configuration
public class VisionAiConfig {

    @Bean("ollamaVisionChatClient")
    public ChatClient ollamaVisionChatClient(OllamaChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    @Bean("geminiVisionChatClient")
    @ConditionalOnExpression("!'${GEMINI_API_KEY:}'.isBlank()")
    public ChatClient geminiVisionChatClient(GoogleGenAiChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
