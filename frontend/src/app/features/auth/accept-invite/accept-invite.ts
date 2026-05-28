import { Component, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { InvitationService, InvitationInfo } from '../../../core/services/invitation';
import { AuthService } from '../../../core/services/auth';

type PageState = 'loading' | 'error' | 'form' | 'submitting';

@Component({
  selector: 'app-accept-invite',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './accept-invite.html',
  styleUrl: './accept-invite.scss',
})
export class AcceptInviteComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private fb = inject(FormBuilder);
  private invitationService = inject(InvitationService);
  private authService = inject(AuthService);

  state = signal<PageState>('loading');
  errorMessage = signal('');
  invitationInfo = signal<InvitationInfo | null>(null);

  form = this.fb.group({
    name:     ['', [Validators.required]],
    password: ['', [Validators.required, Validators.minLength(6)]],
  });

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.errorMessage.set('Link de convite inválido.');
      this.state.set('error');
      return;
    }

    this.invitationService.validateToken(token).subscribe({
      next: (info) => {
        this.invitationInfo.set(info);
        this.state.set('form');
      },
      error: (err) => {
        this.errorMessage.set(err.error?.message ?? 'Convite inválido ou expirado.');
        this.state.set('error');
      },
    });
  }

  onSubmit(): void {
    if (this.form.invalid) return;
    const token = this.route.snapshot.queryParamMap.get('token')!;

    this.state.set('submitting');
    this.invitationService.acceptInvite({
      token,
      name:     this.form.value.name!,
      password: this.form.value.password!,
    }).subscribe({
      next: (response) => {
        this.authService.setToken(response.token);
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.errorMessage.set(err.error?.message ?? 'Erro ao aceitar convite.');
        this.state.set('error');
      },
    });
  }

  goToLogin(): void {
    this.router.navigate(['/login']);
  }
}
