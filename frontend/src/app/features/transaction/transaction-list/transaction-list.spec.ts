import { describe, it, expect } from 'vitest';
import {
  buildDisplayRows, DisplayRow, InstallmentGroupInfo,
  effectiveMonth, groupByEffectiveMonth, resolveMonthKey, monthBounds, formatMonthLabel,
  PeriodGroup, computeMonthChipStates,
} from './transaction-list.utils';

describe('buildDisplayRows', () => {
  it('cada parcela do mesmo grupo aparece como linha individual', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PAID',    installmentNumber: 1, totalInstallments: 3, amount: 1000, date: '2026-01-01', type: 'EXPENSE' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 3, amount: 1000, date: '2026-02-01', type: 'EXPENSE' },
      { id: '3', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 3, totalInstallments: 3, amount: 1000, date: '2026-03-01', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(3);
    rows.forEach(r => expect(r.kind).toBe('installment'));
  });

  it('info do grupo está acessível em cada parcela individual', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PAID',    installmentNumber: 1, totalInstallments: 2, amount: 500, date: '2026-01-01', type: 'EXPENSE' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 2, amount: 500, date: '2026-02-01', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(2);
    rows.forEach(r => {
      expect(r.kind).toBe('installment');
      const group = (r as any).group as InstallmentGroupInfo;
      expect(group.totalInstallments).toBe(2);
      expect(group.paidInstallments).toBe(1);
      expect(group.description).toBe('Notebook');
    });
  });

  it('expande uma parcela inserindo uma linha de detalhe logo após ela', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PAID',    installmentNumber: 1, totalInstallments: 2, amount: 500, date: '2026-01-01', type: 'EXPENSE' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 2, amount: 500, date: '2026-02-01', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set(['1']));

    expect(rows).toHaveLength(3);
    expect(rows[0].kind).toBe('installment');
    expect((rows[0] as any).isExpanded).toBe(true);
    expect(rows[1].kind).toBe('installment-detail');
    expect(rows[2].kind).toBe('installment');
    expect((rows[2] as any).isExpanded).toBe(false);
  });

  it('mantém transações avulsas como linhas single separadas', () => {
    const txs: any[] = [
      { id: '1', description: 'Salário', status: 'PAID',    amount: 5000, date: '2026-01-05', type: 'INCOME' },
      { id: '2', description: 'Mercado', status: 'PENDING', amount: 300,  date: '2026-01-10', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(2);
    rows.forEach((r: DisplayRow) => expect(r.kind).toBe('single'));
  });

  it('mistura de avulsas e parceladas mantém a ordem original', () => {
    const txs: any[] = [
      { id: '1', description: 'Salário',   status: 'PAID',    amount: 5000, date: '2026-01-01', type: 'INCOME' },
      { id: '2', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 1, totalInstallments: 2, amount: 500, date: '2026-01-05', type: 'EXPENSE' },
      { id: '3', description: 'Mercado',   status: 'PAID',    amount: 300,  date: '2026-01-10', type: 'EXPENSE' },
      { id: '4', installmentGroupId: 'g1', installmentGroupDescription: 'Notebook', description: 'Notebook', status: 'PENDING', installmentNumber: 2, totalInstallments: 2, amount: 500, date: '2026-02-05', type: 'EXPENSE' },
    ];

    const rows = buildDisplayRows(txs, new Set());

    expect(rows).toHaveLength(4);
    expect(rows[0].kind).toBe('single');
    expect(rows[1].kind).toBe('installment');
    expect(rows[2].kind).toBe('single');
    expect(rows[3].kind).toBe('installment');
  });
});

describe('effectiveMonth', () => {
  it('parcela de cartão usa mês do invoiceDueDate', () => {
    const t: any = { installmentGroupId: 'g1', invoiceDueDate: '2026-07-10', date: '2026-06-01' };
    expect(effectiveMonth(t)).toBe('2026-07');
  });

  it('transação avulsa usa mês de date', () => {
    const t: any = { installmentGroupId: null, date: '2026-06-15' };
    expect(effectiveMonth(t)).toBe('2026-06');
  });

  it('transação avulsa de cartão (sem installmentGroupId, mesmo com invoiceDueDate) usa date', () => {
    const t: any = { installmentGroupId: null, invoiceDueDate: '2026-07-10', date: '2026-06-15' };
    expect(effectiveMonth(t)).toBe('2026-06');
  });
});

