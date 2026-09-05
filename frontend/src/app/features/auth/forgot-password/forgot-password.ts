import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../../core/services/auth';

@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    RouterLink,
  ],
  templateUrl: './forgot-password.html',
  styleUrl: './forgot-password.scss',
})
export class ForgotPasswordComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);

  isLoading = signal(false);
  // Sempre a mesma mensagem, existindo ou não o email — o frontend não pode reintroduzir
  // a enumeração de usuários que o backend evita (resposta genérica do /auth/forgot-password).
  submitted = signal(false);

  form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
  });

  onSubmit(): void {
    if (this.form.invalid) return;

    this.isLoading.set(true);
    this.auth.forgotPassword(this.form.value.email!).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.submitted.set(true);
      },
      error: () => {
        // Mesmo em erro de rede/rate-limit, não revelamos detalhe — mensagem genérica.
        this.isLoading.set(false);
        this.submitted.set(true);
      },
    });
  }
}
