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
import java.time.YearMonth;
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
    // 15–36, nunca fica perto do limiar. Fica no mínimo porque é o único valor que cobre a
    // coluna direita esparsa (última página com 1–2 lançamentos). O risco oposto — uma data
    // solta virando "coluna" — não é barrado por limiar de massa nenhum, e sim pela checagem de
    // faixa vazia em fimDoConteudoAEsquerda(): se o conteúdo da esquerda alcança a suposta
    // coluna direita, a página vira coluna única em vez de ser cortada ao meio.
    private static final int MIN_CLUSTER_MASS = 1;

    // Distância mínima entre os dois clusters para serem colunas de verdade, e não uma coluna
    // real mais um punhado de datas dispersas. Medido: as duas colunas ficam a 215,6–218,3pt
    // uma da outra em todas as 121 páginas do levantamento. O limiar é menos da metade disso.
    private static final float MIN_COLUMN_SEPARATION = 100f;

    // Afastamento entre as duas colunas de lançamento — usado para posicionar a coluna direita
    // "virtual" quando a página tem lançamentos só à esquerda (última página), de modo a isolar
    // as caixas de resumo do lado direito. Medido em 215,6–218,3pt; aqui fica um pouco abaixo,
    // pra cair na FAIXA VAZIA antes da coluna, não em cima dela. Varredura de sensibilidade
    // sobre as 45 faturas: 190 a 214 dão resultado idêntico (23 faturas exatas), 220 degrada —
    // ou seja, é um platô largo, não um valor de sorte.
    private static final float COLUMN_OFFSET = 206f;

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
        LocalDate vencimento = extrairVencimento(fullText);
        int mesVencimento = vencimento.getMonthValue();
        int anoVencimento = vencimento.getYear();

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

    @Override
    public YearMonth targetInvoiceReferenceMonth(String fullText) {
        // dueDay >= closingDay é o caso normal do Itaú (confirmado nas 45 faturas medidas na
        // spec de coluna: vencimento sempre dia 10) — nesse caso InvoiceService.createNewInvoice
        // vence a fatura de referenceMonth no mês SEGUINTE. Recalcular pelo closingDay
        // configurado na conta reintroduziria a mesma fragilidade que causou o sintoma original
        // (spec 2026-08-09 §2.c) — o vencimento IMPRESSO já é o dado certo, independente de
        // como a conta está configurada no sistema.
        return YearMonth.from(extrairVencimento(fullText)).minusMonths(1);
    }

    /** Vencimento impresso no documento (único, aparece uma vez, com ano completo). */
    private LocalDate extrairVencimento(String fullText) {
        Matcher dueDateMatcher = DUE_DATE.matcher(fullText);
        if (!dueDateMatcher.find()) {
            throw new ExtractionException(
                    "Não foi possível localizar a data de vencimento na fatura Itaú.");
        }
        int dia = Integer.parseInt(dueDateMatcher.group(1));
        int mes = Integer.parseInt(dueDateMatcher.group(2));
        int ano = Integer.parseInt(dueDateMatcher.group(3));
        return LocalDate.of(ano, mes, dia);
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
        List<float[]> clusters = clusterByProximity(anchors);
        if (clusters.isEmpty()) {
            return pageWidth;
        }
        // O cluster de MAIOR massa é seguramente uma coluna real (medido: 15–36 lançamentos,
        // contra 1–3 do ruído). É a âncora a partir da qual a outra coluna é procurada.
        clusters.sort((a, b) -> Float.compare(b[1], a[1]));
        float[] dominante = clusters.get(0);

        // Candidatas a "outra coluna", da MAIS PRÓXIMA à mais distante da dominante. Ordenar por
        // proximidade e não por massa importa: numa última página, a coluna direita pode ter 1–2
        // lançamentos e haver ruído mais massivo à direita dela (datas da seção de limites) — por
        // massa, o ruído venceria e o corte cairia depois da coluna real, partindo-a ao meio.
        List<float[]> candidatas = new ArrayList<>();
        for (int i = 1; i < clusters.size(); i++) {
            float[] candidata = clusters.get(i);
            if (candidata[1] >= MIN_CLUSTER_MASS
                    && Math.abs(candidata[0] - dominante[0]) >= MIN_COLUMN_SEPARATION) {
                candidatas.add(candidata);
            }
        }
        candidatas.sort(Comparator.comparingDouble(c -> Math.abs(c[0] - dominante[0])));

        // Fica com a PRIMEIRA candidata que deixa uma faixa vertical de fato vazia até ela.
        // Só proximidade não basta: uma data solta ENTRE as duas colunas é mais próxima que a
        // coluna direita real e sequestraria a escolha. Exigir a faixa vazia descarta a
        // intrusa — o conteúdo da esquerda a alcança — e a busca segue para a coluna seguinte.
        for (float[] candidata : candidatas) {
            float rightColumnX = Math.max(dominante[0], candidata[0]);
            float leftContentEnd =
                    fimDoConteudoAEsquerda(tokensByRow, rightColumnX - CLUSTER_TOLERANCE);
            if (leftContentEnd < rightColumnX) {
                // Meio da faixa vertical comprovadamente vazia entre as duas colunas.
                return (leftContentEnd + rightColumnX) / 2f;
            }
        }

        // Nenhuma segunda COLUNA DE LANÇAMENTOS. Isso não quer dizer que o lado direito esteja
        // vazio: a última página de lançamentos costuma ter lançamentos só à esquerda e caixas
        // de resumo à direita ("Limites de crédito", "Encargos cobrados"). Sem corte, o texto
        // dessas caixas entra no mesmo fluxo — e como os títulos delas são justamente os
        // marcadores de fim de bloco, o bloco é cortado logo no começo e a página inteira se
        // perde (medido: R$ 602,12 e R$ 162,74 sumindo de duas faturas reais).
        //
        // Por isso cortamos assim mesmo, na posição onde a coluna direita ESTARIA. O afastamento
        // entre colunas é a medida mais estável do layout: 215,6–218,3pt em 121 páginas de 45
        // faturas ao longo de 4 anos. Não é a posição absoluta de uma coluna (isso variou e
        // causou os defeitos anteriores) — é a distância entre elas.
        float colunaVirtualX = dominante[0] + COLUMN_OFFSET;
        if (colunaVirtualX < pageWidth) {
            float leftContentEnd = fimDoConteudoAEsquerda(tokensByRow, colunaVirtualX);
            if (leftContentEnd < colunaVirtualX) {
                return (leftContentEnd + colunaVirtualX) / 2f;
            }
        }

        if (!candidatas.isEmpty()) {
            log.warn("ItauFaturaTemplate: nenhuma das {} candidatas a segunda coluna deixa faixa "
                    + "vazia na página {} — tratada como coluna única.",
                    candidatas.size(), pageNumberOneBased);
        }
        return pageWidth;
    }

    /**
     * X em que termina o conteúdo das LINHAS DE LANÇAMENTO à esquerda de {@code limiteEsquerda}.
     *
     * <p>Só linhas de lançamento contam: rodapé, endereço e cabeçalho atravessam a calha com
     * frequência em página real e não são conteúdo que se possa partir — considerá-los faria a
     * página degradar por causa de um texto que ninguém precisa preservar intacto.
     *
     * <p>Devolve {@code 0} quando não há nenhuma linha de lançamento à esquerda (o corte então
     * fica no meio do caminho até a coluna direita, sem nada a preservar).
     *
     * <p>O limite é passado pronto pelo chamador porque as duas origens pedem correções
     * diferentes: vindo de um cluster real, é preciso descontar a tolerância (o representante é
     * o centroide, uma média, e metade dos tokens da coluna cai abaixo dele); vindo da coluna
     * virtual, não há cluster nenhum e descontar criaria um ponto cego — tokens entre o limite
     * descontado e o corte ficariam de fora da conta e o corte partiria a linha ali.
     */
    private float fimDoConteudoAEsquerda(Map<Integer, List<Token>> tokensByRow, float limiteEsquerda) {
        float leftEnd = 0f;
        for (List<Token> row : tokensByRow.values()) {
            boolean lancamentoAEsquerda = row.stream()
                    .anyMatch(t -> t.xStart() < limiteEsquerda
                            && DATE_TOKEN_PREFIX.matcher(t.text()).find());
            if (!lancamentoAEsquerda) {
                continue;
            }
            for (Token t : row) {
                if (t.xStart() < limiteEsquerda) {
                    leftEnd = Math.max(leftEnd, t.xEnd());
                }
            }
        }
        return leftEnd;
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
     * Agrupa posições X próximas — cada item devolvido é {@code {centroide, massa}}.
     *
     * <p>Cada valor é comparado com o primeiro do grupo, então um grupo nunca fica mais largo
     * que {@link #CLUSTER_TOLERANCE} — suficiente aqui, onde a variância medida dentro de uma
     * coluna real é ~0,01pt e as colunas distam ~216pt uma da outra.
     *
     * <p>Quem consome o centroide precisa descontar {@link #CLUSTER_TOLERANCE} ao usá-lo como
     * fronteira de coluna, porque metade dos membros do grupo fica abaixo da média — ver
     * {@link #fimDoConteudoAEsquerda}, que documenta por que a alternativa "guardar o menor X"
     * foi medida e rejeitada.
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
