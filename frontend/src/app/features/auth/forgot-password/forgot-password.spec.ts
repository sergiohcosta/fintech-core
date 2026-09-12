import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { ForgotPasswordComponent } from './forgot-password';

describe('ForgotPasswordComponent', () => {
  let component: ForgotPasswordComponent;
  let fixture: ComponentFixture<ForgotPasswordComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ForgotPasswordComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(ForgotPasswordComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    await fixture.whenStable();
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('marca submitted após envio bem-sucedido', () => {
    component.form.setValue({ email: 'carlos@costa.com' });
    component.onSubmit();

    const req = httpMock.expectOne('/auth/forgot-password');
    expect(req.request.method).toBe('POST');
    req.flush(null);

    expect(component.submitted()).toBe(true);
  });

  it('marca submitted mesmo quando o backend retorna erro (nunca revela se o email existe)', () => {
    component.form.setValue({ email: 'ninguem@teste.com' });
    component.onSubmit();

    const req = httpMock.expectOne('/auth/forgot-password');
    req.flush(null, { status: 429, statusText: 'Too Many Requests' });

    expect(component.submitted()).toBe(true);
  });

  it('não envia requisição com email inválido', () => {
    component.form.setValue({ email: 'nao-e-email' });
    component.onSubmit();

    httpMock.expectNone('/auth/forgot-password');
  });
});
