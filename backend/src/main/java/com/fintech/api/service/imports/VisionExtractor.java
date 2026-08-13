package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportMode;
import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.service.imports.vision.VisionModelClient;
import com.fintech.api.service.imports.vision.VisionProviderErrorClassifier;
import com.fintech.api.service.imports.vision.VisionProviderUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extrator de visão — implementação da porta {@link TransactionExtractor}. A partir da Onda 1
 * (plano "extração Gemini primário / Ollama fallback") a chamada ao modelo em si vive atrás da
 * porta {@link VisionModelClient} — este extrator não sabe mais que existe um {@code ChatClient}
 * ou um Ollama; recebe uma {@link List} de clients ordenada por {@code @Order} (Gemini 10, Ollama
 * 20) e tenta em ordem.
 *
 * <p><b>Política de fallback (Onda 4, spec §3.2):</b> só uma falha de DISPONIBILIDADE
 * ({@link VisionProviderUnavailableException} — 429/5xx/timeout/401/403/400) dispara a tentativa
 * do PRÓXIMO client da lista. Qualquer outra exceção (incl. {@link ExtractionException} de
 * conteúdo — imagem ilegível, {@code amount} implausível, ou falha de conteúdo na 2ª chamada do
 * caminho de extrato — #194) propaga IMEDIATAMENTE, sem tentar o próximo — falha de conteúdo
 * repetida no outro modelo pagaria latência dobrada pra chegar à MESMA conclusão, e mascararia o
 * {@code failureReason} de #193. Essa distinção é garantida por CONSTRUÇÃO: o guarda-corpo de
 * plausibilidade só roda depois que um client já "venceu" (retornou sem lançar), então é
 * fisicamente impossível ele disparar fallback. O print de extrato (multi-transação), que #193
 * recusava com este mesmo mecanismo, foi SUBSTITUÍDO pelo caminho de aceite do #194
 * ({@link #extractStatement}) — não é mais um caso de recusa/falha.
 *
 * <p>Fluxo: monta o prompt fixo + a imagem, delega ao {@link VisionModelClient} escolhido (que
 * devolve a saída TIPADA crua — {@link LlmReceiptExtractionDTO}), e então
 * <b>revalida a PLAUSIBILIDADE</b> do nosso lado (guarda-corpo §2.g): schema íntegro não garante
 * conteúdo são — o modelo pode alucinar um valor com formato válido. Só depois mapeia para o
 * {@link NormalizedBatchDTO} da Fase 0.
 *
 * <p>{@code requires_review} não é decidido por CONFIANÇA aqui (§2.f) — o {@code ImportService}
 * deriva por threshold a partir dos valores + confiança por campo que este extrator produz. A
 * ÚNICA exceção é o caminho de extrato (#194, {@link #mapStatementLine}): ali o extrator FORÇA
 * {@code true} incondicional (piso, nunca confiado por si só — {@code ImportService} ainda faz
 * o OR com o cálculo por threshold), staged rollout enquanto não há dado de produção sobre
 * acurácia do modelo em listas.
 *
 * <p>{@code @Order(LOWEST)}: é o ÚLTIMO extrator do funil do {@link ExtractionRouter} (roadmap
 * §1.2 — padrão universal → genérico → IA). IA é cara e ambígua; só entra quando nenhum parser
 * determinístico (OFX, CSV) reconheceu o arquivo.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class VisionExtractor implements TransactionExtractor {

    // Magic numbers dos formatos de imagem aceitos — supports() decide por BYTES, nunca pelo
    // mimeType do cliente (o browser erra/mente o Content-Type com frequência).
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] GIF = {0x47, 0x49, 0x46};
    private static final byte[] WEBP_RIFF = {0x52, 0x49, 0x46, 0x46};

    private final List<VisionModelClient> visionModelClients;
    private final String extractorVersion;
    // #194 — limites do caminho de EXTRATO (spec §6.4/§2.e): checados DEPOIS da extração (não dá
    // pra saber a contagem de linhas antes de chamar o modelo). Teto de saída isolado só nesta
    // chamada — o caminho de comprovante (Fase 1, já calibrado) segue sem teto, maxOutputTokens=null.
    private final int statementMaxLines;
    private final int statementMaxOutputTokens;

    // Formato pt-BR de data — fallback quando o modelo devolve dd/MM/yyyy apesar do pedido de ISO.
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    // Prompt fixo. Português alinhado ao domínio (comprovantes/recibos BR). Pedimos confiança
    // por campo E agregada — é o que alimenta o requires_review derivado depois. As instruções
    // de formato/schema JSON são anexadas automaticamente pelo Spring AI via .entity(...).
    private static final String PROMPT = """
            Você é um assistente que extrai dados de comprovantes financeiros (recibos, notas,
            comprovantes de PIX, faturas de compra). Analise a imagem e extraia APENAS os dados
            visíveis. Leia os dígitos DIRETAMENTE da imagem — NUNCA use números escritos nestas
            instruções. Se um campo não estiver legível, use confiança baixa (próxima de 0.0)
            para aquele campo.

            Regras:
            - amount: o VALOR TOTAL exato mostrado na imagem (o campo "Valor total", "Valor" ou
              similar), como número decimal com ponto. No Brasil a vírgula é o separador decimal
              e o ponto é separador de milhar: converta removendo os separadores de milhar e
              trocando a vírgula decimal por ponto. Leia os dígitos da imagem; nunca invente nem
              copie números deste texto.
            - transactionDate: a data da transação no formato ISO yyyy-MM-dd. Copie a data EXATA
              da imagem, INCLUSIVE o ano — nunca altere nem presuma o ano.
            - description: o nome do RECEBEDOR/estabelecimento (a empresa ou pessoa), NÃO o tipo
              da transação (não use "Pix Enviado", "Pagamento", "Compra aprovada" e afins).
            - direction: "debit" para saída de dinheiro (compra, despesa, pagamento, Pix enviado);
              "credit" para entrada (recebimento, estorno, Pix recebido). Compra é "debit".
            - paymentMethod: pix, credito, debito, dinheiro ou boleto, conforme a imagem.
            - Para cada campo, informe uma confiança de 0.0 a 1.0 na sua leitura.
            - overallConfidence: sua confiança agregada na extração completa.
            - multipleTransactionsDetected: true SOMENTE se a imagem mostrar uma LISTA de vários
              lançamentos (extrato bancário, fatura de cartão, histórico) — várias linhas, cada
              uma com sua própria data e seu próprio valor. Um comprovante único é false, mesmo
              que mostre vários números (taxas, saldo, total). Conte as LINHAS de lançamento.
            """;

    // Prompt do caminho de EXTRATO (#194) — só pedido numa 2ª chamada, quando a 1ª sinaliza
    // multipleTransactionsDetected=true. Mesmas regras de leitura do prompt de comprovante
    // (nunca inventar dígito, nunca presumir ano), adaptadas para LISTA.
    private static final String PROMPT_STATEMENT = """
            Você é um assistente que extrai lançamentos de um print de extrato bancário ou
            histórico de transações (várias linhas, cada uma com sua própria data e valor).
            Analise a imagem e liste APENAS os lançamentos visíveis, na ordem em que aparecem.
            Leia os dígitos DIRETAMENTE da imagem — NUNCA use números escritos nestas instruções.

            Regras:
            - lines: uma entrada por lançamento visível. NUNCA inclua linha de saldo, cabeçalho,
              rodapé ou total como se fosse um lançamento. Não complete nem invente uma linha
              cortada/ilegível — inclua com confiança baixa nos campos incertos, não a descarte.
            - amount: o valor da transação, sempre POSITIVO (a direção informa o sinal, não o
              valor), como número decimal com ponto. No Brasil a vírgula é o separador decimal
              e o ponto é separador de milhar: converta removendo os separadores de milhar e
              trocando a vírgula decimal por ponto.
            - transactionDate: a data da transação no formato ISO yyyy-MM-dd. Copie a data EXATA
              de cada linha, inclusive o ano quando visível — nunca presuma um ano que não está
              na imagem.
            - direction: "debit" para saída/despesa, "credit" para entrada/receita — leia pela
              seção/rótulo da linha (ex.: "Total de entradas"/"Total de saídas" da seção onde a
              linha está), nunca infira só pelo sinal do valor impresso.
            - declaredTotalDebits/declaredTotalCredits: só preencha se a imagem mostrar
              EXPLICITAMENTE um total impresso de débitos ou créditos do período (ex.: "Total de
              saídas: R$ 1.234,56"). Nunca calcule nem estime — deixe null se não houver total
              impresso.
            - Para cada campo, informe uma confiança de 0.0 a 1.0 na sua leitura.
            - overallConfidence: sua confiança agregada na extração completa da lista.
            """;

    public VisionExtractor(
            List<VisionModelClient> visionModelClients,
            @Value("${import.vision.extractor-version:unknown}") String extractorVersion,
            @Value("${import.vision.statement.max-lines:60}") int statementMaxLines,
            @Value("${import.vision.statement.max-output-tokens:4096}") int statementMaxOutputTokens) {
        this.visionModelClients = visionModelClients;
        this.extractorVersion = extractorVersion;
        this.statementMaxLines = statementMaxLines;
        this.statementMaxOutputTokens = statementMaxOutputTokens;
    }

    @Override
    public boolean supports(ExtractionInput input) {
        byte[] content = input.content();
        return startsWith(content, JPEG) || startsWith(content, PNG) || startsWith(content, GIF)
                || startsWith(content, WEBP_RIFF);
    }

    private boolean startsWith(byte[] content, byte[] magic) {
        if (content == null || content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.IMAGE;
    }

    @Override
    public String extractorVersion() {
        return extractorVersion;
    }

    @Override
    public NormalizedBatchDTO extract(ExtractionInput input) {
        if (visionModelClients.isEmpty()) {
            // Config quebrada (nenhum client habilitado, ex.: sem GEMINI_API_KEY e sem Ollama) —
            // falha EXPLÍCITA, não um NullPointerException em visionModelClients.get(0).
            throw new ExtractionException("Nenhum provider de visão configurado.");
        }

        byte[] imageBytes = input.content();
        ImportMode mode = input.mode();
        // mimeType do cliente é só um HINT pro client montar o Resource — quem decide se é
        // imagem de verdade é o supports() por magic number, chamado antes pelo router.
        String mimeType = input.mimeType() != null ? input.mimeType() : "image/jpeg";
        MimeType mime = MimeTypeUtils.parseMimeType(mimeType);
        Resource imageResource = new ByteArrayResource(imageBytes);

        VisionModelClient winner = null;
        LlmReceiptExtractionDTO raw = null;
        long latencyMs = 0;

        // Proveniência do fallback (V28): só populada se ALGUM client tentado antes do vencedor
        // falhou por disponibilidade. fallbackFrom == null já responde "houve fallback?" (spec
        // §5.1) — guardamos o PRIMEIRO que falhou (com só 2 providers configurados hoje, é
        // exatamente "de quem" a extração precisou fugir).
        String fallbackFrom = null;
        String fallbackReason = null;
        VisionProviderUnavailableException lastFailure = null;

        for (VisionModelClient client : visionModelClients) {
            long startNanos = System.nanoTime();
            try {
                raw = client.extract(PROMPT, mime, imageResource, LlmReceiptExtractionDTO.class, null);
                latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                winner = client;
                break;
            } catch (VisionProviderUnavailableException e) {
                // Só ESTA exceção é capturada aqui — qualquer outra (ExtractionException de
                // conteúdo, erro inesperado) propaga direto pro chamador, sem tentar o próximo
                // client. É essa seletividade do catch que implementa a regra central da Onda.
                if (fallbackFrom == null) {
                    fallbackFrom = client.providerId();
                    fallbackReason = e.reasonCode();
                }
                lastFailure = e;

                // 401/403 é ERROR, não WARN: degradar pro próximo provider é UX melhor que
                // falhar de vez, mas silenciar um provider morto por credencial inválida faria
                // ninguém perceber que ele está fora do ar (spec: "401/403 — cai pro fallback,
                // mas loga ERROR").
                if (VisionProviderErrorClassifier.REASON_AUTH.equals(e.reasonCode())) {
                    log.error("Provider de visão indisponível por falha de autenticação, tentando "
                                    + "próximo (se houver): provider={}, reason={}, motivo={}",
                            client.providerId(), e.reasonCode(), e.getMessage());
                } else {
                    log.warn("Provider de visão indisponível, tentando próximo (se houver): "
                                    + "provider={}, reason={}, motivo={}",
                            client.providerId(), e.reasonCode(), e.getMessage());
                }
            }
        }

        if (winner == null) {
            // Todos os providers da lista falharam por disponibilidade — o motivo do ÚLTIMO erro
            // (spec: "ExtractionException com o motivo do ÚLTIMO"), numa mensagem redigida por
            // nós (nunca o texto cru do provider — lastFailure.getMessage() já é nosso, não do
            // SDK; ver VisionProviderUnavailableException).
            String reason = lastFailure != null ? lastFailure.getMessage() : "nenhum provider disponível";
            throw new ExtractionException(
                    "Não foi possível extrair os dados da imagem: todos os provedores de visão "
                            + "configurados estão indisponíveis no momento (" + reason + ").",
                    lastFailure);
        }

        if (raw == null) {
            throw new ExtractionException("Modelo de visão não retornou dados estruturados.");
        }

        // Critério de saída: latência conhecida (no homelab o custo em $ é zero; medimos tempo).
        // Tokens não são expostos pelo caminho .entity(); latência é a métrica que importa aqui.
        log.info("Extração de visão concluída: provider={}, model={}, extractorVersion={}, latencyMs={}, "
                        + "overallConfidence={}, fallbackFrom={}, fallbackReason={}",
                winner.providerId(), winner.modelId(), extractorVersion, latencyMs, raw.overallConfidence(),
                fallbackFrom, fallbackReason);

        // #194 — TRUE.equals (não `raw.multipleTransactionsDetected()`) porque null = modelo que
        // não preencheu a flag: nesse caso extrai normal como comprovante. Ausência de sinal não
        // é sinal de "é extrato". O antigo guard-rail do #193 (recusa explícita) foi SUBSTITUÍDO
        // por este caminho de aceite — não convivem: uma imagem multi-transação nunca mais falha
        // só por ser multi-transação.
        if (Boolean.TRUE.equals(raw.multipleTransactionsDetected())) {
            return extractStatement(winner, mime, imageResource, mode, latencyMs, fallbackFrom, fallbackReason);
        }

        return toNormalizedBatch(raw, mode, winner, latencyMs, fallbackFrom, fallbackReason);
    }

    /**
     * Caminho de EXTRATO (#194, spec §6.3) — 2ª chamada ao MESMO client vencedor da 1ª (nunca
     * tenta outro provider aqui: trocar de modelo no meio da extração misturaria leituras
     * diferentes da mesma imagem). Schema pedido é {@link LlmStatementExtractionDTO} — o Spring
     * AI vincula o JSON Schema de saída ANTES da chamada, então não dá pra pedir os dois formatos
     * na mesma chamada; por isso são duas chamadas sequenciais, não uma só.
     */
    private NormalizedBatchDTO extractStatement(
            VisionModelClient winner, MimeType mime, Resource imageResource, ImportMode mode,
            long firstCallLatencyMs, String fallbackFrom, String fallbackReason) {
        long startNanos = System.nanoTime();
        LlmStatementExtractionDTO statement;
        try {
            statement = winner.extract(
                    PROMPT_STATEMENT, mime, imageResource, LlmStatementExtractionDTO.class, statementMaxOutputTokens);
        } catch (VisionProviderUnavailableException e) {
            // Falha de disponibilidade NESTA chamada não dispara fallback pro próximo provider
            // (diferente da 1ª chamada) — já comprometemos a extração com o client vencedor.
            throw new ExtractionException(
                    "Não foi possível extrair a lista de lançamentos do extrato: provider indisponível "
                            + "no meio da extração (" + e.getMessage() + ").", e);
        }
        long statementLatencyMs = (System.nanoTime() - startNanos) / 1_000_000;

        if (statement == null || statement.lines() == null || statement.lines().isEmpty()) {
            throw new ExtractionException("Não foi possível reconhecer nenhum lançamento no extrato.");
        }
        if (statement.lines().size() > statementMaxLines) {
            throw new ExtractionException(
                    "O extrato tem mais de " + statementMaxLines
                            + " lançamentos — recorte a imagem em partes menores.");
        }

        reconcileTotals(statement);

        BigDecimal statementOverallConfidence = clampConfidence(statement.overallConfidence());
        List<NormalizedTransactionDTO> transactions = statement.lines().stream()
                .map(line -> mapStatementLine(line, statementOverallConfidence))
                .toList();

        // Sufixo "_statement" distingue o volume/custo deste caminho do de comprovante (spec §3)
        // — é o marcador usado pra revisitar o gate de confiança forçada no futuro.
        String extractorUsed = "vision_statement_" + winner.providerId() + "_" + winner.modelId();
        long totalLatencyMs = firstCallLatencyMs + statementLatencyMs;
        log.info("Extração de extrato concluída: provider={}, model={}, linhas={}, latencyMs={}",
                winner.providerId(), winner.modelId(), transactions.size(), totalLatencyMs);

        return new NormalizedBatchDTO(
                mode, ImportSourceType.IMAGE, extractorUsed, extractorVersion, transactions,
                winner.providerId(), winner.modelId(), (int) totalLatencyMs, fallbackFrom, fallbackReason);
    }

    /**
     * Mapeia uma linha do extrato para o schema normalizado. {@code requires_review=true}
     * incondicional (spec §2.d/§3 — staged rollout: zero dado de produção sobre acurácia do
     * modelo em lista, blast radius maior que comprovante único) — só tem efeito real porque
     * {@code ImportService.createBatch} foi ajustado para ler este campo como PISO (nunca teto),
     * nunca ceiling sobre o que os outros extratores já mandam.
     *
     * <p>{@code overallConfidence} vem do MODELO ({@code statement.overallConfidence()}), nunca
     * hardcoded — achado de code review: {@code ImportService.patchStaged} RE-DERIVA
     * {@code requires_review} a partir desse valor quando o usuário edita a staged
     * (`deriveRequiresReview`: overall &lt; 0.90 → true). Um valor fixo alto desativaria o floor
     * silenciosamente no primeiro campo que o usuário corrigir, mesmo sem nunca ter verificado
     * o resto da linha.
     */
    private NormalizedTransactionDTO mapStatementLine(
            LlmStatementExtractionDTO.Line line, BigDecimal statementOverallConfidence) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();

        BigDecimal amount = line.amount();
        BigDecimal amountConfidence = clampConfidence(line.amountConfidence());
        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
            // Sinal negativo é ambíguo/conflitante com `direction` (a fonte de verdade do sinal
            // aqui) — normaliza pro valor absoluto e zera a confiança (força revisão), nunca
            // descarta a linha (issue #194 exige não-destrutivo).
            amount = amount.abs();
            amountConfidence = BigDecimal.ZERO;
        }
        fields.put("amount", new StagedFieldValueDTO(amount, amountConfidence));

        NormalizedDate date = normalizeDate(line.transactionDate(), line.transactionDateConfidence());
        fields.put("transaction_date", new StagedFieldValueDTO(date.isoValue(), date.confidence()));

        fields.put("description",
                new StagedFieldValueDTO(blankToNull(line.description()), clampConfidence(line.descriptionConfidence())));
        fields.put("direction",
                new StagedFieldValueDTO(normalizeDirection(line.direction()), clampConfidence(line.directionConfidence())));

        return new NormalizedTransactionDTO(
                null,
                fields,
                null, null,
                statementOverallConfidence,
                true,  // requires_review forçado — ver javadoc do método
                null);
    }

    /**
     * Reconciliação soma×total (spec §6.4) — sinal de LOG, nunca gate. Compara {@code Σ debit}
     * com {@code declaredTotalDebits} e {@code Σ credit} com {@code declaredTotalCredits}, cada
     * um só quando o total foi declarado (não estima nem calcula o que a imagem não mostrou).
     * Mismatch fora da tolerância NUNCA descarta linha nenhuma — `requires_review` já é `true`
     * incondicional nesta versão (mapStatementLine), então o mismatch não muda nenhuma decisão,
     * só entra no log estruturado para telemetria futura.
     */
    private void reconcileTotals(LlmStatementExtractionDTO statement) {
        reconcileDirection(statement.lines(), "debit", statement.declaredTotalDebits());
        reconcileDirection(statement.lines(), "credit", statement.declaredTotalCredits());
    }

    private void reconcileDirection(
            List<LlmStatementExtractionDTO.Line> lines, String direction, BigDecimal declaredTotal) {
        if (declaredTotal == null) {
            return;
        }
        BigDecimal sum = lines.stream()
                .filter(line -> direction.equalsIgnoreCase(normalizeDirection(line.direction())))
                .map(LlmStatementExtractionDTO.Line::amount)
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Tolerância: absoluta pra extratos pequenos, relativa (1%) pra grandes — erro de
        // arredondamento por linha acumula com o tamanho do extrato.
        BigDecimal tolerance = declaredTotal.abs().multiply(new BigDecimal("0.01"))
                .max(new BigDecimal("0.02"));
        BigDecimal diff = sum.subtract(declaredTotal).abs();
        if (diff.compareTo(tolerance) > 0) {
            log.warn("Reconciliação soma×total divergente no caminho de extrato: direction={}, "
                            + "somaCalculada={}, totalDeclarado={}, diferenca={}, tolerancia={}",
                    direction, sum, declaredTotal, diff, tolerance);
        }
    }

    /**
     * GUARDA-CORPO (§2.g) + mapeamento do caminho de COMPROVANTE único. Revalida a
     * plausibilidade e converte a saída plana do modelo para o schema normalizado da Fase 0. O
     * {@code amount} é obrigatório e plausível (&gt; 0) — sem ele não há transação a lançar,
     * então falha a extração. Data ilegível não derruba a extração (o usuário completa na
     * revisão), mas zera a confiança para exigir olho.
     *
     * <p>Só chamado quando {@code multipleTransactionsDetected != true} — o caso "é extrato" já
     * foi desviado em {@link #extract(ExtractionInput)} para {@link #extractStatement}
     * (#194) antes deste método ser alcançado.
     */
    private NormalizedBatchDTO toNormalizedBatch(
            LlmReceiptExtractionDTO raw, ImportMode mode, VisionModelClient client, long latencyMs,
            String fallbackFrom, String fallbackReason) {
        if (raw.amount() == null || raw.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ExtractionException(
                    "Valor extraído ausente ou não plausível (amount=" + raw.amount() + ").");
        }

        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();
        fields.put("amount", new StagedFieldValueDTO(raw.amount(), clampConfidence(raw.amountConfidence())));

        NormalizedDate date = normalizeDate(raw.transactionDate(), raw.transactionDateConfidence());
        fields.put("transaction_date", new StagedFieldValueDTO(date.isoValue(), date.confidence()));

        fields.put("description",
                new StagedFieldValueDTO(blankToNull(raw.description()), clampConfidence(raw.descriptionConfidence())));
        fields.put("direction",
                new StagedFieldValueDTO(normalizeDirection(raw.direction()), clampConfidence(raw.directionConfidence())));
        if (blankToNull(raw.paymentMethod()) != null) {
            fields.put("payment_method",
                    new StagedFieldValueDTO(raw.paymentMethod(), clampConfidence(raw.paymentMethodConfidence())));
        }

        NormalizedTransactionDTO tx = new NormalizedTransactionDTO(
                null,
                fields,
                null,  // suggested_category — heurística é Fase 4
                null,
                clampConfidence(raw.overallConfidence()),
                null,  // requires_review derivado no ImportService (§2.f), nunca pelo modelo
                null);

        // extractorUsed carrega a proveniência: qual provider+modelo gerou o dado
        // (ex.: vision_ollama_qwen2.5vl) — mesmo formato de antes da Onda 1, agora vindo do client
        // VENCEDOR (o que efetivamente respondeu, não necessariamente o primeiro tentado).
        String extractorUsed = "vision_" + client.providerId() + "_" + client.modelId();
        // Proveniência estruturada (V28): quem MEDE é o extrator (aqui), quem GRAVA é o
        // ImportService — a fronteira "extrator não toca banco" não muda. fallbackFrom/Reason
        // (Onda 4) só vêm preenchidos quando um provider anterior falhou por disponibilidade.
        return new NormalizedBatchDTO(
                mode, ImportSourceType.IMAGE, extractorUsed, extractorVersion, List.of(tx),
                client.providerId(), client.modelId(), (int) latencyMs, fallbackFrom, fallbackReason);
    }

    /** Normaliza para "debit"/"credit"; qualquer coisa não reconhecida cai em "debit" (compra é o caso comum). */
    private String normalizeDirection(String raw) {
        return "credit".equalsIgnoreCase(raw != null ? raw.trim() : null) ? "credit" : "debit";
    }

    /**
     * Parseia a data ISO. Se ausente/ilegível, devolve valor null com confiança 0.0 — força a
     * revisão sem derrubar a extração inteira (o usuário completa a data na tela de revisão).
     */
    private NormalizedDate normalizeDate(String rawDate, Double rawConfidence) {
        String trimmed = blankToNull(rawDate);
        if (trimmed == null) {
            return new NormalizedDate(null, BigDecimal.ZERO);
        }
        // O modelo às vezes devolve a data em pt-BR (dd/MM/yyyy) apesar do pedido de ISO.
        // Tenta ISO e depois o formato brasileiro — recuperar a data é melhor que descartá-la;
        // só zera a confiança quando NENHUM formato conhecido casa (aí o usuário completa na revisão).
        for (DateTimeFormatter fmt : List.of(DateTimeFormatter.ISO_LOCAL_DATE, BR_DATE)) {
            try {
                LocalDate parsed = LocalDate.parse(trimmed, fmt);
                return new NormalizedDate(parsed.toString(), clampConfidence(rawConfidence));
            } catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        return new NormalizedDate(null, BigDecimal.ZERO);
    }

    /** Confiança em [0,1]; null vira 0.0 (ausência = duvidoso). */
    private BigDecimal clampConfidence(Double confidence) {
        if (confidence == null) {
            return BigDecimal.ZERO;
        }
        double clamped = Math.max(0.0, Math.min(1.0, confidence));
        return BigDecimal.valueOf(clamped);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private record NormalizedDate(String isoValue, BigDecimal confidence) {}
}
