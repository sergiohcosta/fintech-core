package com.fintech.api.service.imports.templates;

import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import com.fintech.api.service.imports.ExtractionException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template Itaú fatura de cartão — reconhece transações da seção "Lançamentos: compras e
 * saques" (spec: registry de templates, decisões c/e). Datas de lançamento vêm sem ano
 * (DD/MM); o ano é inferido da data de vencimento, que aparece uma vez no documento com ano
 * completo. Colunas de lançamento são separadas por detecção dinâmica de vão horizontal,
 * por página (spec: fix-itau-split-coluna-dinamico) — não há constante fixa de corte.
 */
@Slf4j
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
    private static final Pattern TRAILING_INSTALLMENT_MARKER = Pattern.compile("(\\d{2})/(\\d{2})\\s*$");

    // Vão mínimo (pt) pra considerar uma quebra de coluna real, não espaço normal entre
    // palavras/blocos de texto — folga generosa: o vão real medido no documento que
    // motivou este fix era ~77pt; espaçamento intra-linha em fonte 10pt não passa de
    // poucos pontos. Abaixo disso, a página é tratada como coluna única (spec §4.1).
    private static final float MIN_GAP_WIDTH = 20f;

    // Valor histórico do antigo COLUMN_SPLIT_X (corte fixo, calibrado manualmente contra uma
    // fatura Itaú real antes deste fix). Usado APENAS como último recurso, quando a detecção
    // dinâmica da página não encontra nenhum vão confiável — nunca como mecanismo primário
    // (isso reintroduziria o bug original: 365f sempre, mesmo quando o layout real diverge).
    // Ver final-review.md (C1): devolver pageWidth nesse caminho funde as duas colunas no
    // mesmo stream (dado corrompido); devolver este default degrada, na pior hipótese, para
    // o comportamento antigo conhecido (perde a coluna direita) — pior que sem dado, nunca
    // pior que dado errado.
    private static final float FALLBACK_SPLIT_X = 365f;

    @Override
    public boolean matches(String fullText) {
        return fullText.contains(CNPJ_ITAU) && fullText.contains(HEADER_LANCAMENTOS);
    }

    @Override
    public String templateId() {
        return "itau_fatura_v1";
    }

    @Override
    public List<NormalizedTransactionDTO> parse(String fullText, byte[] content) {
        Matcher dueDateMatcher = DUE_DATE.matcher(fullText);
        if (!dueDateMatcher.find()) {
            throw new ExtractionException(
                    "Não foi possível localizar a data de vencimento na fatura Itaú.");
        }
        int mesVencimento = Integer.parseInt(dueDateMatcher.group(2));
        int anoVencimento = Integer.parseInt(dueDateMatcher.group(3));

        StringBuilder colunaEsquerda = new StringBuilder();
        StringBuilder colunaDireita = new StringBuilder();
        // A fatura renderiza duas colunas de lançamentos lado a lado — o PDFBox funde texto
        // da mesma altura Y numa única linha (mesmo com sortByPosition), misturando data e
        // valor de transações DIFERENTES. Reabrir o documento e extrair por REGIÃO
        // RETANGULAR (posição X) separa as colunas antes de qualquer parsing de linha.
        try (PDDocument document = Loader.loadPDF(content)) {
            int pageNumber = 0;
            for (PDPage page : document.getPages()) {
                pageNumber++;
                float split = detectColumnSplit(document, page, pageNumber);
                PDRectangle box = page.getMediaBox();
                // Instância nova por página — não reusar uma stripper com addRegion fora
                // do loop de páginas (histórico deste template, PR #213: estado vazando entre
                // páginas já causou bug de duplicação aqui antes).
                PDFTextStripperByArea stripper = new PDFTextStripperByArea();
                stripper.setSortByPosition(true);
                stripper.addRegion("esquerda", new Rectangle2D.Float(0, 0, split, box.getHeight()));
                stripper.addRegion("direita",
                        new Rectangle2D.Float(split, 0, box.getWidth() - split, box.getHeight()));
                stripper.extractRegions(page);
                colunaEsquerda.append(stripper.getTextForRegion("esquerda")).append('\n');
                colunaDireita.append(stripper.getTextForRegion("direita")).append('\n');
            }
        } catch (IOException e) {
            throw new ExtractionException(
                    "Não foi possível reabrir o PDF da fatura Itaú para separar as colunas.", e);
        }

        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        transacoes.addAll(extrairTransacoesDoStream(colunaEsquerda.toString(), mesVencimento, anoVencimento));
        transacoes.addAll(extrairTransacoesDoStream(colunaDireita.toString(), mesVencimento, anoVencimento));
        return transacoes;
    }

    /**
     * Acha o corte de coluna da página pelo maior vão horizontal entre trechos de texto
     * renderizados — substitui a constante fixa antiga como MECANISMO PRIMÁRIO (spec:
     * fix-itau-split-coluna-dinamico). Sem vão significativo (≥ {@link #MIN_GAP_WIDTH}) na
     * página inteira, devolve {@link #FALLBACK_SPLIT_X} (não a largura da página inteira):
     * uma única palavra fora das colunas de lançamento (rodapé, endereço, numeração de
     * página) cai na faixa X da calha com frequência em páginas reais — devolver
     * {@code pageWidth} nesse caso funde as duas colunas no mesmo stream e produz
     * transações com data de uma coluna e valor da outra, em silêncio (C1 do review final).
     * O corte fixo histórico é um default seguro, não a ausência de corte.
     */
    private float detectColumnSplit(PDDocument document, PDPage page, int pageNumberOneBased)
            throws IOException {
        List<float[]> extents = new ArrayList<>();
        PDFTextStripper detector = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                float minX = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE;
                for (TextPosition tp : textPositions) {
                    minX = Math.min(minX, tp.getX());
                    maxX = Math.max(maxX, tp.getX() + tp.getWidth());
                }
                if (minX <= maxX) {
                    extents.add(new float[] {minX, maxX});
                }
            }
        };
        detector.setSortByPosition(true);
        detector.setStartPage(pageNumberOneBased);
        detector.setEndPage(pageNumberOneBased);
        detector.getText(document);

        float pageWidth = page.getMediaBox().getWidth();
        if (extents.isEmpty()) {
            return pageWidth;
        }
        extents.sort(Comparator.comparingDouble(e -> e[0]));

        float cursor = extents.get(0)[1];  // Inicializa com o fim do primeiro extent, não 0 (evita margem esquerda)
        float bestGapStart = -1f;
        float bestGapSize = 0f;
        for (int i = 1; i < extents.size(); i++) {
            float[] extent = extents.get(i);
            if (extent[0] > cursor) {  // Só há vão se o início deste extent for APÓS o alcance máximo visto até agora
                float gap = extent[0] - cursor;
                if (gap > bestGapSize) {
                    bestGapSize = gap;
                    bestGapStart = cursor;
                }
            }
            cursor = Math.max(cursor, extent[1]);  // Atualiza cursor para o MAIOR fim visto (running-max)
        }

        if (bestGapSize < MIN_GAP_WIDTH) {
            // Nenhum vão confiável nesta página — quase sempre porque uma única palavra fora
            // das colunas de lançamento (rodapé/endereço/numeração) caiu na faixa X da calha
            // e "fechou" o vão real (C1 do review final). Cair para pageWidth aqui fundiria
            // as duas colunas; o corte histórico calibrado é o default seguro.
            log.warn("ItauFaturaTemplate: detecção dinâmica de coluna não achou vão >= {}pt na "
                    + "página {} — usando corte histórico ({}pt) como fallback.",
                    MIN_GAP_WIDTH, pageNumberOneBased, FALLBACK_SPLIT_X);
            return FALLBACK_SPLIT_X;
        }
        return bestGapStart + bestGapSize / 2f;
    }

    /**
     * Localiza TODOS os blocos "Lançamentos: compras e saques" dentro de UM stream já
     * separado por coluna (esquerda ou direita) e reconhece as transações de cada um — a
     * mesma lógica de delimitação de seção de antes, agora rodando sobre texto limpo (sem
     * fusão de coluna), o que a torna correta: cada coluna tem seus próprios cabeçalhos e
     * marcadores de parada na ordem certa.
     */
    private List<NormalizedTransactionDTO> extrairTransacoesDoStream(
            String stream, int mesVencimento, int anoVencimento) {
        List<NormalizedTransactionDTO> transacoes = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int headerIdx = stream.indexOf(HEADER_LANCAMENTOS, cursor);
            if (headerIdx < 0) {
                break;
            }
            int start = headerIdx + HEADER_LANCAMENTOS.length();
            int stop = stream.length();
            for (String marker : STOP_MARKERS) {
                int idx = stream.indexOf(marker, start);
                if (idx >= 0 && idx < stop) {
                    stop = idx;
                }
            }
            // Header re-impresso por continuação de página não deve abrir um bloco novo —
            // é o MESMO bloco lógico continuando. Avançar o cursor pro fim do bloco atual
            // (não pro início do header) é matematicamente equivalente ao capping anterior
            // para este algoritmo (verificado), mas é a leitura mais fiel do layout
            // paginado real e evita reabrir blocos sobre header repetido por continuação.
            String bloco = stream.substring(start, stop);
            for (String linha : bloco.lines().toList()) {
                TransacaoItau transacao = parseLinha(linha.trim(), mesVencimento, anoVencimento);
                if (transacao != null) {
                    transacoes.add(toDto(transacao));
                }
            }
            // Avança o cursor pro FIM deste bloco (não pro início do header atual) — qualquer
            // header re-impresso DENTRO deste intervalo já foi incluído (como texto inerte) e
            // não deve reabrir um bloco novo sobreposto.
            cursor = stop;
        }
        // Observabilidade: coluna com conteúdo que PARECE transação (linha "DD/MM ...") mas
        // zero transações reconhecidas é sinal de header ausente/quebrado nesta coluna — sem
        // isso o resultado só fica menor que o esperado, em silêncio (exatamente a classe de
        // erro "plausível mas errado" que este fix existe pra evitar).
        if (transacoes.isEmpty() && !stream.isBlank()
                && stream.lines().anyMatch(linha -> LINE_START_DATE.matcher(linha.trim()).matches())) {
            log.warn("ItauFaturaTemplate: coluna com linhas no formato de transação mas nenhuma "
                    + "transação reconhecida — possível header \"{}\" ausente ou quebrado nesta coluna.",
                    HEADER_LANCAMENTOS);
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
        // Capturado ANTES de remover — usado pelo ImportService.commit() pra reconhecer a
        // parcela 1 e criar o parcelamento completo (spec: import-itau-parcelamento).
        String antesDoValor = resto.substring(0, amountMatcher.start()).trim();
        Matcher installmentMatcher = TRAILING_INSTALLMENT_MARKER.matcher(antesDoValor);
        Integer installmentNumber = null;
        Integer installmentTotal = null;
        String descricao;
        if (installmentMatcher.find()) {
            installmentNumber = Integer.parseInt(installmentMatcher.group(1));
            installmentTotal = Integer.parseInt(installmentMatcher.group(2));
            descricao = antesDoValor.substring(0, installmentMatcher.start()).trim();
        } else {
            descricao = antesDoValor;
        }
        if (descricao.isEmpty()) {
            return null;
        }

        // Fatura fecha ~1 mês antes do vencimento: lançamento com mês MAIOR que o mês de
        // vencimento pertence ao ano anterior (ex.: vencimento 10/03/2025, lançamento 28/11
        // é 28/11/2024).
        int ano = mes > mesVencimento ? anoVencimento - 1 : anoVencimento;
        LocalDate data = LocalDate.of(ano, mes, dia);
        return new TransacaoItau(data, descricao, valor, installmentNumber, installmentTotal);
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
        // Metadado de parcela — só presente quando a linha trazia o marcador "NN/MM". Confiança
        // 1.0: veio de um padrão regex casado, não de inferência. O ImportService.commit() usa
        // isso pra decidir se a parcela 1 vira um InstallmentGroup completo.
        if (t.installmentNumber() != null) {
            fields.put("installment_number", new StagedFieldValueDTO(t.installmentNumber(), BigDecimal.ONE));
            fields.put("installment_total", new StagedFieldValueDTO(t.installmentTotal(), BigDecimal.ONE));
        }
        BigDecimal overallConfidence = fields.get("amount").confidence().min(fields.get("transaction_date").confidence());
        return new NormalizedTransactionDTO(null, fields, null, null, overallConfidence, null, null);
    }

    private record TransacaoItau(
            LocalDate data, String descricao, BigDecimal valor,
            Integer installmentNumber, Integer installmentTotal) {}
}
