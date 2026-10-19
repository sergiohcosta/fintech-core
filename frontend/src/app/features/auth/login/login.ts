import { Component, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/services/auth';
import { BiometricAuthService } from '../../../core/services/biometric-auth.service';
import { TokenStorageService } from '../../../core/services/token-storage.service';
import { environment } from '../../../../environments/environment';
import { formatVersionLabel, formatVersionTooltip, readAppEnv } from '../../../core/app-env';
import { formInvalidSignal } from '../../../core/form.utils';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatButtonModule,
    RouterLink
  ],
  templateUrl: './login.html',
  styleUrl: './login.scss'
})
export class LoginComponent implements OnInit {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);
  readonly biometricAuth = inject(BiometricAuthService);
  readonly tokenStorage = inject(TokenStorageService);

  ngOnInit(): void {
    if (this.auth.isAuthenticated()) {
      this.router.navigate(['/dashboard']);
    }
  }


  isLoading = signal(false);
  isBiometricLoading = signal(false);
  errorMessage = signal('');

  // Versão/ambiente exibidos de forma evidente na tela de login.
  private readonly appEnv = readAppEnv();
  readonly versionLabel = formatVersionLabel(this.appEnv);
  // Data/hora do commit só no tooltip — mesmo padrão do rodapé global.
  readonly versionTooltip = formatVersionTooltip(this.appEnv);

  form = this.fb.group({
    email: [environment.devCredentials?.email ?? '', [Validators.required, Validators.email]],
    password: [environment.devCredentials?.password ?? '', [Validators.required]]
  });

  readonly isInvalid = formInvalidSignal(this.form);

  onSubmit() {
    if (this.form.invalid) return;
    const { email, password } = this.form.getRawValue();
    if (!email || !password) return;

    this.isLoading.set(true);
    this.errorMessage.set('');

    this.auth.login({ email, password }).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set('E-mail ou senha inválidos.');
      }
    });
  }

  async authenticateWithBiometric(): Promise<void> {
    if (this.isBiometricLoading()) return;
    this.isBiometricLoading.set(true);
    this.errorMessage.set('');
    try {
      const token = await this.biometricAuth.authenticate();
      if (!token) return;
      if (!this.auth.restoreSession(token)) {
        this.errorMessage.set('A sessão biométrica expirou. Entre com e-mail e senha.');
        return;
      }
      await this.router.navigate(['/dashboard']);
    } catch (error: unknown) {
      this.errorMessage.set(error instanceof Error ? error.message : 'Não foi possível autenticar com biometria.');
    } finally {
      this.isBiometricLoading.set(false);
    }
  }
}
