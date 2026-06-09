import { Component, ElementRef, inject, OnInit, signal, computed, effect, ViewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Observable, finalize } from 'rxjs';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatDatepicker, MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatRadioModule } from '@angular/material/radio';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { TransactionsService } from '../../../core/api/transactions/transactions.service';
import { TransfersService } from '../../../core/api/transfers/transfers.service';
import { CategoriesService } from '../../../core/api/categories/categories.service';
import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { CategoryResponseDTO, AccountResponse } from '../../../core/api/fintechSaaSAPI.schemas';
import { buildInstallmentPreview, CreditCardPreviewConfig } from './installment-preview';
export { buildInstallmentPreview } from './installment-preview';
export type { InstallmentPreviewRow, CreditCardPreviewConfig } from './installment-preview';

interface TransactionCategoryOption {
  id: string;
  name: string;
  level: number;
  archived: boolean;
}

@Component({
  selector: 'app-transaction-form',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatDatepickerModule,
    MatNativeDateModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatSlideToggleModule,
    MatRadioModule,
    MatCheckboxModule,
    MatDividerModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './transaction-form.html',
  styleUrl: './transaction-form.scss'
})
export class TransactionForm implements OnInit {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private transactionService = inject(TransactionsService);
  private transferService = inject(TransfersService);
  private categoryService = inject(CategoriesService);
  private accountService = inject(AccountsService);
  private snackBar = inject(MatSnackBar);

  @ViewChild('picker') private picker?: MatDatepicker<Date>;
  @ViewChild('transferPicker') private transferPicker?: MatDatepicker<Date>;
  @ViewChild('descriptionInput') private descriptionInput?: ElementRef<HTMLInputElement>;

  saving = signal(false);
  isEditMode = signal(false);
  isPartOfInstallment = signal(false);
  transactionId = signal<string | null>(null);
  categories = signal<TransactionCategoryOption[]>([]);
  accounts = signal<AccountResponse[]>([]);
  amountDisplay = signal('');
  mode = signal<'TRANSACTION' | 'TRANSFER'>('TRANSACTION');
  isInstallment = signal(false);
  valueMode = signal<'total' | 'per-installment'>('total');
  propagateFields = signal<Set<string>>(new Set());

  form = this.fb.group({
    description: ['', [Validators.required, Validators.minLength(2)]],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    date: [new Date(), Validators.required],
    type: ['EXPENSE', Validators.required],
    status: ['PENDING'],
    totalInstallments: [1, [Validators.min(1), Validators.max(48)]],
    categoryId: [null as string | null],
    accountId: [null as string | null, Validators.required],
    fromAccountId: [null as string | null],
    toAccountId: [null as string | null]
  }, { validators: this.differentAccountsValidator });

  // Sinais derivados dos FormControls para reatividade em Zoneless
  private amountSignal = toSignal(this.form.controls.amount.valueChanges, { initialValue: this.form.controls.amount.value ?? 0 });
  private installmentsSignal = toSignal(this.form.controls.totalInstallments.valueChanges, { initialValue: this.form.controls.totalInstallments.value ?? 1 });
  private accountIdSignal = toSignal(this.form.controls.accountId.valueChanges, { initialValue: this.form.controls.accountId.value });
  private typeSignal = toSignal(this.form.controls.type.valueChanges, { initialValue: this.form.controls.type.value ?? 'EXPENSE' });

  // Validade do formulário como Signal: statusChanges é o canal reativo do ReactiveFormsModule.
  // Em Zoneless, form.invalid é uma propriedade comum — não dispara CD. Ao convertê-la em Signal,
  // qualquer mudança de status agenda um tick automaticamente, garantindo que [disabled] seja atualizado.
  private formStatusSignal = toSignal(this.form.statusChanges, { initialValue: this.form.status });
  formValid = computed(() => this.formStatusSignal() === 'VALID');

  formCardClass = computed(() => ({
    'card-expense': this.mode() === 'TRANSACTION' && this.typeSignal() === 'EXPENSE',
    'card-income':  this.mode() === 'TRANSACTION' && this.typeSignal() === 'INCOME',
    'card-transfer': this.mode() === 'TRANSFER',
  }));

  isCreditCard = computed(() =>
    this.accounts().find(a => a.id === this.accountIdSignal())?.type === 'CREDIT_CARD'
  );

  // Quando o usuário troca para uma conta que não é cartão, limpa o estado de parcelamento
  private readonly _resetInstallmentOnAccountChange = effect(() => {
    if (!this.isCreditCard()) this.isInstallment.set(false);
  });

