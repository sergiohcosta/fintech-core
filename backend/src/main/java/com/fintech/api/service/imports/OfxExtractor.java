package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrator de OFX (Open Financial Exchange) — primeiro do funil do {@link ExtractionRouter}
 * (roadmap §1.2: padrão universal → genérico → IA). É o mais determinístico dos formatos:
 * identificador único por transação ({@code FITID}), campos nomeados, zero heurística de coluna.
 *
 * <p><b>Por que um parser próprio e não uma lib OFX completa (ex.: ofx4j)?</b> OFX 1.x é SGML
 * com tags NÃO fechadas ({@code <DTPOSTED>20260701} sem {@code </DTPOSTED>}) — um parser XML
 * engasga nele. Uma lib de banking completa traria um cliente inteiro (download de extrato,
 * OFXDirectConnect...) pra usar 5% da API. O truque que faz um leitor simples cobrir SGML 1.x
 * E XML 2.x com a MESMA regex: valor de uma tag sempre termina no próximo {@code <} (fechamento
 * em XML) OU em quebra de linha (SGML sem fechamento) — {@code [^<\r\n]+} cobre os dois.
 *
 * <p>Se aparecer variação estrutural real entre bancos que este leitor não cubra, a decisão é
 * reavaliar uma lib (ex.: ofx4j) — não empilhar remendos aqui (ver plano da Fase 2, riscos).
 */
@Component
@Order(10)
@Slf4j
public class OfxExtractor implements TransactionExtractor {

    private static final String EXTRACTOR_USED = "ofx_parser_v1";

    // Cada bloco <STMTTRN>...</STMTTRN> (ou até o próximo <STMTTRN>/fim de lista em SGML sem
    // fechamento) é UMA transação. DOTALL para o conteúdo cruzar múltiplas linhas.
    private static final Pattern STMTTRN_BLOCK = Pattern.compile("(?is)<STMTTRN>(.*?)</STMTTRN>");

    // yyyyMMdd — DTPOSTED pode vir como YYYYMMDD ou YYYYMMDDHHMMSS[.SSS][TZ]; só os 8 primeiros
    // dígitos (a DATA) importam para o campo transaction_date.
    private static final DateTimeFormatter OFX_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final String extractorVersion;

    public OfxExtractor(@Value("${import.ofx.extractor-version:v1}") String extractorVersion) {
        this.extractorVersion = extractorVersion;
    }

