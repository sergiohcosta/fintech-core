package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.service.imports.templates.PdfBankTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
 * <p><b>PDF escaneado (sem camada de texto) falha EXPLICITAMENTE aqui</b>, em vez de tentar
 * visão/OCR: o {@link VisionExtractor} hoje só processa bytes de imagem (prompt de comprovante),
 * não sabe renderizar página de PDF — rotear pra ele seria pior que a falha explícita. Suporte a
 * PDF escaneado é fatia futura da Fase 3 (mesmo espírito da decisão (f) da Fase 2: "formato não
 * reconhecido falha, ainda não cai na IA" — aqui o formato *é* reconhecido, só o conteúdo ainda
 * não tem extrator).
 *
 * <p>{@code supports()} reconhece QUALQUER PDF pelo magic number, escaneado ou não — a distinção
 * "tem texto ou não" só é possível depois que o PDFBox abre o documento, dentro de
 * {@link #extract}. Se {@code supports()} fosse restrito a "tem texto extraível", um PDF
 * escaneado cairia no {@link VisionExtractor} (que não sabe processá-lo hoje) em vez de receber a
 * mensagem explicativa certa.
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

    public PdfTextExtractor(
            @Value("${import.pdf-text.extractor-version:v1}") String extractorVersion,
            List<PdfBankTemplate> templates) {
        this.extractorVersion = extractorVersion;
        this.templates = templates;
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
        String text = extractText(input.content());

        long meaningfulChars = text.chars().filter(c -> !Character.isWhitespace(c)).count();
        if (meaningfulChars < MIN_TEXT_CHARS) {
            throw new ExtractionException(
                    "Este PDF parece ser uma imagem digitalizada (sem texto extraível). Suporte a "
                            + "PDF escaneado ainda não está disponível — use o formulário manual ou "
                            + "envie como imagem.");
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
    }

    /** Extrai o texto bruto do documento inteiro. Falha do PDFBox (corrompido/senha) vira {@link ExtractionException}. */
    private String extractText(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException e) {
            // Nenhuma exceção de infra (mensagem/stacktrace do PDFBox) cruza a borda da API —
            // mesma regra do restante do pipeline (ImportService.failureReasonFor).
            throw new ExtractionException("Não foi possível abrir este PDF — arquivo corrompido ou protegido por senha.", e);
        }
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
