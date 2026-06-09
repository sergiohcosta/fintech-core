import { Component, inject, OnInit, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { AccountsService } from '../../../core/api/accounts/accounts.service';
import { AccountCreateRequest, AccountUpdateRequest } from '../../../core/api/fintechSaaSAPI.schemas';
import { IconPicker } from '../../../components/icon-picker/icon-picker';

@Component({
  selector: 'app-account-form',
  standalone: true,
  imports: [
    CommonModule, RouterLink, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatSnackBarModule,
    IconPicker
  ],
  templateUrl: './account-form.html',
  styleUrl: './account-form.scss'
})
export class AccountForm implements OnInit {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private service = inject(AccountsService);
  private snackBar = inject(MatSnackBar);

  saving = signal(false);
  isEditMode = signal(false);
  accountId = signal<string | null>(null);
  selectedIconSignal = signal('account_balance');
  limitDisplay = signal('');

  readonly accountTypes = [
    { value: 'CHECKING',     label: 'Conta Corrente' },
    { value: 'INVESTMENT',   label: 'Investimento' },
    { value: 'CREDIT_CARD',  label: 'Cartão de Crédito' },
    { value: 'CASH',         label: 'Carteira Física' }
  ];

  readonly cardBrands = ['VISA', 'MASTERCARD', 'ELO', 'AMEX', 'HIPERCARD', 'OTHER'];

  form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    type: ['CHECKING', Validators.required],
    color: [''],
    icon: ['account_balance'],
    countInLiquidBalance: [true],
    countInNetWorth: [true],
    brand: [''],
    lastFourDigits: ['', [Validators.minLength(4), Validators.maxLength(4)]],
    limitAmount: [null as number | null],
    closingDay: [null as number | null, [Validators.min(1), Validators.max(31)]],
    dueDay: [null as number | null, [Validators.min(1), Validators.max(31)]]
  });

  // toSignal converte o Observable de mudanças do campo em um Signal — correto para Zoneless
  private typeValue = toSignal(this.form.get('type')!.valueChanges, { initialValue: 'CHECKING' });
  isCreditCard = computed(() => this.typeValue() === 'CREDIT_CARD');

  ngOnInit(): void {
    // Em modo criação, ajusta countInLiquidBalance ao trocar o tipo.
    // Guard de isEditMode() evita sobrescrever o valor carregado do banco quando patchValue dispara valueChanges.
    this.form.get('type')!.valueChanges.subscribe(type => {
      if (this.isEditMode()) return;
      const liquid = type === 'CHECKING' || type === 'CASH';
      this.form.patchValue({ countInLiquidBalance: liquid }, { emitEvent: false });
    });

    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.accountId.set(id);
      this.service.getAccount(id).subscribe({
        next: (a) => {
          this.form.patchValue({
            name: a.name, type: a.type, color: a.color ?? '', icon: a.icon ?? '',
            countInLiquidBalance: a.countInLiquidBalance, countInNetWorth: a.countInNetWorth,
            brand: a.creditCardDetails?.brand ?? '',
            lastFourDigits: a.creditCardDetails?.lastFourDigits ?? '',
            limitAmount: a.creditCardDetails?.limitAmount ?? null,
            closingDay: a.creditCardDetails?.closingDay ?? null,
            dueDay: a.creditCardDetails?.dueDay ?? null
          });
          this.selectedIconSignal.set(a.icon ?? 'account_balance');
          if (a.creditCardDetails?.limitAmount != null) {
            this.limitDisplay.set(this.formatCurrency(a.creditCardDetails.limitAmount));
          }
          // Tipo não pode mudar após criação; desabilita o select para comunicar isso ao usuário.
          this.form.get('type')!.disable({ emitEvent: false });
        },
        error: () => {
          this.snackBar.open('Conta não encontrada.', 'Fechar', { duration: 5000 });
          this.router.navigate(['/accounts']);
        }
      });
    }
  }

  onIconSelected(icon: string): void {
    this.form.patchValue({ icon });
    this.selectedIconSignal.set(icon);
  }

  private formatCurrency(value: number): string {
    return new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value);
  }

  onLimitInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const cleaned = input.value.replace(/[^\d,]/g, '');
    const asFloat = parseFloat(cleaned.replace(',', '.'));
    this.form.controls.limitAmount.setValue(isNaN(asFloat) ? null : asFloat);
    this.form.controls.limitAmount.markAsTouched();
  }

  onLimitBlur(event: Event): void {
    const val = this.form.controls.limitAmount.value;
    const input = event.target as HTMLInputElement;
    if (val !== null && val !== undefined) {
      const formatted = this.formatCurrency(val);
      input.value = formatted;
      this.limitDisplay.set(formatted);
    } else {
      this.limitDisplay.set('');
    }
  }

  onLimitFocus(event: Event): void {
    const val = this.form.controls.limitAmount.value;
    const input = event.target as HTMLInputElement;
    input.value = val !== null && val !== undefined ? String(val).replace('.', ',') : '';
  }

  onSubmit(): void {
    if (this.form.invalid) return;
    const raw = this.form.getRawValue();
    this.saving.set(true);

    const creditCardDetails = raw.type === 'CREDIT_CARD' ? {
      brand: raw.brand as any || undefined,
      lastFourDigits: raw.lastFourDigits || undefined,
      limitAmount: raw.limitAmount ?? undefined,
      closingDay: raw.closingDay ?? undefined,
      dueDay: raw.dueDay ?? undefined
    } : undefined;

    const obs$ = this.isEditMode()
      ? this.service.updateAccount(this.accountId()!, {
          name: raw.name || undefined,
          color: raw.color || undefined,
          icon: raw.icon || undefined,
          countInLiquidBalance: raw.countInLiquidBalance ?? undefined,
          countInNetWorth: raw.countInNetWorth ?? undefined,
          creditCardDetails
        } satisfies AccountUpdateRequest)
      : this.service.createAccount({
          name: raw.name!,
          type: raw.type as AccountCreateRequest['type'],
          color: raw.color || undefined,
          icon: raw.icon || undefined,
          countInLiquidBalance: raw.countInLiquidBalance ?? undefined,
          countInNetWorth: raw.countInNetWorth ?? undefined,
          creditCardDetails
        } satisfies AccountCreateRequest);

    obs$.subscribe({
      next: () => {
        this.snackBar.open(
          this.isEditMode() ? 'Conta atualizada!' : 'Conta criada!', 'OK', { duration: 3000 });
        this.router.navigate(['/accounts']);
      },
      error: () => {
        this.saving.set(false);
        this.snackBar.open('Erro ao salvar conta.', 'Fechar', { duration: 5000 });
      }
    });
  }
}