  installmentPreview = computed(() => {
    if (!this.isInstallment()) return [];
    const amount = this.amountSignal() ?? 0;
    const installments = this.installmentsSignal() ?? 1;
    const date = this.form.controls.date.value ?? new Date();

    const selectedAccount = this.accounts().find(a => a.id === this.accountIdSignal());
    const creditCard: CreditCardPreviewConfig | undefined =
      selectedAccount?.type === 'CREDIT_CARD' && selectedAccount.creditCardDetails
        ? { closingDay: selectedAccount.creditCardDetails.closingDay, dueDay: selectedAccount.creditCardDetails.dueDay }
        : undefined;

    return buildInstallmentPreview(amount, installments, date, this.valueMode(), creditCard);
  });

  ngOnInit(): void {
    this.categoryService.listCategories({ includeArchived: true }).subscribe({
      next: (data) => this.categories.set(this.flattenCategories(data)),
      error: () => {}
    });
    this.accountService.listAccounts().subscribe({
      next: (data) => this.accounts.set(data),
      error: () => {}
    });

    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.transactionId.set(id);
      this.transactionService.getTransaction(id).subscribe({
        next: (t) => {
          this.form.patchValue({
            description: t.description,
            amount: t.amount,
            date: new Date(t.date + 'T00:00:00'),
            type: t.type,
            status: t.status,
            categoryId: t.categoryId ?? null,
            accountId: t.accountId ?? null
          });
          this.amountDisplay.set(this.formatAmount(t.amount));
          this.isPartOfInstallment.set(!!t.installmentGroupId);
        },
        error: () => {
          this.snackBar.open('Transação não encontrada.', 'Fechar', { duration: 5000 });
          this.router.navigate(['/transactions']);
        }
      });
    }
  }

  onModeChange(mode: 'TRANSACTION' | 'TRANSFER'): void {
    this.mode.set(mode);
    const fromCtrl    = this.form.controls.fromAccountId;
    const toCtrl      = this.form.controls.toAccountId;
    const accountCtrl = this.form.controls.accountId;
    const descCtrl    = this.form.controls.description;

    if (mode === 'TRANSFER') {
      fromCtrl.setValidators(Validators.required);
      toCtrl.setValidators(Validators.required);
      accountCtrl.clearValidators();
      descCtrl.clearValidators();
    } else {
      fromCtrl.clearValidators();
      toCtrl.clearValidators();
      accountCtrl.setValidators(Validators.required);
      descCtrl.setValidators([Validators.required, Validators.minLength(2)]);
    }
    fromCtrl.updateValueAndValidity();
    toCtrl.updateValueAndValidity();
    accountCtrl.updateValueAndValidity();
    descCtrl.updateValueAndValidity();
  }

  togglePropagate(field: string): void {
    this.propagateFields.update(set => {
      const next = new Set(set);
      next.has(field) ? next.delete(field) : next.add(field);
      return next;
    });
  }

  isPropagating(field: string): boolean {
    return this.propagateFields().has(field);
  }

  // --- Máscara de moeda (#17) ---

  private formatAmount(value: number): string {
    return new Intl.NumberFormat('pt-BR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(value);
  }

  onAmountInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const cleaned = input.value.replace(/[^\d,]/g, '');
    const asFloat = parseFloat(cleaned.replace(',', '.'));
    this.form.controls.amount.setValue(isNaN(asFloat) ? null : asFloat);
    this.form.controls.amount.markAsTouched();
  }

  onAmountBlur(event: Event): void {
    const val = this.form.controls.amount.value;
    if (val !== null && val !== undefined) {
      const input = event.target as HTMLInputElement;
      const formatted = this.formatAmount(val);
      input.value = formatted;
      this.amountDisplay.set(formatted);
    }
  }

  onAmountFocus(event: Event): void {
    const val = this.form.controls.amount.value;
    const input = event.target as HTMLInputElement;
    input.value = val !== null && val !== undefined ? String(val).replace('.', ',') : '';
  }

  // --- Máscara de data (#18) ---

  onDateKeydown(event: KeyboardEvent): void {
    const PASS_THROUGH = ['Backspace', 'Delete', 'Tab', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Enter'];
    if (PASS_THROUGH.includes(event.key) || !(/^\d$/.test(event.key))) return;

    const input = event.target as HTMLInputElement;
    const val = input.value;
    if (val.length === 2 || val.length === 5) {
      input.value = val + '/';
    }
  }

  onDateBlur(event: FocusEvent): void {
    const relatedTarget = event.relatedTarget as HTMLElement | null;
    if (relatedTarget?.closest('mat-datepicker-toggle')) return;
    if (this.picker?.opened || this.transferPicker?.opened) return;

    const input = event.target as HTMLInputElement;
    const val = input.value;
    const match = val.match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
    if (match) {
      const [, day, month, year] = match;
      const date = new Date(+year, +month - 1, +day);
      if (!isNaN(date.getTime())) {
        this.form.controls.date.setValue(date);
      }
    }
  }

  // --- Utilitários ---

  private flattenCategories(cats: CategoryResponseDTO[], level = 0): TransactionCategoryOption[] {
    return cats.flatMap(c => [
      { id: c.id, name: c.name, level, archived: c.archived },
      ...this.flattenCategories(c.children ?? [], level + 1)
    ]);
  }

  getAccountIcon(type: string): string {
    const icons: Record<string, string> = {
      CHECKING: 'account_balance_wallet',
      CREDIT_CARD: 'credit_card',
      INVESTMENT: 'savings',
      CASH: 'payments',
    };
    return icons[type] ?? 'account_balance';
  }

  getAccountName(id: string | null): string {
    return this.accounts().find(a => a.id === id)?.name ?? '';
  }

  private toDateString(date: Date): string {
    return date.toISOString().split('T')[0];
  }

  private differentAccountsValidator(group: AbstractControl): ValidationErrors | null {
    const from = group.get('fromAccountId')?.value;
    const to = group.get('toAccountId')?.value;
    if (from && to && from === to) {
      return { sameAccount: true };
    }
    return null;
  }

  private doSave(): Observable<any> {
    const raw = this.form.getRawValue();

    if (this.isEditMode()) {
      return this.transactionService.updateTransaction(this.transactionId()!, {
        description: raw.description!,
        amount: raw.amount!,
        date: this.toDateString(raw.date as Date),
        type: raw.type as 'INCOME' | 'EXPENSE',
        status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
        categoryId: raw.categoryId ?? undefined,
        accountId: raw.accountId!,
        propagate: this.propagateFields().size > 0 ? Array.from(this.propagateFields()) : undefined
      });
    }

    if (this.mode() === 'TRANSFER') {
      return this.transferService.createTransfer({
        fromAccountId: raw.fromAccountId!,
        toAccountId: raw.toAccountId!,
        amount: raw.amount!,
        date: this.toDateString(raw.date as Date),
        description: raw.description || undefined
      });
    }

    const rawAmount = raw.amount!;
    const totalAmount = this.isInstallment() && this.valueMode() === 'per-installment'
      ? rawAmount * (raw.totalInstallments ?? 1)
      : rawAmount;

    return this.transactionService.createTransaction({
      description: raw.description!,
      amount: totalAmount,
      date: this.toDateString(raw.date as Date),
      type: raw.type as 'INCOME' | 'EXPENSE',
      status: raw.status as 'PENDING' | 'PAID' | 'CANCELLED' ?? undefined,
      categoryId: raw.categoryId ?? undefined,
      accountId: raw.accountId!,
      totalInstallments: this.isInstallment() ? (raw.totalInstallments ?? 1) : 1
    });
  }

  onSubmit(): void {
    if (!this.formValid()) return;
    this.saving.set(true);

    this.doSave().pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (result) => {
        let msg: string;
        if (this.isEditMode()) {
          msg = 'Transação atualizada com sucesso!';
        } else if (this.mode() === 'TRANSFER') {
          msg = 'Transferência registrada com sucesso!';
        } else {
          msg = Array.isArray(result) && result.length > 1
            ? `${result.length} parcelas criadas com sucesso!`
            : 'Transação criada com sucesso!';
        }
        this.snackBar.open(msg, 'OK', { duration: 3000 });
        this.router.navigate(['/transactions']);
      },
      error: () => {
        this.snackBar.open('Erro ao salvar transação.', 'Fechar', { duration: 5000 });
      }
    });
  }

  onSaveAndAddMore(): void {
    if (!this.formValid() || this.isEditMode()) return;
    this.saving.set(true);

    this.doSave().pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (result) => {
        const msg = Array.isArray(result) && result.length > 1
          ? `${result.length} parcelas criadas com sucesso!`
          : 'Transação criada com sucesso!';
        this.snackBar.open(msg, 'OK', { duration: 3000 });
        this.partialReset();
      },
      error: () => {
        this.snackBar.open('Erro ao salvar transação.', 'Fechar', { duration: 5000 });
      }
    });
  }

  private partialReset(): void {
    this.form.controls.description.reset();
    this.form.controls.amount.reset();
    this.form.controls.totalInstallments.reset(1);
    this.amountDisplay.set('');
    this.isInstallment.set(false);
    this.valueMode.set('total');
    this.propagateFields.set(new Set());
    // saving é resetado pelo finalize() da subscription — não aqui
    setTimeout(() => this.descriptionInput?.nativeElement.focus());
  }
}
