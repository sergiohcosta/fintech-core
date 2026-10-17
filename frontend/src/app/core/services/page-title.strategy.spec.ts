import { describe, it, expect, vi, beforeEach } from 'vitest';
import { RouterStateSnapshot } from '@angular/router';
import { Title } from '@angular/platform-browser';
import { PageTitleStrategy } from './page-title.strategy';

describe('PageTitleStrategy', () => {
  let title: Title;
  let strategy: PageTitleStrategy;

  beforeEach(() => {
    title = { setTitle: vi.fn() } as unknown as Title;
    strategy = new PageTitleStrategy(title);
  });

  it('prefixa o título da rota com o nome do app', () => {
    vi.spyOn(strategy, 'buildTitle').mockReturnValue('Minhas Contas');

    strategy.updateTitle({} as RouterStateSnapshot);

    expect(title.setTitle).toHaveBeenCalledWith('Nosso Dinheirinho - Minhas Contas');
  });

  it('usa o fallback quando a rota não tem título', () => {
    vi.spyOn(strategy, 'buildTitle').mockReturnValue(undefined);

    strategy.updateTitle({} as RouterStateSnapshot);

    expect(title.setTitle).toHaveBeenCalledWith('Nosso Dinheirinho');
  });
});
