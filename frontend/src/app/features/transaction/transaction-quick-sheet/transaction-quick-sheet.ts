import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, ViewChild, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatBottomSheetRef } from '@angular/material/bottom-sheet';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelect, MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { finalize, Observable } from 'rxjs';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import { AccountResponse, CategoryResponseDTO, TransactionRequestDTO } from '../../../core/api/fintechSaaSAPI.schemas';
import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
import { TransactionEventsService } from '../../../core/services/transaction-events';
import { formatLocalDate } from '../transaction-form/transaction-form.utils';

type QuickEntryType = 'INCOME' | 'EXPENSE' | 'TRANSFER';

interface QuickEntryCategory {
  id: string;
  name: string;
  level: number;
}

// A sheet reaproveita os serviços oficiais, mas mantém o cadastro curto; transferências
// usam o endpoint próprio porque a API não as representa como uma transação comum.
@Component({
  selector: 'app-transaction-quick-sheet',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
  ],
  templateUrl: './transaction-quick-sheet.html',
  styleUrl: './transaction-quick-sheet.scss',
})
export class TransactionQuickSheetComponent {
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly accountService = inject(AccountsService);
  private readonly categoryService = inject(CategoriesService);
  private readonly transactionService = inject(TransactionsService);
  private readonly transferService = inject(TransfersService);
  private readonly transactionEvents = inject(TransactionEventsService);
  private readonly bottomSheetRef = inject(MatBottomSheetRef<TransactionQuickSheetComponent>);
  private readonly snackBar = inject(MatSnackBar);

  readonly accounts = signal<AccountResponse[]>([]);
  private readonly categories = signal<QuickEntryCategory[]>([]);
  readonly saving = signal(false);

  readonly form = this.fb.nonNullable.group({
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    type: ['EXPENSE' as QuickEntryType, Validators.required],
    accountId: ['', Validators.required],
    destinationAccountId: [''],
    categoryId: ['', Validators.required],
    description: ['', Validators.maxLength(200)],
  }, { validators: this.differentAccountsValidator });

  readonly selectedType = toSignal(this.form.controls.type.valueChanges, {
    initialValue: this.form.controls.type.value,
  });
  readonly isTransfer = computed(() => this.selectedType() === 'TRANSFER');
  readonly isDragging = signal(false);
  readonly dragOffset = signal(0);
  readonly availableCategories = computed(() =>
    this.isTransfer() ? [] : this.categories(),
  );

  private dragStartY: number | null = null;
  private activePointerId: number | null = null;

  @ViewChild('amountInput') private amountInput?: ElementRef<HTMLInputElement>;
  @ViewChild('accountSelect') private accountSelect?: MatSelect;
  @ViewChild('destinationAccountSelect') private destinationAccountSelect?: MatSelect;
  @ViewChild('categorySelect') private categorySelect?: MatSelect;
  @ViewChild('descriptionInput') private descriptionInput?: ElementRef<HTMLInputElement>;
  @ViewChild('typeToggle', { read: ElementRef }) private typeToggle?: ElementRef<HTMLElement>;

