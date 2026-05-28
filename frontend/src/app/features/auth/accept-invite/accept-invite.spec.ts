import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
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

  const setupComponent = async (token: string | null, invitationResponse: any) => {
    const invitationSvc = {
      validateToken: () => invitationResponse,
      acceptInvite: () => of({ token: 'jwt' }),
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
    await setupComponent('tok', of({ email: 'x@test.com', tenantName: 'Família' }));
    expect(component.state()).toBe('form');
    expect(component.invitationInfo()?.email).toBe('x@test.com');
  });

  it('mostra erro quando token inválido', async () => {
    await setupComponent('bad', throwError(() => ({ error: { message: 'Convite inválido' } })));
    expect(component.state()).toBe('error');
    expect(component.errorMessage()).toBe('Convite inválido');
  });

  it('mostra erro quando nenhum token na URL', async () => {
    await setupComponent(null, of({}));
    expect(component.state()).toBe('error');
    expect(component.errorMessage()).toBe('Link de convite inválido.');
  });
});
