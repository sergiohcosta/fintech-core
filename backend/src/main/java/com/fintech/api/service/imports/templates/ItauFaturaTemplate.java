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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template Itaú fatura de cartão — reconhece transações da seção "Lançamentos: compras e
 * saques" (spec: registry de templates, decisões c/e). Datas de lançamento vêm sem ano
 * (DD/MM); o ano é inferido da data de vencimento, que aparece uma vez no documento com ano
 * completo. Colunas de lançamento são separadas por página, ancorando na posição X dos tokens
 * de data que iniciam cada lançamento (spec: itau-ancora-coluna-por-data) — não há nenhuma
 * coordenada de corte assada no código.
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
    // Token que INICIA com data. Precisa aceitar as duas granularidades com que o PDFBox
    // entrega texto: nas faturas reais cada palavra vira um token ("28/11" sozinho), mas em
    // PDF gerado com uma única operação de escrita a linha inteira vem num token só
    // ("28/11 Estabelecimento 112,67"). Casar só o token exato funcionaria no primeiro caso e
    // falharia silenciosamente no segundo.
    private static final Pattern DATE_TOKEN_PREFIX = Pattern.compile("^\\d{2}/\\d{2}(\\s|$)");

    // Um token "DD/MM" só conta como início de linha de lançamento se tiver este tanto de
    // espaço vazio à esquerda. Sem isso, o marcador de parcela ("04/06", mesmo formato) viraria
    // âncora: no levantamento de 45 faturas reais, 87% do ruído de cluster vinha daí.
    private static final float MIN_BLOCK_GAP = 15f;

    // Tolerância para dois tokens de data pertencerem à mesma coluna. As colunas reais medidas
    // têm variância interna ~0,01pt e ficam a ~216pt uma da outra — 5pt separa com folga larga.
    private static final float CLUSTER_TOLERANCE = 5f;

    // Massa mínima do 2º cluster para a página ser considerada de duas colunas. Medido: limiar
    // 1, 2, 3 e 5 produzem resultado IDÊNTICO nas 45 faturas reais — coluna de verdade tem massa
    // 15–36, nunca fica perto do limiar. Fica no valor mais BAIXO porque é o único que cobre a
    // coluna direita esparsa (última página com 1–2 lançamentos); o risco oposto — uma data
    // solta virando "coluna" e deslocando o corte — é barrado por splitAtravessaLancamento(),
    // não por este limiar. Que o guard é mesmo quem sustenta isso está provado por teste:
    // parseNaoPerdeColunaUnicaQuandoHaDataSoltaADireita devolve 0 transações (página inteira
    // perdida, em silêncio) quando o guard é desativado, e 3 com ele ativo.
    private static final int MIN_CLUSTER_MASS = 1;

    // Recuo do corte em relação ao início da coluna direita. A folga real entre o fim do
    // conteúdo da coluna esquerda e o início da direita foi de 20,9pt a 29,2pt em 121 páginas
    // medidas — 10pt fica dentro dessa folga com margem, em todos os casos observados.
    private static final float SPLIT_MARGIN = 10f;

    // Distância mínima entre os dois clusters para serem colunas de verdade, e não uma coluna
    // real mais um punhado de datas dispersas. Medido: as duas colunas ficam a 215,6–218,3pt
    // uma da outra em todas as 121 páginas do levantamento. O limiar é menos da metade disso.
    // NÃO garante, sozinho, que o corte não atravesse conteúdo — quem garante isso é
    // splitAtravessaLancamento(), verificando o texto de verdade da página.
    private static final float MIN_COLUMN_SEPARATION = 100f;

    // Menos âncoras que isto na página inteira: não dá nem pra formar dois clusters, então não
    // há duas colunas a separar (capa, resumo, página de encargos).
    private static final int MIN_ANCHORS = 2 * MIN_CLUSTER_MASS;

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
     * Acha o corte entre as duas colunas de lançamento da página pela posição X dos tokens de
     * DATA que iniciam uma linha de lançamento.
     *
     * <p>Por que a data e não o "maior vão de texto" (que era a estratégia anterior): o vão
     * some assim que QUALQUER texto cai perto da calha — rodapé, endereço, rótulo de subtotal —
     * e a página inteira degrada em silêncio. Já o token de data só existe onde há lançamento,
     * então é imune a esse ruído. Medição sobre 45 faturas reais (2022–2026): as datas formam
     * dois clusters com variância interna ~0,01pt, nas posições exatas das duas colunas, e
     * ZERO clusters espúrios em 141 páginas. Racional completo: spec
     * itau-ancora-coluna-por-data §1.3.
     *
     * <p>Sem duas colunas detectáveis, devolve a largura da página: tudo cai numa região só e é
     * processado normalmente (correto em página de coluna única — capa, resumo). Note que NÃO
     * há mais fallback para coordenada fixa: a medição mostrou que o antigo corte fixo cai dentro da
     * faixa onde a coluna direita começa (351–367pt), ou seja, corta conteúdo real.
     */
    private float detectColumnSplit(PDDocument document, PDPage page, int pageNumberOneBased)
            throws IOException {
        float pageWidth = page.getMediaBox().getWidth();
        Map<Integer, List<Token>> tokensByRow = collectTokensByRow(document, pageNumberOneBased);
        List<Float> anchors = dateAnchors(tokensByRow);
        if (anchors.size() < MIN_ANCHORS) {
            return pageWidth;
        }

        List<float[]> clusters = clusterByProximity(anchors);
        // Por MASSA, não por posição: há ruído legítimo à DIREITA da coluna direita (datas na
        // seção de limites de crédito), que venceria um critério de "cluster mais à direita".
        clusters.sort((a, b) -> Float.compare(b[1], a[1]));
        if (clusters.size() < 2 || clusters.get(1)[1] < MIN_CLUSTER_MASS) {
            return pageWidth;
        }

        float rightColumnX = Math.max(clusters.get(0)[0], clusters.get(1)[0]);
        float leftColumnX = Math.min(clusters.get(0)[0], clusters.get(1)[0]);
        if (rightColumnX - leftColumnX < MIN_COLUMN_SEPARATION) {
            return pageWidth;
        }

        float candidate = rightColumnX - SPLIT_MARGIN;
        if (splitAtravessaLancamento(tokensByRow, candidate)) {
            // Rede de segurança final, verificada contra o texto REAL da página: se o corte
            // partiria alguma linha de lançamento ao meio, não cortar é estritamente melhor.
            // Sem corte, o pior caso é uma página de coluna dupla vir fundida (defeito
            // conhecido, visível); com corte errado, cada linha vira metade-data e
            // metade-valor e a página inteira desaparece da extração, em silêncio.
            log.warn("ItauFaturaTemplate: corte candidato ({}pt) atravessaria uma linha de "
                    + "lançamento na página {} — página tratada como coluna única.",
                    candidate, pageNumberOneBased);
            return pageWidth;
        }
        return candidate;
    }

    /**
     * {@code true} se o corte cairia DENTRO do conteúdo de alguma linha de lançamento — isto é,
     * se alguma linha que começa com data à esquerda do corte se estende para além dele.
     *
     * <p>Só linhas de lançamento contam: rodapé, endereço e cabeçalho atravessam a calha com
     * frequência em página real e não são conteúdo que se possa partir. Nas 121 páginas
     * medidas, a linha da coluna esquerda sempre termina 20,9pt ou mais antes do início da
     * coluna direita, então este guard nunca dispara no layout conhecido — ele existe para o
     * layout que ainda não vimos.
     */
    private boolean splitAtravessaLancamento(Map<Integer, List<Token>> tokensByRow, float split) {
        for (List<Token> row : tokensByRow.values()) {
            boolean lancamentoAEsquerda = row.stream()
                    .anyMatch(t -> t.xStart() < split && DATE_TOKEN_PREFIX.matcher(t.text()).find());
            if (!lancamentoAEsquerda) {
                continue;
            }
            for (Token t : row) {
                if (t.xStart() < split && t.xEnd() > split) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Um pedaço de texto posicionado, como o PDFBox o entrega. */
    private record Token(String text, float xStart, float xEnd) {}

    /** Tokens da página agrupados por linha visual (Y arredondado), cada linha ordenada por X. */
    private Map<Integer, List<Token>> collectTokensByRow(PDDocument document, int pageNumberOneBased)
            throws IOException {
        Map<Integer, List<Token>> tokensByRow = new TreeMap<>();
        PDFTextStripper collector = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                if (textPositions.isEmpty()) {
                    return;
                }
                float minX = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE;
                for (TextPosition tp : textPositions) {
                    minX = Math.min(minX, tp.getX());
                    maxX = Math.max(maxX, tp.getX() + tp.getWidth());
                }
                int row = Math.round(textPositions.get(0).getY());
                tokensByRow.computeIfAbsent(row, k -> new ArrayList<>())
                        .add(new Token(text.trim(), minX, maxX));
            }
        };
        collector.setSortByPosition(true);
        collector.setStartPage(pageNumberOneBased);
        collector.setEndPage(pageNumberOneBased);
        collector.getText(document);
        tokensByRow.values().forEach(row -> row.sort(Comparator.comparingDouble(Token::xStart)));
        return tokensByRow;
    }

    /**
     * Posições X dos tokens {@code DD/MM} que INICIAM uma linha de lançamento — primeiro token
     * da linha, ou precedido por um espaço vazio de pelo menos {@link #MIN_BLOCK_GAP}. O filtro
     * descarta o marcador de parcela, que tem o mesmo formato mas aparece colado ao meio da
     * descrição.
     */
    private List<Float> dateAnchors(Map<Integer, List<Token>> tokensByRow) {
        List<Float> anchors = new ArrayList<>();
        for (List<Token> row : tokensByRow.values()) {
            for (int i = 0; i < row.size(); i++) {
                Token token = row.get(i);
                if (!DATE_TOKEN_PREFIX.matcher(token.text()).find()) {
                    continue;
                }
                boolean startsBlock =
                        i == 0 || token.xStart() - row.get(i - 1).xEnd() > MIN_BLOCK_GAP;
                if (startsBlock) {
                    anchors.add(token.xStart());
                }
            }
        }
        Collections.sort(anchors);
        return anchors;
    }

    /**
     * Agrupa posições X próximas — cada item devolvido é {@code {centroide, massa}}. Compara
     * cada valor com o PRIMEIRO do grupo (não com o centroide corrente), então um grupo nunca
     * fica mais largo que {@link #CLUSTER_TOLERANCE}: suficiente aqui, onde a variância medida
     * dentro de uma coluna real é ~0,01pt e as colunas distam ~216pt uma da outra.
     */
    private List<float[]> clusterByProximity(List<Float> sortedX) {
        List<float[]> clusters = new ArrayList<>();
        int i = 0;
        while (i < sortedX.size()) {
            float start = sortedX.get(i);
            float sum = 0f;
            int count = 0;
            while (i < sortedX.size() && sortedX.get(i) - start <= CLUSTER_TOLERANCE) {
                sum += sortedX.get(i);
                count++;
                i++;
            }
            clusters.add(new float[] {sum / count, count});
        }
        return clusters;
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
