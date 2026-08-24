package com.fintech.api.service.imports;

/**
 * Marca uma falha originada no caminho de PDF escaneado ({@link PdfTextExtractor#extractScanned}).
 * {@code TransactionExtractor.sourceType()} é fixo por extrator — cobre o caso "extract() nunca
 * chegou a decidir o sub-caminho", não "decidiu escaneado e falhou depois" (limite de páginas,
 * página ilegível). Sem essa distinção, {@code ImportService} gravaria todo batch FAILED deste
 * caminho como {@code PDF_TEXT}, o oposto do que a proveniência (V28) existe pra rastrear.
 * Pacote-privada: só {@code ImportService} (mesmo pacote) precisa diferenciar o tipo.
 */
class ScannedPdfExtractionException extends ExtractionException {

    ScannedPdfExtractionException(String message) {
        super(message);
    }

    ScannedPdfExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
