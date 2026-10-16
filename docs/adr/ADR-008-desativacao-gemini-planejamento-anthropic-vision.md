# ADR-008: Desativação do Gemini Free Tier e Viabilidade da Anthropic API para Visão

## Status

Aprovado — 2026-10-16.
Substitui a diretriz provisória da ADR-007 (que mantinha o Gemini como primário até validação de corpus maior).

---

## 1. Contexto

O sub-funil de visão (`VisionExtractor`) foi desenhado na Onda 2 para ter o **Gemini 2.5 Flash** (Google AI Studio Free Tier) como provider primário (`@Order(10)`) e o **Ollama** no homelab (`qwen3-vl:8b-instruct`) como fallback (`@Order(20)`).

Durante a preparação para o lançamento comercial do MVP (v0.7.0+), dois riscos críticos relacionados ao Gemini foram reavaliados:

1. **Privacidade e LGPD:** Os termos de serviço do Google AI Studio (Free Tier) explicitam que conteúdos trafegados (incluindo imagens e prompts) podem ser auditados por revisores humanos e utilizados para treino de produtos e modelos do Google. No domínio do *Nosso Dinheirinho*, comprovantes de pagamento (Pix, notas fiscais, faturas) carregam nomes completos, CPF, chaves Pix, agência/conta e hábitos de consumo detalhados. Enviar esses dados para uma API pública não-comercial sem acordo de processamento de dados (DPA) viola as diretrizes de privacidade e proteção de dados financeiros da LGPD.
2. **Estabilidade e Alucinação:** Modelos generativos em modo thinking ou com janelas mal dimensionadas apresentaram intermitências conhecidas (truncamento de JSON com pensamento expandido e risco de leitura incorreta de dígitos em comprovantes).

Paralelamente, a equipe adotou a **Anthropic API** (com chave comercial pay-as-you-go) para o desenvolvimento do software, levantando a questão da viabilidade técnica, financeira e de segurança de adotar a Anthropic também no pipeline de visão da aplicação.

---

## 2. Decisão

### A) Desativação Imediata do Gemini para o MVP
1. O Gemini é **desativado no lançamento do MVP**.
2. A desativação é puramente configuracional e não quebra compatibilidade:
   * `spring.ai.google.genai.api-key=${GEMINI_API_KEY:}` em `application.properties` e `application-prod.properties` garante default vazio.
   * A ausência da chave aciona o `GeminiAutoConfigExclusionPostProcessor` e `@ConditionalOnExpression("!'${GEMINI_API_KEY:}'.isBlank()")`, garantindo que o bean `GeminiVisionClient` não seja instanciado e o autoconfigure seja excluído.
3. **O MVP opera em modo Local-First / Determinístico:**
   * Extração de extratos e faturas suportada 100% pelos extratores determinísticos locais: `OfxExtractor`, `CsvExtractor` e `PdfTextExtractor` (com templates Apache PDFBox para Itaú e Nubank). Custo zero, sem rede externa, zero vazamento de dados.
   * Extração de fotos/imagens suportada exclusivamente pelo **Ollama local** (`qwen3-vl:8b-instruct`) rodando no cluster k3s do homelab (ADR-004 e ADR-007).

### B) Avaliação de Viabilidade da Anthropic API para Visão (Fase 2 Pós-MVP)

A adoção do **Claude 3.5 Haiku** como provider de visão gerenciado na nuvem foi avaliada e considerada **altamente viável**, ficando aprovada como arquitetura de transição para a Fase pós-MVP:

| Dimensão | Avaliação para Claude 3.5 Haiku | Veredito |
| :--- | :--- | :--- |
| **Técnica** | Suportado nativamente pelo Spring AI (`spring-ai-starter-model-anthropic`). Suporta entrada de imagem base64 e saída estruturada JSON com fidelidade superior a modelos abertos em OCR numérico. Latência média: 1,5s a 3s. | **Aprovado** |
| **Financeira** | Custo de $0,80 / 1M tokens de input e $4,00 / 1M tokens de output. Um comprovante típico consome ~1.600 tokens de imagem + ~200 tokens de resposta JSON, resultando em aproximadamente **US$ 0,002 por comprovante** (menos de R$ 0,015). 1.000 comprovantes custariam cerca de US$ 2,00. | **Aprovado** |
| **Segurança / LGPD** | Os Termos Comerciais da Anthropic API garantem **Zero Data Retention** para treinamento de modelos. A Anthropic disponibiliza DPA formal para compliance com LGPD e GDPR. Dados de clientes não são expostos a revisores públicos. | **Aprovado** |

---

## 3. Consequências

### Positivas
* **Conformidade Imediata com LGPD:** Nenhum dado financeiro de usuário trafega pelo tier gratuito do Gemini no lançamento do MVP.
* **Resiliência do Boot:** `application-prod.properties` agora possui fallback vazio `${GEMINI_API_KEY:}`, eliminando qualquer risco de falha de boot no cluster k8s caso a secret seja removida.
* **Caminho Claro de Escala:** A decisão pós-MVP está documentada: quando o volume de fotos crescer e a GPU do homelab se tornar um gargalo operacional, a transição para o Claude 3.5 Haiku custará centavos e resolverá a infraestrutura sem expor dados.

### Negativas / Limitações Temporárias
* Usuários do piloto que subirem imagens dependerão da disponibilidade do Ollama no homelab. Caso o homelab esteja offline, o upload de imagem falha com erro explícito amigável (`batch FAILED`), direcionando o usuário para o lançamento manual ou importação de PDF/OFX (que funcionam localmente no backend sem IA).
