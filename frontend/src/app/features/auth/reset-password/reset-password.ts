import { Component, inject, signal, OnInit } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../../core/services/auth';

type PageState = 'enter-token' | 'form' | 'submitting' | 'success';

// Validator de nível de FormGroup: as duas senhas precisam ser iguais.
function passwordsMatchValidator(control: AbstractControl): ValidationErrors | null {
  const password = control.get('password')?.value;
  const confirmPassword = control.get('confirmPassword')?.value;
  return password === confirmPassword ? null : { passwordsMismatch: true };
}

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
  ],
  templateUrl: './reset-password.html',
  styleUrl: './reset-password.scss',
})
export class ResetPasswordComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);

  private token: string | null = null;

  state = signal<PageState>('form');
  errorMessage = signal('');

  form = this.fb.group(
    {
      password: ['', [
        Validators.required,
        Validators.minLength(8),
        Validators.maxLength(72),
        Validators.pattern(/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/),
      ]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: passwordsMatchValidator },
  );

  // Fallback quando o link do email não abre (rede sem acesso ao host do frontend —
  // o email sempre aponta pra um único host, não dá pra cobrir todo acesso possível).
  // O token em si é portável entre hosts, só o link não é.
  tokenForm = this.fb.group({
    manualToken: ['', [Validators.required]],
  });

  ngOnInit(): void {
    const queryToken = this.route.snapshot.queryParamMap.get('token');
    if (queryToken) {
      this.token = queryToken;
      this.state.set('form');
    } else {
      this.state.set('enter-token');
    }
  }

  confirmManualToken(): void {
    if (this.tokenForm.invalid) return;
    this.token = this.tokenForm.value.manualToken!.trim();
    this.state.set('form');
  }

  onSubmit(): void {
    if (this.form.invalid || !this.token) return;

    this.state.set('submitting');
    this.auth.resetPassword(this.token, this.form.value.password!).subscribe({
      next: () => {
        this.state.set('success');
      },
      error: (err) => {
        this.errorMessage.set(
          err.status === 400 ? 'Token inválido ou expirado. Solicite um novo.' : 'Erro ao redefinir senha.',
        );
        this.state.set('form');
      },
    });
  }

  goToLogin(): void {
    this.router.navigate(['/login']);
  }
}
