import { describe, it, expect, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AcceptInviteComponent } from './accept-invite';
import { InvitationService } from '../../../core/services/invitation';
import { AuthService } from '../../../core/services/auth';

const makeRoute = (token: string | null) => ({
  snapshot: { queryParamMap: { get: (k: string) => (k === 'token' ? token : null) } },
});

describe('AcceptInviteComponent', () => {
  let fixture: ComponentFixture<AcceptInviteComponent>;
  let component: AcceptInviteComponent;

  interface SetupOptions {
    token: string | null;
    validateResponse: any;
    acceptResponse?: any;
  }

  const setupComponent = async ({ token, validateResponse, acceptResponse = of({ token: 'jwt' }) }: SetupOptions) => {
    const invitationSvc = {
      validateToken: () => validateResponse,
      acceptInvite: () => acceptResponse,
    };
    const authSvc = {
      setToken: () => {},
    };

    await TestBed.configureTestingModule({
      imports: [AcceptInviteComponent],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: makeRoute(token) },
        { provide: InvitationService, useValue: invitationSvc },
        { provide: AuthService, useValue: authSvc },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AcceptInviteComponent);
    component = fixture.componentInstance;
    await fixture.whenStable();
  };

  it('mostra formulário após validar token com sucesso', async () => {
    await setupComponent({ token: 'tok', validateResponse: of({ email: 'x@test.com', tenantName: 'Família' }) });
    expect(component.state()).toBe('form');
    expect(component.invitationInfo()?.email).toBe('x@test.com');
  });

  it('mostra erro quando token inválido', async () => {
    await setupComponent({ token: 'bad', validateResponse: throwError(() => ({ error: { message: 'Convite inválido' } })) });
    expect(component.state()).toBe('error');
    expect(component.errorMessage()).toBe('Convite inválido');
  });

  it('mostra erro quando nenhum token na URL', async () => {
    await setupComponent({ token: null, validateResponse: of({}) });
    expect(component.state()).toBe('error');
    expect(component.errorMessage()).toBe('Link de convite inválido.');
  });

  it('onSubmit bem-sucedido salva token e redireciona para /dashboard', async () => {
    let savedToken = '';
    await setupComponent({ token: 'tok', validateResponse: of({ email: 'x@test.com', tenantName: 'Família' }) });

    const router = TestBed.inject(Router);
    const authSvc = TestBed.inject(AuthService);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    vi.spyOn(authSvc, 'setToken').mockImplementation((t: string) => { savedToken = t; });

    component.form.setValue({ name: 'João', password: 'senha123' });
    component.onSubmit();
    await fixture.whenStable();

    expect(authSvc.setToken).toHaveBeenCalledWith('jwt');
    expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
  });

  it('onSubmit com erro do servidor retorna ao estado form com mensagem', async () => {
    const acceptError = throwError(() => ({ error: { message: 'Email já cadastrado' } }));
    await setupComponent({
      token: 'tok',
      validateResponse: of({ email: 'x@test.com', tenantName: 'Família' }),
      acceptResponse: acceptError,
    });

    component.form.setValue({ name: 'João', password: 'senha123' });
    component.onSubmit();
    await fixture.whenStable();

    expect(component.state()).toBe('form');
    expect(component.errorMessage()).toBe('Email já cadastrado');
  });
});
