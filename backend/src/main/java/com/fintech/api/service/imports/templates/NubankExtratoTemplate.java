package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template Nubank extrato PDF — reconhece transações da seção "Movimentações" (spec:
 * registry de templates, decisão f). Datas ficam em linha própria; valor pode estar na
 * mesma linha do rótulo (entrada simples) ou sozinho na última linha de um bloco
 * multilinha (rótulo + contraparte longa). Direção vem da seção corrente ("Total de
 * entradas"/"Total de saídas"), não de palavra-chave por linha — rótulos como "Resgate RDB"
 * se repetem nos dois lados.
 */
@Component
@Order(20)
public class NubankExtratoTemplate implements PdfBankTemplate {

    private static final String CNPJ_NUBANK = "18.236.120/0001-58";
    private static final String HEADER_MOVIMENTACOES = "Movimentações";

    private static final Pattern DATE_HEADER = Pattern.compile(
            "^(\\d{2})\\s+(JAN|FEV|MAR|ABR|MAI|JUN|JUL|AGO|SET|OUT|NOV|DEZ)\\s+(\\d{4})\\b\\s*(.*)$");
    private static final Pattern TRAILING_AMOUNT =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s*$");

    private static final Map<String, Integer> MESES = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEV", 2), Map.entry("MAR", 3), Map.entry("ABR", 4),
            Map.entry("MAI", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AGO", 8),
            Map.entry("SET", 9), Map.entry("OUT", 10), Map.entry("NOV", 11), Map.entry("DEZ", 12));

    @Override
    public boolean matches(String fullText) {
        return fullText.contains(CNPJ_NUBANK) && fullText.contains(HEADER_MOVIMENTACOES);
    }

    @Override
    public String templateId() {
        return "nubank_extrato_v1";
    }

    @Override
    public List<NormalizedTransactionDTO> parse(String fullText) {
        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        LocalDate dataCorrente = null;
        String direcaoCorrente = null;
        StringBuilder acumulador = new StringBuilder();

        for (String linhaBruta : fullText.lines().toList()) {
            String linha = linhaBruta.trim();
            if (linha.isEmpty()) {
                continue;
            }

            Matcher dateMatcher = DATE_HEADER.matcher(linha);
            if (dateMatcher.matches()) {
                dataCorrente = LocalDate.of(
                        Integer.parseInt(dateMatcher.group(3)),
                        MESES.get(dateMatcher.group(2)),
                        Integer.parseInt(dateMatcher.group(1)));
                acumulador.setLength(0);
                linha = dateMatcher.group(4).trim();
                if (linha.isEmpty()) {
                    continue;
                }
            }

            if (linha.startsWith("Total de entradas")) {
                direcaoCorrente = "credit";
                acumulador.setLength(0);
                continue;
            }
            if (linha.startsWith("Total de saídas")) {
                direcaoCorrente = "debit";
                acumulador.setLength(0);
                continue;
            }

            Matcher amountMatcher = TRAILING_AMOUNT.matcher(linha);
            if (amountMatcher.find() && amountMatcher.end() == linha.length()) {
                String prefixo = linha.substring(0, amountMatcher.start()).trim();
                String descricao = (acumulador + " " + prefixo).trim().replaceAll("\\s+", " ");
                if (dataCorrente != null && direcaoCorrente != null && !descricao.isEmpty()) {
                    BigDecimal valor = parseValorBr(amountMatcher.group(1));
                    transacoes.add(toDto(dataCorrente, descricao, direcaoCorrente, valor));
                }
                acumulador.setLength(0);
            } else {
                if (!acumulador.isEmpty()) {
                    acumulador.append(' ');
                }
                acumulador.append(linha);
            }
        }
        return transacoes;
    }

    private BigDecimal parseValorBr(String raw) {
        return new BigDecimal(raw.replace(".", "").replace(',', '.'));
    }

    private NormalizedTransactionDTO toDto(LocalDate data, String descricao, String direcao, BigDecimal valor) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();
        fields.put("amount", new StagedFieldValueDTO(valor, BigDecimal.ONE));
        fields.put("transaction_date", new StagedFieldValueDTO(data.toString(), BigDecimal.ONE));
        // Direção vem da seção corrente, não de sinal ambíguo no texto — confiança máxima.
        fields.put("direction", new StagedFieldValueDTO(direcao, BigDecimal.ONE));
        fields.put("description", new StagedFieldValueDTO(descricao, new BigDecimal("0.8")));
        return new NormalizedTransactionDTO(null, fields, null, null, BigDecimal.ONE, null, null);
    }
}