    @Override
    public boolean supports(ExtractionInput input) {
        byte[] content = input.content();
        if (content == null || content.length == 0) {
            return false;
        }
        // Primeiros bytes bastam — cabeçalho OFX (1.x SGML ou 2.x XML) sempre aparece logo no
        // início do arquivo. ISO-8859-1 nunca lança em byte nenhum (1 byte = 1 char), seguro
        // para um sniff que só procura tokens ASCII.
        int len = Math.min(content.length, 300);
        String head = new String(content, 0, len, StandardCharsets.ISO_8859_1).toUpperCase();
        return head.contains("OFXHEADER:") || head.contains("<OFX>") || head.contains("<OFX ");
    }

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.OFX;
    }

    @Override
    public String extractorVersion() {
        return extractorVersion;
    }

    @Override
    public NormalizedBatchDTO extract(ExtractionInput input) {
        String content = new String(input.content(), StandardCharsets.UTF_8);

        Matcher blockMatcher = STMTTRN_BLOCK.matcher(content);
        List<String> blocks = new ArrayList<>();
        while (blockMatcher.find()) {
            blocks.add(blockMatcher.group(1));
        }
        if (blocks.isEmpty()) {
            throw new ExtractionException(
                    "Arquivo OFX não contém nenhuma transação (nenhum <STMTTRN> encontrado).");
        }

        // CURDEF é do ENVELOPE (uma moeda para o extrato inteiro), não por transação.
        String currency = tagValue(content, "CURDEF");

        List<NormalizedTransactionDTO> transactions = blocks.stream()
                .map(block -> toTransaction(block, currency))
                .toList();

        return new NormalizedBatchDTO(input.mode(), ImportSourceType.OFX, EXTRACTOR_USED, extractorVersion, transactions);
    }

    private NormalizedTransactionDTO toTransaction(String block, String currency) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();

        BigDecimal trnamt = parseAmount(tagValue(block, "TRNAMT"));
        // Confiança 1.0: o sinal e o valor vêm do próprio arquivo do banco, não de inferência
        // (diferente da Fase 1, onde um modelo de visão "lê" o número de uma imagem).
        if (trnamt != null) {
            fields.put("amount", new StagedFieldValueDTO(trnamt.abs(), BigDecimal.ONE));
            fields.put("direction", new StagedFieldValueDTO(trnamt.signum() < 0 ? "debit" : "credit", BigDecimal.ONE));
        } else {
            // TRNAMT ausente/ilegível: não derruba a transação inteira (guarda-corpo central de
            // sanidade é responsabilidade do ImportService — Onda 4); zera confiança pra exigir revisão.
            fields.put("amount", new StagedFieldValueDTO(null, BigDecimal.ZERO));
            fields.put("direction", new StagedFieldValueDTO(null, BigDecimal.ZERO));
        }

        LocalDate date = parseOfxDate(tagValue(block, "DTPOSTED"));
        fields.put("transaction_date",
                new StagedFieldValueDTO(date == null ? null : date.toString(), date == null ? BigDecimal.ZERO : BigDecimal.ONE));

        // MEMO é a descrição livre do banco; NAME é o nome do beneficiário/estabelecimento —
        // usa MEMO quando presente (mais descritivo), cai para NAME na ausência.
        String memo = tagValue(block, "MEMO");
        String name = tagValue(block, "NAME");
        String description = memo != null ? memo : name;
        fields.put("description", new StagedFieldValueDTO(description, description == null ? BigDecimal.ZERO : BigDecimal.ONE));

        if (currency != null) {
            fields.put("currency", new StagedFieldValueDTO(currency, BigDecimal.ONE));
        }
        String fitid = tagValue(block, "FITID");
        if (fitid != null) {
            // external_id: autoridade de dedup intra-batch (Onda 4) — FITID é único por transação
            // segundo o padrão OFX, ao contrário do trio (data, valor, descrição) do CSV genérico.
            fields.put("external_id", new StagedFieldValueDTO(fitid, BigDecimal.ONE));
        }

        // overall = o MENOR entre os campos críticos — mesma régua da Fase 1 (o mais fraco decide).
        BigDecimal amountConfidence = fields.get("amount").confidence();
        BigDecimal dateConfidence = fields.get("transaction_date").confidence();
        BigDecimal overallConfidence = amountConfidence.min(dateConfidence);

        return new NormalizedTransactionDTO(null, fields, null, null, overallConfidence, null, null);
    }

    /** {@code TRNAMT} usa ponto decimal (padrão OFX) — não há ambiguidade pt-BR aqui. */
    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code DTPOSTED} aceita {@code YYYYMMDD} ou {@code YYYYMMDDHHMMSS[.SSS][TZ]} — só os 8
     * primeiros dígitos (a data) importam para {@code transaction_date}; hora/timezone são
     * descartados de propósito (o contrato normalizado não tem campo de hora).
     */
    private LocalDate parseOfxDate(String raw) {
        if (raw == null || raw.trim().length() < 8) {
            return null;
        }
        String datePart = raw.trim().substring(0, 8);
        try {
            return LocalDate.parse(datePart, OFX_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Valor de {@code <TAG>} — termina no próximo {@code <} (fecha em XML: {@code </TAG>}) OU em
     * quebra de linha (SGML sem fechamento). A MESMA regex cobre OFX 1.x e 2.x sem precisar
     * detectar o dialeto antes de ler.
     */
    private String tagValue(String content, String tag) {
        Matcher m = Pattern.compile("(?is)<" + tag + ">\\s*([^<\r\n]+)").matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }
}
