import { describe, expect, it } from 'vitest';
import { summarizeInstallmentCreation } from './installment-result.utils';

describe('summarizeInstallmentCreation', () => {
  it('informa quando nenhuma parcela foi lançada', () => {
    expect(summarizeInstallmentCreation(0, 6)).toEqual({
      kind: 'none',
      message: 'Nenhuma parcela foi lançada: todas cairiam em faturas fechadas ou pagas.',
      createdCount: 0,
      requestedCount: 6,
    });
  });

  it('informa a contagem parcial e o motivo das parcelas ignoradas', () => {
    expect(summarizeInstallmentCreation(4, 6)).toEqual({
      kind: 'partial',
      message:
        '4 de 6 parcelas criadas. As demais foram ignoradas porque as faturas estão fechadas ou pagas.',
      createdCount: 4,
      requestedCount: 6,
    });
  });

  it('mantém a mensagem de sucesso quando todas as parcelas foram criadas', () => {
    expect(summarizeInstallmentCreation(6, 6).message).toBe('6 parcelas criadas com sucesso!');
    expect(summarizeInstallmentCreation(1, 1).message).toBe('Transação criada com sucesso!');
  });
});