  constructor() {
    this.accountService.listAccounts().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (accounts) => this.accounts.set(accounts.filter((account) => account.active)),
      error: () => this.snackBar.open('Não foi possível carregar as contas.', 'OK', { duration: 4000 }),
    });
    this.categoryService.listCategories().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (categories) => this.categories.set(this.flattenCategories(categories)),
      error: () => this.snackBar.open('Não foi possível carregar as categorias.', 'OK', { duration: 4000 }),
    });

    this.form.controls.type.valueChanges
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((type) => this.updateTypeFields(type));
  }

  close(): void {
    this.bottomSheetRef.dismiss();
  }

  // O Material não expõe swipe-to-dismiss; o handle isolado permite arrastar sem bloquear o scroll do formulário.
  startDrag(event: PointerEvent): void {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    this.dragStartY = event.clientY;
    this.activePointerId = event.pointerId;
    this.isDragging.set(true);
    if (event.currentTarget instanceof HTMLElement) {
      event.currentTarget.setPointerCapture(event.pointerId);
    }
  }

  moveDrag(event: PointerEvent): void {
    if (event.pointerId !== this.activePointerId || this.dragStartY === null) return;
    this.dragOffset.set(Math.max(0, event.clientY - this.dragStartY));
  }

  endDrag(event: PointerEvent): void {
    if (event.pointerId !== this.activePointerId || this.dragStartY === null) return;
    const shouldDismiss = event.clientY - this.dragStartY > 96;
    this.resetDrag();
    if (shouldDismiss) this.close();
  }

  cancelDrag(): void {
    this.resetDrag();
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.focusFirstInvalidField();
      return;
    }

    const value = this.form.getRawValue();
    const date = formatLocalDate(new Date());
    let request: Observable<unknown>;
    if (value.type === 'TRANSFER') {
      request = this.transferService.createTransfer({
          fromAccountId: value.accountId,
          toAccountId: value.destinationAccountId,
          amount: value.amount!,
          date,
          description: value.description.trim() || undefined,
        });
    } else {
      request = this.transactionService.createTransaction({
          description: value.description.trim() || 'Lançamento rápido',
          amount: value.amount!,
          date,
          type: value.type,
          accountId: value.accountId,
          categoryId: value.categoryId || undefined,
          totalInstallments: 1,
        } satisfies TransactionRequestDTO);
    }

    this.saving.set(true);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: () => {
        this.transactionEvents.notifyTransactionCreated();
        this.bottomSheetRef.dismiss();
        this.snackBar.open(
          this.isTransfer() ? 'Transferência registrada' : 'Transação lançada',
          'OK',
          { duration: 3000 },
        );
      },
      error: (error: unknown) => {
        this.snackBar.open(this.errorMessage(error), 'OK', { duration: 5000 });
        const field = this.validationField(error);
        if (field) queueMicrotask(() => this.focusField(field));
      },
    });
  }

  private updateTypeFields(type: QuickEntryType): void {
    const category = this.form.controls.categoryId;
    const destination = this.form.controls.destinationAccountId;

    // shortcut: a API não classifica categorias por tipo, então INCOME/EXPENSE compartilham
    // opções ativas; filtre por tipo quando o contrato incluir essa propriedade.
    category.reset('', { emitEvent: false });
    if (type === 'TRANSFER') {
      category.clearValidators();
      category.disable({ emitEvent: false });
      destination.setValidators(Validators.required);
    } else {
      category.enable({ emitEvent: false });
      category.setValidators(Validators.required);
      destination.clearValidators();
      destination.reset('', { emitEvent: false });
    }
    category.updateValueAndValidity({ emitEvent: false });
    destination.updateValueAndValidity({ emitEvent: false });
  }

  private resetDrag(): void {
    this.dragStartY = null;
    this.activePointerId = null;
    this.isDragging.set(false);
    this.dragOffset.set(0);
  }

  private flattenCategories(categories: CategoryResponseDTO[], level = 0): QuickEntryCategory[] {
    return categories.flatMap((category) => [
      ...(!category.archived ? [{ id: category.id, name: category.name, level }] : []),
      ...this.flattenCategories(category.children ?? [], level + 1),
    ]);
  }

  private focusFirstInvalidField(): void {
    if (this.form.hasError('sameAccount')) {
      this.destinationAccountSelect?.focus();
      return;
    }

    const fields = this.isTransfer()
      ? ['amount', 'type', 'accountId', 'destinationAccountId'] as const
      : ['amount', 'type', 'accountId', 'categoryId'] as const;
    const field = fields.find((name) => this.form.controls[name].invalid);
    if (field) this.focusField(field);
  }

  private focusField(field: string): void {
    switch (field) {
      case 'amount': this.amountInput?.nativeElement.focus(); break;
      case 'type': this.typeToggle?.nativeElement.querySelector('button')?.focus(); break;
      case 'accountId':
      case 'fromAccountId': this.accountSelect?.focus(); break;
      case 'destinationAccountId':
      case 'toAccountId': this.destinationAccountSelect?.focus(); break;
      case 'categoryId': this.categorySelect?.focus(); break;
      case 'description': this.descriptionInput?.nativeElement.focus(); break;
      default: break;
    }
  }

  private validationField(error: unknown): string | null {
    if (!(error instanceof HttpErrorResponse)) return null;
    const body: unknown = error.error;
    if (typeof body !== 'object' || body === null || !('details' in body)) return null;
    const details: unknown = body.details;
    if (typeof details !== 'object' || details === null) return null;
    return Object.keys(details)[0] ?? null;
  }

  private errorMessage(error: unknown): string {
    if (!(error instanceof HttpErrorResponse)) return 'Não foi possível lançar. Tente novamente.';
    if (error.status === 0) return 'Sem conexão — tente novamente.';
    const body: unknown = error.error;
    if (typeof body === 'object' && body !== null && 'message' in body &&
        typeof body.message === 'string' && body.message.trim()) {
      return body.message;
    }
    return 'Não foi possível lançar. Tente novamente.';
  }

  private differentAccountsValidator(group: AbstractControl<unknown>): ValidationErrors | null {
    const source = group.get('accountId')?.value;
    const destination = group.get('destinationAccountId')?.value;
    return typeof source === 'string' && source !== '' &&
      typeof destination === 'string' && destination !== '' && source === destination
      ? { sameAccount: true }
      : null;
  }
}
