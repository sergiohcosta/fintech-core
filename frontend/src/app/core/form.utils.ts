import { computed, Injector, Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl } from '@angular/forms';

export interface FormSignalOptions {
  injector?: Injector;
}

/**
 * Converte a invalidade de um AbstractControl (FormGroup, FormControl, FormArray)
 * em um Signal booleano reativo.
 *
 * Em Zoneless change detection, control.invalid é uma propriedade comum que não notifica
 * o framework sobre atualizações. Esta função conecta control.statusChanges a um Signal
 * com initialValue baseado no status síncrono atual.
 */
export function formInvalidSignal(
  control: AbstractControl,
  options?: FormSignalOptions,
): Signal<boolean> {
  const status = toSignal(control.statusChanges, {
    initialValue: control.status,
    injector: options?.injector,
  });
  return computed(() => status() === 'INVALID');
}

/**
 * Converte a validade de um AbstractControl em um Signal booleano reativo.
 */
export function formValidSignal(
  control: AbstractControl,
  options?: FormSignalOptions,
): Signal<boolean> {
  const status = toSignal(control.statusChanges, {
    initialValue: control.status,
    injector: options?.injector,
  });
  return computed(() => status() === 'VALID');
}