describe('groupByEffectiveMonth', () => {
  it('retorna grupos em ordem decrescente de mês', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-05-10', type: 'EXPENSE', amount: 100, status: 'PAID' },
      { id: '2', installmentGroupId: null, date: '2026-06-15', type: 'INCOME',  amount: 500, status: 'PAID' },
    ];
    const groups = groupByEffectiveMonth(txs);
    expect(groups[0].key).toBe('2026-06');
    expect(groups[1].key).toBe('2026-05');
  });

  it('calcula totalIncome, totalExpense e balance por grupo', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-06-10', type: 'INCOME',  amount: 1000, status: 'PAID' },
      { id: '2', installmentGroupId: null, date: '2026-06-20', type: 'EXPENSE', amount: 300,  status: 'PAID' },
    ];
    const [group] = groupByEffectiveMonth(txs);
    expect(group.totalIncome).toBe(1000);
    expect(group.totalExpense).toBe(300);
    expect(group.balance).toBe(700);
  });

  it('parcelas de cartão agrupam pelo mês do invoiceDueDate', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: 'g1', invoiceDueDate: '2026-07-15', date: '2026-06-01', type: 'EXPENSE', amount: 200, status: 'PENDING' },
    ];
    const groups = groupByEffectiveMonth(txs);
    expect(groups[0].key).toBe('2026-07');
  });

  it('calcula balance negativo quando despesas superam receitas', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-06-10', type: 'INCOME',  amount: 500, status: 'PAID' },
      { id: '2', installmentGroupId: null, date: '2026-06-20', type: 'EXPENSE', amount: 800, status: 'PAID' },
    ];
    const [group] = groupByEffectiveMonth(txs);
    expect(group.balance).toBe(-300);
  });
});

describe('resolveMonthKey', () => {
  it('retorna chave YYYY-MM quando o range é exatamente um mês', () => {
    expect(resolveMonthKey('2026-06-01', '2026-06-30')).toBe('2026-06');
  });

  it('retorna "custom" quando range é intervalo arbitrário', () => {
    expect(resolveMonthKey('2026-06-15', '2026-07-15')).toBe('custom');
  });

  it('retorna "" quando startDate ou endDate é null', () => {
    expect(resolveMonthKey(null, '2026-06-30')).toBe('');
    expect(resolveMonthKey('2026-06-01', null)).toBe('');
  });
});

describe('monthBounds', () => {
  it('retorna primeiro e último dia de junho de 2026', () => {
    const bounds = monthBounds('2026-06');
    expect(bounds.startDate).toBe('2026-06-01');
    expect(bounds.endDate).toBe('2026-06-30');
  });

  it('retorna último dia correto para fevereiro 2026 (não-bissexto)', () => {
    const bounds = monthBounds('2026-02');
    expect(bounds.endDate).toBe('2026-02-28');
  });

  it('retorna último dia correto para fevereiro 2024 (bissexto)', () => {
    const bounds = monthBounds('2024-02');
    expect(bounds.endDate).toBe('2024-02-29');
  });
});

describe('buildDisplayRows com groupByPeriod', () => {
  it('insere uma linha period-header antes das transações de cada mês', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-06-10', type: 'INCOME',  amount: 500,  status: 'PAID' },
      { id: '2', installmentGroupId: null, date: '2026-05-05', type: 'EXPENSE', amount: 100,  status: 'PAID' },
    ];
    const rows = buildDisplayRows(txs, new Set(), true);
    expect(rows[0].kind).toBe('period-header');
    expect((rows[0] as any).key).toBe('2026-06');
    expect(rows[1].kind).not.toBe('period-header');
    expect(rows[2].kind).toBe('period-header');
    expect((rows[2] as any).key).toBe('2026-05');
  });

  it('sem groupByPeriod não insere period-header', () => {
    const txs: any[] = [
      { id: '1', installmentGroupId: null, date: '2026-06-10', type: 'INCOME', amount: 500, status: 'PAID' },
    ];
    const rows = buildDisplayRows(txs, new Set());
    expect(rows.every(r => r.kind !== 'period-header')).toBe(true);
  });
});

describe('computeMonthChipStates', () => {
  it('retorna exatamente 12 chips para o ano informado', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    expect(chips).toHaveLength(12);
  });

  it('labels são abreviações pt-BR: Jan, Fev, Mar...', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    expect(chips[0].label).toBe('Jan');
    expect(chips[1].label).toBe('Fev');
    expect(chips[5].label).toBe('Jun');
    expect(chips[11].label).toBe('Dez');
  });

  it('keys são no formato YYYY-MM', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    expect(chips[0].key).toBe('2026-01');
    expect(chips[5].key).toBe('2026-06');
    expect(chips[11].key).toBe('2026-12');
  });

  it('meses futuros (> nowMonth) ficam disabled', () => {
    const chips = computeMonthChipStates(2026, 6, null, null); // nowMonth = 6 = junho
    expect(chips[5].disabled).toBe(false); // junho: não desabilitado
    expect(chips[6].disabled).toBe(true);  // julho: desabilitado
    expect(chips[11].disabled).toBe(true); // dezembro: desabilitado
  });

  it('meses passados e o mês atual não ficam disabled', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    chips.slice(0, 6).forEach(c => expect(c.disabled).toBe(false));
  });

  it('chip do mês que coincide com startDate/endDate fica active', () => {
    const chips = computeMonthChipStates(2026, 6, '2026-06-01', '2026-06-30');
    expect(chips[5].active).toBe(true);  // junho: ativo
    expect(chips[4].active).toBe(false); // maio: inativo
  });

  it('sem datas selecionadas: nenhum chip fica active', () => {
    const chips = computeMonthChipStates(2026, 6, null, null);
    expect(chips.every(c => !c.active)).toBe(true);
  });

  it('intervalo personalizado (não exato de mês): nenhum chip fica active', () => {
    const chips = computeMonthChipStates(2026, 6, '2026-06-05', '2026-06-20');
    expect(chips.every(c => !c.active)).toBe(true);
  });
});
