package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import com.fintech.api.dto.imports.StagedFieldValueDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extrator de CSV genérico — segundo do funil do {@link ExtractionRouter} (roadmap §1.2: padrão
 * universal (OFX) → GENÉRICO (aqui) → IA). Diferente do OFX, um CSV não tem contrato fixo de
 * campos: cada banco/planilha nomeia colunas do seu jeito. Este extrator resolve isso com
 * HEURÍSTICAS DETERMINÍSTICAS (nunca IA) — casamento de header por sinônimo, inferência de
 * decimal/data/direção — cada uma documentada abaixo, porque é aqui que a régua de confiança
 * por campo (Fase 0/1) realmente importa: coluna casada por nome é confiança 1.0, valor
 * inferido (decimal ambíguo, direção pelo sinal) é confiança mais baixa.
 *
 * <p>Header IRRECONHECÍVEL (nenhuma coluna de data nem de valor identificada) faz
 * {@link #supports} devolver {@code false} — o arquivo cai no erro explícito do
 * {@link ExtractionRouter} (400), não numa extração capenga. Mandar isso pra IA é decisão da
 * Fase 3 (critério de saída do roadmap), não desta fatia.
 */
@Component
@Order(20)
@Slf4j
public class CsvExtractor implements TransactionExtractor {

    private static final String EXTRACTOR_USED = "csv_generic_v1";

    // Sinônimos normalizados (sem acento, minúsculo, espaços colapsados) — cada bullet é uma
    // grafia real vista em extrato de banco/planilha brasileira.
    private static final Set<String> DATE_SYNONYMS =
            Set.of("data", "date", "data da compra", "dt", "data transacao", "data compra");
    private static final Set<String> AMOUNT_SYNONYMS =
            Set.of("valor", "amount", "quantia", "valor total", "montante");
    private static final Set<String> DESCRIPTION_SYNONYMS =
            Set.of("descricao", "description", "historico", "estabelecimento", "memo");
    private static final Set<String> TYPE_SYNONYMS =
            Set.of("tipo", "type", "direcao", "direction", "natureza");

    private static final Set<String> CREDIT_VALUES = Set.of("credito", "credit", "entrada", "c");
    private static final Set<String> DEBIT_VALUES = Set.of("debito", "debit", "saida", "d", "compra");

    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final String extractorVersion;

    public CsvExtractor(@Value("${import.csv.extractor-version:v1}") String extractorVersion) {
        this.extractorVersion = extractorVersion;
    }

    @Override
    public boolean supports(ExtractionInput input) {
        if (input.content() == null || input.content().length == 0) {
            return false;
        }
        ParsedCsv parsed = tryParse(input.content());
        if (parsed == null) {
            return false;
        }
        HeaderMapping mapping = matchHeader(parsed.header());
        // Header PLAUSÍVEL = pelo menos 1 coluna de data E 1 de valor reconhecidas. Sem isso,
        // não dá pra confiar que este é mesmo um extrato de transações (poderia ser qualquer CSV).
        return mapping.dateCol() >= 0 && mapping.amountCol() >= 0;
    }

    @Override
    public ImportSourceType sourceType() {
        return ImportSourceType.CSV;
    }

    @Override
    public String extractorVersion() {
        return extractorVersion;
    }

    @Override
    public NormalizedBatchDTO extract(ExtractionInput input) {
        ParsedCsv parsed = tryParse(input.content());
        if (parsed == null) {
            throw new ExtractionException("Não foi possível ler este CSV — delimitador inconsistente entre linhas.");
        }
        HeaderMapping mapping = matchHeader(parsed.header());
        if (mapping.dateCol() < 0 || mapping.amountCol() < 0) {
            throw new ExtractionException("Cabeçalho do CSV não tem coluna de data e/ou de valor reconhecível.");
        }

        // Linha que não parseou (null) vira "linha ruim" (mesma régua de dado ilegível —
        // confiança 0, nada apagado), sem derrubar as demais transações do arquivo.
        List<NormalizedTransactionDTO> transactions = parsed.rows().stream()
                .map(row -> row == null ? garbledTransaction() : toTransaction(row, mapping))
                .toList();

        return new NormalizedBatchDTO(input.mode(), ImportSourceType.CSV, EXTRACTOR_USED, extractorVersion, transactions);
    }

    /**
     * Linha que nem chega a parsear como CSV (ex.: aspa solta fora do padrão) — mesma régua da
     * "linha ruim" (confiança 0 em tudo, nenhum valor apagado porque não há valor legível pra
     * preservar), sem derrubar a transação nem o restante do arquivo.
     */
    private NormalizedTransactionDTO garbledTransaction() {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();
        fields.put("amount", new StagedFieldValueDTO(null, BigDecimal.ZERO));
        fields.put("transaction_date", new StagedFieldValueDTO(null, BigDecimal.ZERO));
        fields.put("direction", new StagedFieldValueDTO(null, BigDecimal.ZERO));
        fields.put("description", new StagedFieldValueDTO(null, BigDecimal.ZERO));
        return new NormalizedTransactionDTO(null, fields, null, null, BigDecimal.ZERO, null, null);
    }

    private NormalizedTransactionDTO toTransaction(CSVRecord row, HeaderMapping mapping) {
        Map<String, StagedFieldValueDTO> fields = new LinkedHashMap<>();

        String rawAmount = cell(row, mapping.amountCol());
        BigDecimal amount = parseAmount(rawAmount);
        // Coluna casada por HEADER (não por posição) → confiança 1.0 na identificação do campo;
        // o valor em si só falha (confiança 0) se o conteúdo da célula for ilegível.
        fields.put("amount", new StagedFieldValueDTO(amount == null ? null : amount.abs(),
                amount == null ? BigDecimal.ZERO : BigDecimal.ONE));

        String rawDate = cell(row, mapping.dateCol());
        LocalDate date = parseDate(rawDate);
        fields.put("transaction_date",
                new StagedFieldValueDTO(date == null ? null : date.toString(), date == null ? BigDecimal.ZERO : BigDecimal.ONE));

        String rawType = cell(row, mapping.typeCol());
        // Diferente do OFX (TRNAMT tem sinal contratual): um CSV genérico não garante que o
        // sinal do valor SEMPRE reflete a direção — por isso confiança 0.7 (inferência), nunca 1.0.
        fields.put("direction", new StagedFieldValueDTO(direction(amount, rawType), new BigDecimal("0.7")));

        int descriptionCol = mapping.descriptionCol() >= 0 ? mapping.descriptionCol() : mapping.fallbackDescriptionCol();
        String description = blankToNull(cell(row, descriptionCol));
        // Header casou o nome da coluna → 1.0; sobrou por ELIMINAÇÃO (nenhum sinônimo bateu) → 0.7.
        BigDecimal descriptionConfidence = description == null
                ? BigDecimal.ZERO
                : (mapping.descriptionCol() >= 0 ? BigDecimal.ONE : new BigDecimal("0.7"));
        fields.put("description", new StagedFieldValueDTO(description, descriptionConfidence));

        BigDecimal overallConfidence = fields.get("amount").confidence().min(fields.get("transaction_date").confidence());
        return new NormalizedTransactionDTO(null, fields, null, null, overallConfidence, null, null);
    }

    /** {@code null} se a coluna não foi identificada ou a linha não tem célula naquele índice. */
    private String cell(CSVRecord row, int col) {
        return (col >= 0 && col < row.size()) ? row.get(col) : null;
    }

    private String direction(BigDecimal amount, String rawType) {
        if (rawType != null) {
            String normalized = normalize(rawType);
            if (CREDIT_VALUES.contains(normalized)) {
                return "credit";
            }
            if (DEBIT_VALUES.contains(normalized)) {
                return "debit";
            }
        }
        if (amount != null) {
            return amount.signum() < 0 ? "debit" : "credit";
        }
        // Sem sinal e sem coluna de tipo legível: cai no caso mais comum (compra), mesma
        // convenção conservadora da Fase 1 (VisionExtractor.normalizeDirection).
        return "debit";
    }

    /**
     * Decimal pt-BR ({@code 1.234,56}) vs. padrão ({@code 1234.56}) inferido POR VALOR: se a
     * célula tem os dois separadores, o ÚLTIMO é o decimal (o outro é milhar); se só tem vírgula,
     * vírgula é decimal; senão assume ponto. Evita depender de uma convenção fixa por coluna —
     * um extrato real às vezes mistura formatação entre linhas exportadas por sistemas diferentes.
     */
    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // Remove símbolo de moeda e espaços ("R$ 74,87" → "74,87") antes de normalizar separador —
        // sem isso, o símbolo sobrevive à troca de vírgula/ponto e o BigDecimal final estoura
        // NumberFormatException (valor vira null em toda a coluna, derrubando o batch inteiro).
        String trimmed = raw.trim().replaceAll("[^\\d,.\\-]", "");
        boolean hasComma = trimmed.indexOf(',') >= 0;
        boolean hasDot = trimmed.indexOf('.') >= 0;
        String normalized;
        if (hasComma && hasDot) {
            normalized = trimmed.lastIndexOf(',') > trimmed.lastIndexOf('.')
                    ? trimmed.replace(".", "").replace(',', '.')
                    : trimmed.replace(",", "");
        } else if (hasComma) {
            normalized = trimmed.replace(',', '.');
        } else {
            normalized = trimmed;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Aceita {@code DD/MM/YYYY} (pt-BR) e {@code YYYY-MM-DD} (ISO); outro formato → null (força revisão). */
    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        for (DateTimeFormatter fmt : List.of(DateTimeFormatter.ISO_LOCAL_DATE, BR_DATE)) {
            try {
                return LocalDate.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {
                // tenta o próximo formato
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * Casa cada coluna do header contra os sinônimos normalizados. {@code description} sem
     * sinônimo reconhecido cai para a primeira coluna sobrando (posição) — {@code fallbackDescriptionCol}.
     */
    private HeaderMapping matchHeader(CSVRecord header) {
        int dateCol = -1;
        int amountCol = -1;
        int descriptionCol = -1;
        int typeCol = -1;
        for (int i = 0; i < header.size(); i++) {
            String normalized = normalize(header.get(i));
            if (dateCol < 0 && DATE_SYNONYMS.contains(normalized)) {
                dateCol = i;
            } else if (amountCol < 0 && AMOUNT_SYNONYMS.contains(normalized)) {
                amountCol = i;
            } else if (descriptionCol < 0 && DESCRIPTION_SYNONYMS.contains(normalized)) {
                descriptionCol = i;
            } else if (typeCol < 0 && TYPE_SYNONYMS.contains(normalized)) {
                typeCol = i;
            }
        }
        int fallbackDescriptionCol = -1;
        if (descriptionCol < 0) {
            for (int i = 0; i < header.size(); i++) {
                if (i != dateCol && i != amountCol && i != typeCol) {
                    fallbackDescriptionCol = i;
                    break;
                }
            }
        }
        return new HeaderMapping(dateCol, amountCol, descriptionCol, typeCol, fallbackDescriptionCol);
    }

    /** Remove acentos, minúsculo, trim, colapsa espaços — "Descrição" e "descricao" viram o mesmo token. */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String withoutAccents = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return withoutAccents.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Detecta o delimitador (',' depois ';') e devolve o header + as linhas de dado JÁ PARSEADAS
     * ({@code null} na posição = linha que não parseou → "linha ruim" no extract()).
     *
     * <p>Duas passadas, nesta ordem, porque as duas propriedades desejadas se excluem num parse só:
     * <ol>
     *   <li><b>Arquivo inteiro</b> — é o único jeito de honrar RFC 4180 por completo, em especial
     *       campo entre aspas com QUEBRA DE LINHA dentro (uma descrição multilinha é um registro
     *       só, não N linhas). Aceito apenas se TODAS as linhas de dado batem a largura do header:
     *       uma aspa solta faz o commons-csv engolir o resto do arquivo num campo gigante, e o
     *       resultado passaria como "válido" com largura errada — a largura é o que separa os
     *       dois casos.</li>
     *   <li><b>Linha a linha</b> (fallback) — quando o arquivo inteiro não fecha, uma linha com
     *       aspa solta fora do padrão (commons-csv lança {@code CSVException}, capturada via
     *       {@link UncheckedIOException}, não {@link IOException}) fica ISOLADA como linha ruim
     *       em vez de derrubar as demais transações. Basta UMA linha bater a largura do header.</li>
     * </ol>
     */
    private ParsedCsv tryParse(byte[] content) {
        String decoded = decode(content);
        List<String> lines = decoded.lines().filter(l -> !l.isBlank()).toList();
        if (lines.size() < 2) {
            return null; // precisa header + ao menos 1 linha de dado
        }

        for (char delimiter : new char[] {',', ';'}) {
            CSVRecord header = parseSingleLine(lines.get(0), delimiter);
            if (header == null || header.size() < 2) {
                continue; // header não parseia com este delimitador (ou só teria 1 coluna)
            }
            int width = header.size();

            List<CSVRecord> whole = parseWhole(decoded, delimiter);
            if (whole != null && whole.size() >= 2) {
                List<CSVRecord> dataRows = whole.subList(1, whole.size());
                if (dataRows.stream().allMatch(row -> row.size() == width)) {
                    return new ParsedCsv(header, dataRows);
                }
            }

            List<CSVRecord> rows = lines.subList(1, lines.size()).stream()
                    .map(line -> parseSingleLine(line, delimiter))
                    .toList(); // pode conter null — Stream.toList() aceita
            if (rows.stream().anyMatch(row -> row != null && row.size() == width)) {
                return new ParsedCsv(header, rows);
            }
        }
        return null;
    }

    /** Parseia o arquivo INTEIRO; {@code null} se ele não é um CSV válido com este delimitador. */
    private List<CSVRecord> parseWhole(String decoded, char delimiter) {
        try {
            return CSVParser.parse(decoded, csvFormat(delimiter)).getRecords();
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    /** Parseia UMA linha isolada; {@code null} se esta linha não é um CSV válido com este delimitador. */
    private CSVRecord parseSingleLine(String line, char delimiter) {
        try {
            List<CSVRecord> records = CSVParser.parse(line, csvFormat(delimiter)).getRecords();
            return records.isEmpty() ? null : records.get(0);
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
    }

    private CSVFormat csvFormat(char delimiter) {
        return CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setDelimiter(delimiter)
                .setTrim(true)
                .get();
    }

    /**
     * BOM UTF-8 explícito → UTF-8 sem o BOM. Senão tenta UTF-8 ESTRITO (rejeita byte inválido) e
     * só cai para ISO-8859-1 se UTF-8 falhar — ISO-8859-1 nunca lança (1 byte = 1 char), então
     * teria que ser o ÚLTIMO recurso, não o primeiro (senão UTF-8 real vira texto errado calado).
     */
    private String decode(byte[] content) {
        if (content.length >= 3 && content[0] == UTF8_BOM[0] && content[1] == UTF8_BOM[1] && content[2] == UTF8_BOM[2]) {
            return new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(content, StandardCharsets.ISO_8859_1);
        }
    }

    /** {@code rows} pode conter {@code null} — posição de linha que não parseou (linha ruim). */
    private record ParsedCsv(CSVRecord header, List<CSVRecord> rows) {}

    private record HeaderMapping(int dateCol, int amountCol, int descriptionCol, int typeCol, int fallbackDescriptionCol) {}
}
