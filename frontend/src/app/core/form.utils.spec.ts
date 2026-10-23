import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, Validators } from '@angular/forms';
import { provideZonelessChangeDetection } from '@angular/core';
import { formInvalidSignal, formValidSignal } from './form.utils';

describe('form.utils', () => {
  beforeEach(() => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideZonelessChangeDetection()],
    });
  });

  describe('formInvalidSignal', () => {
    it('retorna true inicialmente quando o controle é inválido', () => {
      TestBed.runInInjectionContext(() => {
        const control = new FormControl('', Validators.required);
        const isInvalid = formInvalidSignal(control);

        expect(isInvalid()).toBe(true);
      });
    });

    it('retorna false inicialmente quando o controle é válido', () => {
      TestBed.runInInjectionContext(() => {
        const control = new FormControl('preenchido', Validators.required);
        const isInvalid = formInvalidSignal(control);

        expect(isInvalid()).toBe(false);
      });
    });

    it('atualiza reativamente quando o valor e status mudam', () => {
      TestBed.runInInjectionContext(() => {
        const control = new FormControl('', Validators.required);
        const isInvalid = formInvalidSignal(control);

        expect(isInvalid()).toBe(true);

        control.setValue('novo valor');
        expect(isInvalid()).toBe(false);

        control.setValue('');
        expect(isInvalid()).toBe(true);
      });
    });

    it('funciona com FormGroup e validações múltiplas', () => {
      TestBed.runInInjectionContext(() => {
        const group = new FormGroup({
          name: new FormControl('', Validators.required),
          amount: new FormControl(0, [Validators.required, Validators.min(1)]),
        });
        const isInvalid = formInvalidSignal(group);

        expect(isInvalid()).toBe(true);

        group.patchValue({ name: 'Compra' });
        expect(isInvalid()).toBe(true);

        group.patchValue({ amount: 50 });
        expect(isInvalid()).toBe(false);
      });
    });
  });

  describe('formValidSignal', () => {
    it('retorna false inicialmente para controle inválido e true quando válido', () => {
      TestBed.runInInjectionContext(() => {
        const control = new FormControl('', Validators.required);
        const isValid = formValidSignal(control);

        expect(isValid()).toBe(false);

        control.setValue('ok');
        expect(isValid()).toBe(true);
      });
    });
  });
});
