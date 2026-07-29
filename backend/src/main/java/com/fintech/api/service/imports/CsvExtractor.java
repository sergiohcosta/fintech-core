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
        HeaderMapping mapping = matchHeader(parsed.records().get(0));
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
        List<CSVRecord> records = parsed.records();
        HeaderMapping mapping = matchHeader(records.get(0));
        if (mapping.dateCol() < 0 || mapping.amountCol() < 0) {
            throw new ExtractionException("Cabeçalho do CSV não tem coluna de data e/ou de valor reconhecível.");
        }

        List<NormalizedTransactionDTO> transactions = records.stream()
                .skip(1)
                .map(row -> toTransaction(row, mapping))
                .toList();

        return new NormalizedBatchDTO(input.mode(), ImportSourceType.CSV, EXTRACTOR_USED, extractorVersion, transactions);
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
        String trimmed = raw.trim();
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
     * Tenta ler o CSV com cada delimitador candidato (',' depois ';') e aceita o primeiro cuja
     * largura (nº de colunas) é CONSISTENTE em todas as linhas — evita adivinhar delimitador por
     * contagem bruta de caracteres, que quebraria com campo entre aspas contendo o outro delimitador.
     */
    private ParsedCsv tryParse(byte[] content) {
        String decoded = decode(content);
        for (char delimiter : new char[] {',', ';'}) {
            try {
                CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                        .setDelimiter(delimiter)
                        .setTrim(true)
                        .setIgnoreEmptyLines(true)
                        .get();
                List<CSVRecord> records = CSVParser.parse(decoded, format).getRecords();
                if (records.size() < 2) {
                    continue; // precisa header + ao menos 1 linha de dado
                }
                int width = records.get(0).size();
                if (width < 2 || records.stream().anyMatch(r -> r.size() != width)) {
                    continue; // largura inconsistente = delimitador errado (ou arquivo não é CSV)
                }
                return new ParsedCsv(delimiter, records);
            } catch (IOException e) {
                // este delimitador produziu um CSV malformado — tenta o próximo candidato
            }
        }
        return null;
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

    private record ParsedCsv(char delimiter, List<CSVRecord> records) {}

    private record HeaderMapping(int dateCol, int amountCol, int descriptionCol, int typeCol, int fallbackDescriptionCol) {}
}
