package com.fintech.api.service.imports;

import com.fintech.api.domain.enums.ImportSourceType;
import com.fintech.api.dto.imports.NormalizedBatchDTO;
import com.fintech.api.dto.imports.NormalizedTransactionDTO;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Extrator de texto de PDF — primeira fatia da Fase 3 do funil do {@link ExtractionRouter}
 * (roadmap §1.2: padrão universal (OFX) → GENÉRICO (CSV, aqui) → IA). Diferente do CSV, texto de
 * PDF não tem separador estrutural nenhum (sem coluna, sem tag) — o único sinal disponível é o
 * padrão típico de uma linha de extrato (data + descrição + valor), reconhecido por heurística de
 * linha (Onda 2 deste plano).
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

    private final String extractorVersion;

    public PdfTextExtractor(@Value("${import.pdf-text.extractor-version:v1}") String extractorVersion) {
        this.extractorVersion = extractorVersion;
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

        // Heurística de reconhecimento de linha (data + descrição + valor) entra na Onda 2 deste
        // plano. Por ora, o caminho feliz (PDF com texto) devolve batch vazio — o guard-rail de
        // "zero transações aproveitáveis" do ImportService já converte isso em FAILED explícito,
        // sem duplicar essa checagem aqui.
        List<NormalizedTransactionDTO> transactions = List.of();

        return new NormalizedBatchDTO(input.mode(), ImportSourceType.PDF_TEXT, EXTRACTOR_USED, extractorVersion, transactions);
    }

    /** Extrai o texto bruto do documento inteiro. Falha do PDFBox (corrompido/senha) vira {@link ExtractionException}. */
    private String extractText(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            // Nenhuma exceção de infra (mensagem/stacktrace do PDFBox) cruza a borda da API —
            // mesma regra do restante do pipeline (ImportService.failureReasonFor).
            throw new ExtractionException("Não foi possível abrir este PDF — arquivo corrompido ou protegido por senha.", e);
        }
    }
}
