import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { AccountForm } from './account-form';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

describe('AccountForm', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AccountForm, NoopAnimationsModule],
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
  });

  it('formulário inicia inválido (name e type são required)', () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ name: '' });
    expect(fixture.componentInstance.form.invalid).toBe(true);
  });

  it('formulário válido com name e type preenchidos', () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ name: 'Bradesco', type: 'CHECKING' });
    expect(fixture.componentInstance.form.valid).toBe(true);
  });

  it('isCreditCard retorna true quando type = CREDIT_CARD', async () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ type: 'CREDIT_CARD' });
    await fixture.whenStable();
    expect(fixture.componentInstance.isCreditCard()).toBe(true);
  });

  it('isCreditCard retorna false quando type = CHECKING', async () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ type: 'CHECKING' });
    await fixture.whenStable();
    expect(fixture.componentInstance.isCreditCard()).toBe(false);
  });

  it('ao mudar type para INVESTMENT, countInLiquidBalance vira false automaticamente', async () => {
    const fixture = TestBed.createComponent(AccountForm);
    fixture.detectChanges();
    fixture.componentInstance.form.patchValue({ type: 'INVESTMENT' });
    await fixture.whenStable();
    expect(fixture.componentInstance.form.get('countInLiquidBalance')?.value).toBe(false);
  });
});
