import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../../core/services/auth';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule
  ],
  templateUrl: './register.html', // Nome simplificado
  styleUrl: './register.scss'     // Nome simplificado
})
export class RegisterComponent {
  private fb = inject(FormBuilder);
  private authService = inject(AuthService);

  isLoading = signal(false);
  errorMessage = signal('');

  form = this.fb.group({
    name: ['', [Validators.required]],
    document: [''],
    adminName: ['', [Validators.required]],
    adminEmail: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(6)]]
  });

  onSubmit() {
    if (this.form.invalid) return;
    this.isLoading.set(true);

    this.authService.register(this.form.value as any).subscribe({
      next: () => alert('Sucesso!'),
      error: (err) => {
        this.isLoading.set(false);
        this.errorMessage.set('Erro ao cadastrar. Verifique os dados.');
      },
      complete: () => this.isLoading.set(true)
    });
  }
}