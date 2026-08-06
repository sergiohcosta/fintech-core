package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.service.imports.ExtractionException;
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
 * Template Itaú fatura de cartão — reconhece transações da seção "Lançamentos: compras e
 * saques" (spec: registry de templates, decisões c/e). Datas de lançamento vêm sem ano
 * (DD/MM); o ano é inferido da data de vencimento, que aparece uma vez no documento com ano
 * completo.
 */
@Component
@Order(10)
public class ItauFaturaTemplate implements PdfBankTemplate {

    private static final String CNPJ_ITAU = "60.872.504/0001-23";
    private static final String HEADER_LANCAMENTOS = "Lançamentos: compras e saques";

    // Cabeçalhos de seção que fecham o bloco de lançamentos do ciclo corrente — em especial
    // "Compras parceladas - próximas faturas", que repete o MESMO formato de linha (data +
    // estabelecimento + valor) para parcelas de meses seguintes da mesma compra.
    private static final List<String> STOP_MARKERS = List.of(
            "Compras parceladas - próximas faturas",
            "Limites de crédito",
            "Encargos cobrados",
            "Lançamentos internacionais",
            "Lançamentos: produtos e serviços");

    private static final Pattern DUE_DATE =
            Pattern.compile("Vencimento\\D{0,20}(\\d{2})/(\\d{2})/(\\d{4})");
    private static final Pattern LINE_START_DATE = Pattern.compile("^(\\d{2})/(\\d{2})\\s+(.*)$");
    private static final Pattern TRAILING_AMOUNT =
            Pattern.compile("(-?)\\s*(\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s*$");
    private static final Pattern TRAILING_INSTALLMENT_MARKER = Pattern.compile("\\d{2}/\\d{2}\\s*$");

    @Override
    public boolean matches(String fullText) {
        return fullText.contains(CNPJ_ITAU) && fullText.contains(HEADER_LANCAMENTOS);
    }

    @Override
    public String templateId() {
        return "itau_fatura_v1";
    }

    @Override
    public List<NormalizedTransactionDTO> parse(String fullText) {
        Matcher dueDateMatcher = DUE_DATE.matcher(fullText);
        if (!dueDateMatcher.find()) {
            throw new ExtractionException(
                    "Não foi possível localizar a data de vencimento na fatura Itaú.");
        }
        int mesVencimento = Integer.parseInt(dueDateMatcher.group(2));
        int anoVencimento = Integer.parseInt(dueDateMatcher.group(3));

        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        // O cabeçalho pode repetir — um bloco por titular adicional no mesmo cartão. Cada
        // ocorrência delimita seu PRÓPRIO bloco até o marcador de parada mais próximo (ou fim
        // do texto, na última) — nunca até o marcador global, senão blocos posteriores ao
        // primeiro seriam perdidos silenciosamente.
        int headerIdx = fullText.indexOf(HEADER_LANCAMENTOS);
        while (headerIdx >= 0) {
            int start = headerIdx + HEADER_LANCAMENTOS.length();
            int stop = fullText.length();
            for (String marker : STOP_MARKERS) {
                int idx = fullText.indexOf(marker, start);
                if (idx >= 0 && idx < stop) {
                    stop = idx;
                }
            }
            // Também capa no próximo header (se houver) — senão o bloco do titular corrente
            // engoliria o header e as linhas do próximo titular, contando-as duas vezes.
            int nextHeaderIdx = fullText.indexOf(HEADER_LANCAMENTOS, start);
            if (nextHeaderIdx >= 0 && nextHeaderIdx < stop) {
                stop = nextHeaderIdx;
            }
            String bloco = fullText.substring(start, stop);
            for (String linha : bloco.lines().toList()) {
                TransacaoItau transacao = parseLinha(linha.trim(), mesVencimento, anoVencimento);
                if (transacao != null) {
                    transacoes.add(toDto(transacao));
                }
            }
            headerIdx = fullText.indexOf(HEADER_LANCAMENTOS, start);
        }
        return transacoes;
    }

    /** {@code null} quando a linha não bate o formato "DD/MM estabelecimento [NN/NN] valor". */
    private TransacaoItau parseLinha(String linha, int mesVencimento, int anoVencimento) {
        Matcher dateMatcher = LINE_START_DATE.matcher(linha);
        if (!dateMatcher.matches()) {
            return null;
        }
        int dia = Integer.parseInt(dateMatcher.group(1));
        int mes = Integer.parseInt(dateMatcher.group(2));
        String resto = dateMatcher.group(3);

        Matcher amountMatcher = TRAILING_AMOUNT.matcher(resto);
        if (!amountMatcher.find()) {
            return null;
        }
        BigDecimal valor = parseValorBr(amountMatcher.group(2));
        if ("-".equals(amountMatcher.group(1))) {
            valor = valor.negate();
        }

        // Marcador de parcela (ex. "04/06") pode vir colado ao nome do estabelecimento, sem
        // espaço ("Foco Aluguel de Ca04/06") — removido da descrição, não é uma segunda data.
        String antesDoValor = resto.substring(0, amountMatcher.start()).trim();
        String descricao = TRAILING_INSTALLMENT_MARKER.matcher(antesDoValor).replaceFirst("").trim();
        if (descricao.isEmpty()) {
            return null;
        }

        // Fatura fecha ~1 mês antes do vencimento: lançamento com mês MAIOR que o mês de
        // vencimento pertence ao ano anterior (ex.: vencimento 10/03/2025, lançamento 28/11
        // é 28/11/2024).
        int ano = mes > mesVencimento ? anoVencimento - 1 : anoVencimento;
        LocalDate data = LocalDate.of(ano, mes, dia);
        return new TransacaoItau(data, descricao, valor);
    }

    private BigDecimal parseValorBr(String raw) {
        return new BigDecimal(raw.replace(".", "").replace(',', '.'));
    }

    private NormalizedTransactionDTO toDto(TransacaoItau t) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();
        fields.put("amount", new StagedFieldValueDTO(t.valor().abs(), BigDecimal.ONE));
        fields.put("transaction_date", new StagedFieldValueDTO(t.data().toString(), BigDecimal.ONE));
        // Sinal do valor decide direção: negativo = estorno/abatimento (credit), positivo =
        // compra normal (debit) — confiança 1.0 porque o sinal veio do padrão do template, não
        // de inferência posicional (diferente da heurística genérica, confiança 0.7).
        fields.put("direction",
                new StagedFieldValueDTO(t.valor().signum() < 0 ? "credit" : "debit", BigDecimal.ONE));
        fields.put("description", new StagedFieldValueDTO(t.descricao(), new BigDecimal("0.9")));
        BigDecimal overallConfidence = fields.get("amount").confidence().min(fields.get("transaction_date").confidence());
        return new NormalizedTransactionDTO(null, fields, null, null, overallConfidence, null, null);
    }

    private record TransacaoItau(LocalDate data, String descricao, BigDecimal valor) {}
}
