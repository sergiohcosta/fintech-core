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
import { AccountCreateRequest } from '../../../core/api/fintechSaaSAPI.schemas';

@Component({
  selector: 'app-account-form',
  standalone: true,
  imports: [
    CommonModule, RouterLink, ReactiveFormsModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatSlideToggleModule, MatSnackBarModule
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
    icon: [''],
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
    // Atualiza flags de liquidez automaticamente quando o tipo muda
    this.form.get('type')!.valueChanges.subscribe(type => {
      const liquid = type === 'CHECKING' || type === 'CASH';
      this.form.patchValue({ countInLiquidBalance: liquid }, { emitEvent: false });
    });

    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.accountId.set(id);
      this.service.getAccount(id).subscribe({
        next: (a) => this.form.patchValue({
          name: a.name, type: a.type, color: a.color ?? '', icon: a.icon ?? '',
          countInLiquidBalance: a.countInLiquidBalance, countInNetWorth: a.countInNetWorth,
          brand: a.creditCardDetails?.brand ?? '',
          lastFourDigits: a.creditCardDetails?.lastFourDigits ?? '',
          limitAmount: a.creditCardDetails?.limitAmount ?? null,
          closingDay: a.creditCardDetails?.closingDay ?? null,
          dueDay: a.creditCardDetails?.dueDay ?? null
        }),
        error: () => {
          this.snackBar.open('Conta não encontrada.', 'Fechar', { duration: 5000 });
          this.router.navigate(['/accounts']);
        }
      });
    }
  }

  onSubmit(): void {
    if (this.form.invalid) return;
    const raw = this.form.getRawValue();
    this.saving.set(true);

    const payload: AccountCreateRequest = {
      name: raw.name!,
      type: raw.type as AccountCreateRequest['type'],
      color: raw.color || undefined,
      icon: raw.icon || undefined,
      countInLiquidBalance: raw.countInLiquidBalance ?? undefined,
      countInNetWorth: raw.countInNetWorth ?? undefined,
      creditCardDetails: raw.type === 'CREDIT_CARD' ? {
        brand: raw.brand as any || undefined,
        lastFourDigits: raw.lastFourDigits || undefined,
        limitAmount: raw.limitAmount ?? undefined,
        closingDay: raw.closingDay ?? undefined,
        dueDay: raw.dueDay ?? undefined
      } : undefined
    };

    const obs$ = this.isEditMode()
      ? this.service.updateAccount(this.accountId()!, payload)
      : this.service.createAccount(payload);

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
