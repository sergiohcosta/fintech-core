package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.service.imports.templates.PdfBankTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrator de texto de PDF — primeira fatia da Fase 3 do funil do {@link ExtractionRouter}
 * (roadmap §1.2: padrão universal (OFX) → GENÉRICO (CSV, aqui) → IA). Diferente do CSV, texto de
 * PDF não tem separador estrutural nenhum (sem coluna, sem tag) — o único sinal disponível é o
 * padrão típico de uma linha de extrato: uma DATA e um VALOR monetário na mesma linha. Sem
 * registry de templates ainda (isso é fatia futura da Fase 3, que depende de volume real por
 * banco), a heurística é genérica e por isso necessariamente mais frágil que a de coluna do
 * {@link CsvExtractor} — esperado, não defeito: é o dado que a fase existe para produzir
 * (roadmap §3, "cada transição exige aprendizado").
 *
 * <p><b>PDF escaneado (sem camada de texto)</b> é rasterizado página a página (Apache PDFBox
 * {@link PDFRenderer}) e cada página delega ao {@link VisionExtractor} já existente — reaproveita
 * de graça o fallback Gemini→Ollama e a detecção multi-transação (#194) sem duplicar lógica de
 * visão aqui. Fail-fast: o número de páginas é checado ANTES de chamar qualquer IA, porque o
 * custo (tempo + possível chamada paga) de renderizar/extrair um documento acima do limite não
 * reverteria em nada aproveitável. All-or-nothing: falha de UMA página derruba o documento
 * inteiro — persistir só parte de um extrato seria uma visão financeira incompleta e silenciosa.
 * Sem template bancário para este caminho (mesmo princípio dos templates de texto: não construir
 * reconhecimento específico sem volume real).
 *
 * <p>{@code supports()} reconhece QUALQUER PDF pelo magic number, escaneado ou não — a distinção
 * "tem texto ou não" só é possível depois que o PDFBox abre o documento, dentro de
 * {@link #extract}.
 */
@Component
@Order(30)
@Slf4j
public class PdfTextExtractor implements TransactionExtractor {

    private static final String EXTRACTOR_USED = "pdf_text_v1";

    // Magic number do formato PDF ("%PDF-") — supports() decide por BYTES, nunca pelo mimeType
    // do cliente, mesma regra dos demais extratores do funil.
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D};

    // Abaixo deste número de caracteres não-whitespace, tratamos o PDF como "sem camada de texto"
    // (escaneado) — limiar generoso de propósito: preferir falso negativo (tentar parsear um PDF
    // realmente sem texto) é pior que falso positivo (recusar um PDF com pouquíssimo texto real),
    // porque o guard-rail de "zero transações aproveitáveis" do ImportService já cobre o segundo caso.
    private static final int MIN_TEXT_CHARS = 20;

    // Data: DD/MM/YYYY, DD/MM/YY (ano reduzido, base 2000) ou YYYY-MM-DD.
    private static final Pattern DATE_PATTERN =
            Pattern.compile("\\b(\\d{2}/\\d{2}/\\d{4}|\\d{2}/\\d{2}/\\d{2}|\\d{4}-\\d{2}-\\d{2})\\b");

    // Valor monetário: sinal opcional, "R$ " opcional, dígitos com separador de milhar (ponto OU
    // vírgula) e sempre 2 casas decimais no final — mesma ambiguidade pt-BR vs. padrão do CSV
    // (Fase 2), resolvida por VALOR (qual separador aparece por último) em parseAmount().
    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("([+-])?\\s*(?:R\\$\\s?)?(\\d{1,3}(?:[.,]\\d{3})*[.,]\\d{2})");

    private static final Pattern DEBIT_KEYWORD = Pattern.compile("(?i)d[ée]bito");
    private static final Pattern CREDIT_KEYWORD = Pattern.compile("(?i)cr[ée]dito");

    private static final DateTimeFormatter BR_DATE_LONG = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final DateTimeFormatter BR_DATE_SHORT = DateTimeFormatter.ofPattern("dd/MM/uu");

    private final String extractorVersion;
    private final List<PdfBankTemplate> templates;
    // Tipado pela PORTA (TransactionExtractor), não pela classe concreta VisionExtractor: o único
    // contrato que este caminho usa é extract(ExtractionInput), já garantido pela interface — a
    // classe concreta só acoplaria sem necessidade e quebraria o @MockitoBean(name="visionExtractor")
    // dos testes existentes (eles substituem o bean pelo tipo da interface).
    private final TransactionExtractor visionExtractor;
    private final int scannedMaxPages;
    private final int scannedRenderDpi;

    // @Autowired explícito: com 2 construtores públicos (este + o de compatibilidade abaixo),
    // Spring não sabe mais qual escolher sozinho — sem a anotação, o contexto falha ao subir
    // (NoSuchMethodException, só visível em teste @SpringBootTest, não em unitário puro).
    @Autowired
    public PdfTextExtractor(
            @Value("${import.pdf-text.extractor-version:v1}") String extractorVersion,
            List<PdfBankTemplate> templates,
            @Qualifier("visionExtractor") TransactionExtractor visionExtractor,
            @Value("${import.pdf-scanned.max-pages:10}") int scannedMaxPages,
            @Value("${import.pdf-scanned.render-dpi:150}") int scannedRenderDpi) {
        this.extractorVersion = extractorVersion;
        this.templates = templates;
        this.visionExtractor = visionExtractor;
        this.scannedMaxPages = scannedMaxPages;
        this.scannedRenderDpi = scannedRenderDpi;
    }

    /** Construtor de compatibilidade — testes do caminho determinístico (PDF com texto) não precisam do caminho escaneado. */
    public PdfTextExtractor(String extractorVersion, List<PdfBankTemplate> templates) {
        this(extractorVersion, templates, null, 10, 150);
    }

    @Override
    public boolean supports(ExtractionInput input) {
        return startsWith(input.content(), PDF_MAGIC);
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
        return ImportSourceType.PDF_TEXT;
    }

    @Override
    public String extractorVersion() {
        return extractorVersion;
    }

    @Override
    public NormalizedBatchDTO extract(ExtractionInput input) {
        // PDDocument aberto UMA VEZ só: o caminho escaneado reusa o mesmo documento pra renderizar
        // páginas, evitando parsear o PDF duas vezes.
        try (PDDocument document = Loader.loadPDF(input.content())) {
            String text = extractText(document);

            long meaningfulChars = text.chars().filter(c -> !Character.isWhitespace(c)).count();
            if (meaningfulChars < MIN_TEXT_CHARS) {
                return extractScanned(document, input);
            }

            for (PdfBankTemplate template : templates) {
                if (template.matches(text)) {
                    YearMonth faturaAlvo = template.targetInvoiceReferenceMonth(text);
                    return new NormalizedBatchDTO(
                            input.mode(), ImportSourceType.PDF_TEXT, template.templateId(), extractorVersion,
                            template.parse(text, input.content()),
                            null, null, null, null, null,
                            faturaAlvo != null ? faturaAlvo.getYear() : null,
                            faturaAlvo != null ? faturaAlvo.getMonthValue() : null);
                }
            }

            // Nenhum template bateu — heurística genérica de linha (fatia 1, comportamento
            // inalterado). Linha sem data+valor não vira transação (ausência de sinal, não erro);
            // batch vazio cai no guard-rail já existente do ImportService.
            List<NormalizedTransactionDTO> transactions = parseLines(text);

            return new NormalizedBatchDTO(input.mode(), ImportSourceType.PDF_TEXT, EXTRACTOR_USED, extractorVersion, transactions);
        } catch (IOException e) {
            // Nenhuma exceção de infra (mensagem/stacktrace do PDFBox) cruza a borda da API —
            // mesma regra do restante do pipeline (ImportService.failureReasonFor).
            throw new ExtractionException("Não foi possível abrir este PDF — arquivo corrompido ou protegido por senha.", e);
        }
    }

    /** Extrai o texto bruto de um documento já aberto (evita reabrir o PDF). */
    private String extractText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return stripper.getText(document);
    }

    /**
     * Rasteriza cada página em PNG e delega ao {@link VisionExtractor} — mesmo prompt/fallback/
     * detecção multi-transação que a imagem avulsa já usa, só que uma vez por página. Proveniência
     * (provider/model/fallback) é capturada da PRIMEIRA página, mesmo critério do {@code
     * fallbackFrom} (primeiro sinal observado), pra não misturar "provider da última página" com
     * "fallback da primeira" — inconsistência que passaria despercebida numa auditoria.
     */
    private NormalizedBatchDTO extractScanned(PDDocument document, ExtractionInput input) throws IOException {
        int pageCount = document.getNumberOfPages();
        if (pageCount > scannedMaxPages) {
            // Fail-fast ANTES de renderizar ou chamar IA — custo parcial de um documento que nunca
            // vai virar um batch utilizável não vale a pena. ScannedPdfExtractionException (não
            // ExtractionException genérica) pra o ImportService gravar sourceType=PDF_SCANNED no
            // batch FAILED — sourceType() da interface é fixo em PDF_TEXT, cobre só o caso em que
            // extract() nunca chegou a decidir o sub-caminho.
            throw new ScannedPdfExtractionException(
                    "Este PDF escaneado tem " + pageCount + " páginas — acima do limite de "
                            + scannedMaxPages + " suportado. Divida o arquivo em partes menores.");
        }
        if (visionExtractor == null) {
            throw new ScannedPdfExtractionException("Extração de PDF escaneado não está configurada.");
        }

        PDFRenderer renderer = new PDFRenderer(document);
        List<NormalizedTransactionDTO> allTransactions = new ArrayList<>();
        String firstProvider = null;
        String firstModel = null;
        int totalLatencyMs = 0;
        String fallbackFrom = null;
        String fallbackReason = null;

        for (int page = 0; page < pageCount; page++) {
            BufferedImage image = renderer.renderImageWithDPI(page, scannedRenderDpi, ImageType.RGB);
            byte[] pngBytes;
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", output);
                pngBytes = output.toByteArray();
            }

            // Falha de QUALQUER página (visão indisponível ou conteúdo implausível) derruba o
            // documento inteiro (all-or-nothing, sem estado parcial persistido). Recapturada só
            // pra trocar o TIPO da exceção (mesma mensagem, mesma causa) — sourceType=PDF_SCANNED
            // no batch FAILED, mesmo motivo do guard-rail de páginas acima.
            NormalizedBatchDTO pageBatch;
            try {
                pageBatch = visionExtractor.extract(
                        new ExtractionInput(pngBytes, input.filename(), "image/png", input.mode()));
            } catch (ExtractionException e) {
                throw new ScannedPdfExtractionException(e.getMessage(), e);
            }

            allTransactions.addAll(pageBatch.transactions());
            totalLatencyMs += pageBatch.extractionLatencyMs() != null ? pageBatch.extractionLatencyMs() : 0;
            if (page == 0) {
                firstProvider = pageBatch.extractorProvider();
                firstModel = pageBatch.extractorModel();
            }
            if (fallbackFrom == null && pageBatch.fallbackFrom() != null) {
                fallbackFrom = pageBatch.fallbackFrom();
                fallbackReason = pageBatch.fallbackReason();
            }
        }

        if (allTransactions.isEmpty()) {
            throw new ScannedPdfExtractionException("Nenhuma transação foi reconhecida neste PDF escaneado.");
        }

        // requiresReview forçado true em toda linha — mesmo staged rollout do caminho de extrato
        // do VisionExtractor (#194): zero dado de produção sobre acurácia do modelo em página
        // rasterizada, então a confiança por campo sozinha não é suficiente pra dispensar revisão.
        List<NormalizedTransactionDTO> reviewedTransactions = allTransactions.stream()
                .map(tx -> new NormalizedTransactionDTO(
                        tx.transactionId(), tx.fields(), tx.suggestedCategoryCode(), tx.suggestedCategoryConfidence(),
                        tx.overallConfidence(), true, tx.duplicateCandidateOf()))
                .toList();

        String extractorUsed = "vision_pdf_scanned_" + firstProvider + "_" + firstModel;
        return new NormalizedBatchDTO(
                input.mode(), ImportSourceType.PDF_SCANNED, extractorUsed, extractorVersion,
                reviewedTransactions, firstProvider, firstModel, totalLatencyMs, fallbackFrom, fallbackReason);
    }

    /** Reconhece transações linha a linha: precisa casar UMA data E UM valor na mesma linha. */
    private List<NormalizedTransactionDTO> parseLines(String text) {
        List<NormalizedTransactionDTO> transactions = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            Matcher dateMatcher = DATE_PATTERN.matcher(line);
            Matcher amountMatcher = AMOUNT_PATTERN.matcher(line);
            if (!dateMatcher.find() || !amountMatcher.find()) {
                continue; // não é (ou não parece ser) uma linha de transação
            }

            LocalDate date = parseDate(dateMatcher.group(1));
            BigDecimal amount = parseAmount(amountMatcher.group(2));
            if (date == null || amount == null) {
                continue; // padrão bateu mas o conteúdo não é um valor/data válido de verdade
            }
            if ("-".equals(amountMatcher.group(1))) {
                amount = amount.negate();
            }

            transactions.add(toTransaction(line, dateMatcher, amountMatcher, date, amount));
        }
        return transactions;
    }

    private NormalizedTransactionDTO toTransaction(
            String line, Matcher dateMatcher, Matcher amountMatcher, LocalDate date, BigDecimal amount) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();

        // Padrão bateu exatamente na linha → confiança máxima na IDENTIFICAÇÃO do campo (mesma
        // régua do OFX: dado lido de um contrato reconhecível, não posição adivinhada).
        fields.put("amount", new StagedFieldValueDTO(amount.abs(), BigDecimal.ONE));
        fields.put("transaction_date", new StagedFieldValueDTO(date.toString(), BigDecimal.ONE));

        // Direção e descrição são INFERÊNCIA (posição/sinal, sem contrato formal por trás) →
        // confiança 0.7, mesma régua do CSV genérico (Fase 2, decisão d).
        fields.put("direction", new StagedFieldValueDTO(direction(line, amount), new BigDecimal("0.7")));
        fields.put("description", new StagedFieldValueDTO(description(line, dateMatcher, amountMatcher), new BigDecimal("0.7")));

        BigDecimal overallConfidence = fields.get("amount").confidence().min(fields.get("transaction_date").confidence());
        return new NormalizedTransactionDTO(null, fields, null, null, overallConfidence, null, null);
    }

    /** Palavra-chave explícita na linha vence; sem ela, cai no sinal do valor (mesma convenção do CSV). */
    private String direction(String line, BigDecimal signedAmount) {
        if (DEBIT_KEYWORD.matcher(line).find()) {
            return "debit";
        }
        if (CREDIT_KEYWORD.matcher(line).find()) {
            return "credit";
        }
        return signedAmount.signum() < 0 ? "debit" : "credit";
    }

    /** Texto restante da linha após remover a data e o valor reconhecidos, espaços colapsados. */
    private String description(String line, Matcher dateMatcher, Matcher amountMatcher) {
        List<int[]> ranges = new ArrayList<>();
        ranges.add(new int[] {dateMatcher.start(), dateMatcher.end()});
        ranges.add(new int[] {amountMatcher.start(), amountMatcher.end()});
        ranges.sort((a, b) -> b[0] - a[0]); // remove do fim pro começo, senão os índices deslocam

        StringBuilder sb = new StringBuilder(line);
        for (int[] range : ranges) {
            sb.delete(range[0], range[1]);
        }
        String result = sb.toString().trim().replaceAll("\\s+", " ");
        return result.isEmpty() ? null : result;
    }

    /**
     * Mesma ambiguidade pt-BR ({@code 1.234,56}) vs. padrão ({@code 1234.56}) do
     * {@link CsvExtractor}, resolvida por VALOR: se o valor casado tem os dois separadores, o
     * ÚLTIMO é o decimal (o outro é milhar); só vírgula → vírgula é decimal; senão assume ponto.
     */
    private BigDecimal parseAmount(String raw) {
        boolean hasComma = raw.indexOf(',') >= 0;
        boolean hasDot = raw.indexOf('.') >= 0;
        String normalized;
        if (hasComma && hasDot) {
            normalized = raw.lastIndexOf(',') > raw.lastIndexOf('.')
                    ? raw.replace(".", "").replace(',', '.')
                    : raw.replace(",", "");
        } else if (hasComma) {
            normalized = raw.replace(',', '.');
        } else {
            normalized = raw;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Aceita {@code DD/MM/YYYY}, {@code DD/MM/YY} (ano reduzido, base 2000) e {@code YYYY-MM-DD}. */
    private LocalDate parseDate(String raw) {
        for (DateTimeFormatter fmt : List.of(DateTimeFormatter.ISO_LOCAL_DATE, BR_DATE_LONG, BR_DATE_SHORT)) {
            try {
                return LocalDate.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        return null;
    }
}
