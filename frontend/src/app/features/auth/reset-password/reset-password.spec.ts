import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { ResetPasswordComponent } from './reset-password';

function setup(queryToken: string | null) {
  return TestBed.configureTestingModule({
    imports: [ResetPasswordComponent],
    providers: [
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: { queryParamMap: convertToParamMap(queryToken ? { token: queryToken } : {}) },
        },
      },
    ],
  }).compileComponents();
}

describe('ResetPasswordComponent', () => {
  let component: ResetPasswordComponent;
  let fixture: ComponentFixture<ResetPasswordComponent>;
  let httpMock: HttpTestingController;

  describe('com token na URL', () => {
    beforeEach(async () => {
      await setup('token-valido');
      fixture = TestBed.createComponent(ResetPasswordComponent);
      component = fixture.componentInstance;
      httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
    });

    afterEach(() => httpMock.verify());

    it('should create', () => {
      expect(component).toBeTruthy();
      expect(component.state()).toBe('form');
    });

    it('marca form inválido quando as senhas não coincidem', () => {
      component.form.setValue({ password: 'SenhaForte123', confirmPassword: 'Diferente123' });
      expect(component.form.valid).toBe(false);
      expect(component.form.errors?.['passwordsMismatch']).toBe(true);
    });

    it('envia reset e marca success', () => {
      component.form.setValue({ password: 'SenhaForte123', confirmPassword: 'SenhaForte123' });
      component.onSubmit();

      const req = httpMock.expectOne('/auth/reset-password');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ token: 'token-valido', newPassword: 'SenhaForte123' });
      req.flush(null);

      expect(component.state()).toBe('success');
    });

    it('volta pro form com mensagem quando token é inválido/expirado (400)', () => {
      component.form.setValue({ password: 'SenhaForte123', confirmPassword: 'SenhaForte123' });
      component.onSubmit();

      const req = httpMock.expectOne('/auth/reset-password');
      req.flush(null, { status: 400, statusText: 'Bad Request' });

      expect(component.state()).toBe('form');
      expect(component.errorMessage()).toContain('inválido');
    });
  });

  describe('sem token na URL', () => {
    beforeEach(async () => {
      await setup(null);
      fixture = TestBed.createComponent(ResetPasswordComponent);
      component = fixture.componentInstance;
      httpMock = TestBed.inject(HttpTestingController);
      fixture.detectChanges();
    });

    afterEach(() => httpMock.verify());

    it('mostra estado de link inválido', () => {
      expect(component.state()).toBe('invalid-link');
    });
  });
});
