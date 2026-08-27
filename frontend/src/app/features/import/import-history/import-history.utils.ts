/** Lógica pura de exibição do histórico — sem import Angular (testável no Vitest sem TestBed). */

const STATUS_LABELS: Record<string, string> = {
  EXTRACTED: 'Extraído',
  REVIEWED: 'Revisado',
  COMMITTED: 'Lançado',
  FAILED: 'Falhou',
};

/** Rótulo pt-BR do status. Cai pro valor cru quando não reconhecido — nunca esconde o dado. */
export function batchStatusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

const STATUS_CLASSES: Record<string, string> = {
  EXTRACTED: 'status-chip status-extracted',
  REVIEWED: 'status-chip status-reviewed',
  COMMITTED: 'status-chip status-committed',
  FAILED: 'status-chip status-failed',
};

export function batchStatusClass(status: string): string {
  return STATUS_CLASSES[status] ?? 'status-chip';
}

const SOURCE_TYPE_LABELS: Record<string, string> = {
  IMAGE: 'Imagem',
  CSV: 'CSV',
  OFX: 'OFX',
  PDF_TEXT: 'PDF',
  PDF_SCANNED: 'PDF (escaneado)',
  AUDIO: 'Áudio',
};

export function sourceTypeLabel(sourceType: string): string {
  return SOURCE_TYPE_LABELS[sourceType] ?? sourceType;
}
