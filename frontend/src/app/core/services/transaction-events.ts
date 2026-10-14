import { Injectable, signal } from '@angular/core';

// Um contador simples permite que telas reativas atualizem suas consultas após uma gravação,
// sem acoplar o formulário rápido ao Dashboard nem provocar navegação artificial.
@Injectable({ providedIn: 'root' })
export class TransactionEventsService {
  readonly refreshTrigger = signal(0);

  notifyTransactionCreated(): void {
    this.refreshTrigger.update((value) => value + 1);
  }
}
