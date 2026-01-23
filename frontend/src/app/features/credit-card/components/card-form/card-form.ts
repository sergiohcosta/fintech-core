import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { CreditCardService } from '../../../../core/services/credit-card';
import { CreditCardModel } from '../../../../core/models/credit-card';
import { BRAND_OPTIONS } from '../../../../core/models/brand.enum';

@Component({
  selector: 'app-card-form',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatSelectModule,
    MatIconModule,
    MatSnackBarModule,
    RouterLink
  ],
  templateUrl: './card-form.html',
  styleUrl: './card-form.scss'
})
export class CardFormComponent implements OnInit {

  private fb = inject(FormBuilder);
  private service = inject(CreditCardService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private snackBar = inject(MatSnackBar);

  form!: FormGroup;
  isEditMode = signal(false);
  cardId = signal<string | null>(null);

  // Opções para o Select (poderia vir de um Enum ou API)
  brands = BRAND_OPTIONS;

  ngOnInit(): void {
    this.initForm();
    this.checkEditMode();
  }

  private initForm() {
    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(3)]],
      brand: ['', Validators.required],
      color: ['#000000', Validators.required], // Valor padrão preto
      lastFourDigits: ['', [Validators.required, Validators.pattern(/^[0-9]{4}$/)]],
      limitAmount: [0, [Validators.required, Validators.min(0.01)]],
      closingDay: [1, [Validators.required, Validators.min(1), Validators.max(31)]],
      dueDay: [10, [Validators.required, Validators.min(1), Validators.max(31)]]
    });
  }

  private checkEditMode() {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.isEditMode.set(true);
      this.cardId.set(id);

      this.service.getById(id).subscribe({
        next: (card) => {
          this.form.patchValue(card);
        },
        error: () => {
          this.showError('Erro ao carregar cartão.');
          this.router.navigate(['/credit-cards']);
        }
      });
    }
  }

  onSubmit() {
    if (this.form.invalid) return;

    const card = this.form.value;

    if (this.isEditMode() && this.cardId()) {
      // Edição
      this.service.update(this.cardId()!, card).subscribe({
        next: () => this.onSuccess('Cartão atualizado com sucesso!'),
        error: () => this.showError('Erro ao atualizar cartão.')
      });
    } else {
      // Criação
      this.service.create(card).subscribe({
        next: () => this.onSuccess('Cartão criado com sucesso!'),
        error: () => this.showError('Erro ao criar cartão.')
      });
    }
  }

  private onSuccess(msg: string) {
    this.snackBar.open(msg, 'OK', { duration: 3000 });
    this.router.navigate(['/credit-cards']);
  }

  private showError(msg: string) {
    this.snackBar.open(msg, 'Fechar', { duration: 5000, panelClass: ['error-snackbar'] });
  }
}