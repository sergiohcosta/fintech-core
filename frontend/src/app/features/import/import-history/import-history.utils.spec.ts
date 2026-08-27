import { describe, it, expect } from 'vitest';
import { batchStatusLabel, batchStatusClass, sourceTypeLabel } from './import-history.utils';

describe('batchStatusLabel', () => {
  it('traduz cada status pra rótulo pt-BR', () => {
    expect(batchStatusLabel('EXTRACTED')).toBe('Extraído');
    expect(batchStatusLabel('COMMITTED')).toBe('Lançado');
    expect(batchStatusLabel('FAILED')).toBe('Falhou');
  });

  it('cai pro próprio valor quando o status não é reconhecido (nunca esconde dado)', () => {
    expect(batchStatusLabel('PENDING')).toBe('PENDING');
  });
});

describe('batchStatusClass', () => {
  it('mapeia status pra classe de chip visual', () => {
    expect(batchStatusClass('COMMITTED')).toBe('status-chip status-committed');
    expect(batchStatusClass('FAILED')).toBe('status-chip status-failed');
  });

  it('cai pra classe neutra quando o status não é reconhecido', () => {
    expect(batchStatusClass('PENDING')).toBe('status-chip');
  });
});

describe('sourceTypeLabel', () => {
  it('traduz o tipo de origem pra rótulo pt-BR', () => {
    expect(sourceTypeLabel('IMAGE')).toBe('Imagem');
    expect(sourceTypeLabel('CSV')).toBe('CSV');
    expect(sourceTypeLabel('OFX')).toBe('OFX');
    expect(sourceTypeLabel('PDF_TEXT')).toBe('PDF');
    expect(sourceTypeLabel('PDF_SCANNED')).toBe('PDF (escaneado)');
  });
});
