package com.fintech.api.service.imports.vision;

import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;

/**
 * Porta para um provider de modelo de visão (LLM multimodal) capaz de extrair dados estruturados
 * de uma imagem de comprovante. Introduzida na Onda 1 do plano "extração Gemini primário / Ollama
 * fallback" — nesta Onda existe uma única implementação ({@code OllamaVisionClient}); a Onda
 * seguinte soma um provider Gemini e o {@code VisionExtractor} escolhe entre eles por
 * disponibilidade (spec §3, plano "Onda 2").
 *
 * <p><b>Por que devolve o {@link LlmReceiptExtractionDTO} CRU (spec §3.1)</b>, em vez de já
 * devolver o {@code NormalizedBatchDTO} da Fase 0: o guarda-corpo de plausibilidade (recusa de
 * imagem multi-transação #193, validação de {@code amount}) precisa ficar de FORA do laço de
 * fallback entre providers, e precisa ficar assim <b>por construção</b> — não por disciplina de
 * quem escreve o próximo provider. Se a porta devolvesse o DTO já normalizado/validado, seria
 * IMPOSSÍVEL tentar o próximo provider só porque o primeiro decidiu "isto é um extrato": a
 * decisão de aceitar ou recusar o conteúdo é de quem orquestra (o {@code VisionExtractor}), nunca
 * do client individual. Deixar o client devolver o dado cru — sem guarda-corpo nenhum — é o que
 * torna essa separação impossível de violar por acidente.
 */
public interface VisionModelClient {

    /**
     * Chama o modelo de visão com o prompt fixo e a imagem, e devolve a saída estruturada CRUA
     * (sem qualquer validação de plausibilidade — isso é responsabilidade de quem chama).
     *
     * <p>Generificado no #194 (extração multi-transação por imagem): o {@code VisionExtractor}
     * passou a pedir DOIS schemas de saída diferentes ao mesmo client — o comprovante plano
     * (Fase 1) e, quando a imagem é um extrato, uma lista de linhas. Só 2 implementações
     * existem, então generificar o método troca uma linha (o {@code .entity(Class)} do Spring
     * AI já aceita qualquer tipo) em vez de duplicar toda a mecânica de fallback/classificação
     * de disponibilidade numa segunda implementação por client.
     *
     * @param prompt          prompt fixo, igual para todos os providers (spec: "Prompt — o
     *                        mesmo para os dois providers", evita duplicar superfície de
     *                        manutenção)
     * @param mimeType        mimeType da imagem, usado para anexar o {@code Resource} à mensagem
     * @param imageResource   conteúdo da imagem como {@link Resource} — cada implementação
     *                        decide como empacotar (ex.: o Ollama precisa de um {@code Resource}
     *                        com {@code getFilename()} preenchido; ver {@code OllamaVisionClient})
     * @param responseType    o record de saída que o Spring AI deve preencher (schema JSON
     *                        gerado a partir dele via {@code .entity(Class)})
     * @param maxOutputTokens teto de tokens de saída, ou {@code null} para não aplicar teto
     *                        nenhum (comportamento idêntico ao de antes do #194 — usado no
     *                        caminho de comprovante, já calibrado, para não arriscar regressão)
     * @return a saída estruturada do modelo, ou {@code null} se o provider não retornou nada
     */
    <T> T extract(String prompt, MimeType mimeType, Resource imageResource, Class<T> responseType, Integer maxOutputTokens);

    /** Identificador curto do provider (ex.: {@code "ollama"}), usado para montar {@code extractorUsed}. */
    String providerId();

    /** Identificador do modelo usado (ex.: {@code "llama3.2-vision"}), usado para montar {@code extractorUsed}. */
    String modelId();
}
