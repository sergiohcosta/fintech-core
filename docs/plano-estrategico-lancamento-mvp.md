# Plano Estratégico de Lançamento — MVP "Nosso Dinheirinho"

> **Status:** Aprovado para execução · **Versão Base:** v0.7.0 · **Público-Alvo Inicial:** 5 a 10 usuários pilotos (casais/famílias/autônomos).

---

## 1. Diagnóstico do Estado Atual (v0.7.0)

O projeto atingiu um nível de maturidade técnica e arquitetural equivalente a produtos comerciais consolidados:
* **Backend:** Spring Boot 4 / Java 21, multi-tenant com Row-Level Security (RLS) no PostgreSQL, contabilidade double-entry, motor de recorrência RFC 5545 (RRULE), lock pessimista em faturas (`SELECT ... FOR UPDATE`), regras de parcelamento com resíduo garantido.
* **Frontend:** Angular 21 Zoneless (Signals), Material Design 3, bottom sheets, mobile-first, linha do tempo com visualizações em calendário, lista e horizonte temporal.
* **Diagnóstico de Risco:** **Armadilha do Over-Engineering.** A engenharia está pronta para escalar, mas o ciclo de desenvolvimento foi sequestrado por melhorias incrementais de infraestrutura (ex: instrumentação OTel/Micrometer de LLM). O foco estratégico agora é **congelar o escopo técnico** e colocar o produto nas mãos de usuários reais.

---

## 2. Decisão de Segurança e Privacidade de IA (Gemini & Extração)

### O Problema Identificado
* O **Google AI Studio (Free Tier)** não oferece garantia de privacidade comercial sob a LGPD: prompts e comprovantes enviados podem ser auditados por humanos e usados para treino de modelos.
* Comprovantes bancários (Pix, notas fiscais, faturas) carregam CPF, nomes completos, dados de agência/conta e hábitos financeiros sensíveis.
* Modelos de visão generativos possuem risco inerente de alucinação numérica pontual (troca de dígitos em valores monetários).

### Diretriz para o Lançamento
1. **Desativação do Gemini como Primário no MVP:**
   * O fluxo de importação principal no lançamento apoia-se nos **extratores determinísticos locais**: `OfxExtractor`, `CsvExtractor` e `PdfTextExtractor` (com templates estruturados Apache PDFBox para Itaú e Nubank).
   * **Ganhos:** Custo zero, processamento local em milissegundos, 100% de privacidade/LGPD e zero risco de alucinação de valores.
2. **Tratamento de Imagens e Comprovantes:**
   * **Opção Homelab:** Utilizar o Ollama local no cluster k3s com o modelo validado na ADR-007 (`qwen3-vl:8b-instruct`), garantindo que nenhum dado saia da infraestrutura própria.
   * **Comunicação ao Usuário:** Posicionar a leitura de fotos como recurso "Beta / Experimental" no lançamento, priorizando a importação de arquivos de fatura e extrato bancário (PDF/OFX).

---

## 3. Estratégia de Interface & Experiência do Usuário (UX)

Para eliminar a sensação de insegurança quanto ao visual e tornar a aplicação acolhedora para o público final:

### A) Empty State do Dia 0 (Onboarding Guiado)
Substituir a tela vazia do Dashboard no primeiro acesso por um card direto de boas-vindas:
* **Mensagem:** *"Bem-vindo ao Nosso Dinheirinho!"*
* **Ação 1:** [Cadastrar primeira conta ou cartão]
* **Ação 2:** [Importar primeiro extrato bancário (PDF ou OFX)]

### B) Redução da Carga Cognitiva no Menu
* Ocultar termos técnicos como *"Taxonomia"* (mover para Configurações ou renomear para *"Mapear Categorias"*).
* Agrupar *"Linha do Tempo"* como filtro visual dentro de *"Transações"*.
* Garantir que no mobile o botão flutuante principal seja sempre o **"+" (Quick Entry)** para registro imediato de despesas.

### C) Validação do "Usuário Silencioso"
* Antes da abertura pública, realizar 1 sessão de teste assistido com 1 usuário leigo (amigo, familiar ou parceiro).
* Deixar o usuário executar o fluxo de cadastro, inserção de despesa e importação de fatura sem intervenção ou explicação prévia.
* Corrigir apenas os pontos de atrito identificados na sessão.

---

## 4. Caminho Crítico para os Primeiros 10 Usuários Pagantes

1. **Ajuste de UX:** Implementar o card de boas-vindas para novos tenants.
2. **Deploy Estável:** Subir a versão `v0.7.0` no ambiente de hospedagem definido (Fly.io ou K8s Homelab com domínio público e SSL).
3. **Gateway de Cobrança:** Finalizar a ativação de planos via Stripe Checkout conforme previsto no `commercialization-plan.md`.
4. **Coleta de Feedback Qualitativo:** Acompanhar de perto o primeiro mês de uso dos 10 usuários piloto.
