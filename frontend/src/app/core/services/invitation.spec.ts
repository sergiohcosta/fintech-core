import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { InvitationService } from './invitation';

describe('InvitationService', () => {
  let service: InvitationService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(InvitationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('validateToken chama GET /invites/{token}', () => {
    service.validateToken('abc123').subscribe(info => {
      expect(info.email).toBe('x@test.com');
      expect(info.tenantName).toBe('Família');
    });

    const req = httpMock.expectOne('/invites/abc123');
    expect(req.request.method).toBe('GET');
    req.flush({ email: 'x@test.com', tenantName: 'Família' });
  });

  it('acceptInvite chama POST /auth/accept-invite', () => {
    service.acceptInvite({ token: 'tok', name: 'João', password: '123' })
      .subscribe(r => expect(r.token).toBe('jwt'));

    const req = httpMock.expectOne('/auth/accept-invite');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ token: 'tok', name: 'João', password: '123' });
    req.flush({ token: 'jwt' });
  });

  it('create chama POST /invites com email', () => {
    service.create({ email: 'novo@test.com' }).subscribe(r => {
      expect(r.email).toBe('novo@test.com');
      expect(r.link).toBe('http://localhost:4200/accept-invite?token=tok');
    });

    const req = httpMock.expectOne('/invites');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'novo@test.com' });
    req.flush({
      token: 'tok',
      link: 'http://localhost:4200/accept-invite?token=tok',
      email: 'novo@test.com',
      expiresAt: '2026-06-12T00:00:00',
    });
  });

  it('list chama GET /invites', () => {
    service.list().subscribe(invites => {
      expect(invites).toHaveLength(1);
      expect(invites[0].status).toBe('PENDING');
    });

    const req = httpMock.expectOne('/invites');
    expect(req.request.method).toBe('GET');
    req.flush([{
      id: 'uuid-1',
      email: 'x@test.com',
      status: 'PENDING',
      createdAt: '2026-06-05T10:00:00',
      expiresAt: '2026-06-12T10:00:00',
      link: 'http://localhost:4200/accept-invite?token=tok',
    }]);
  });

  it('revoke chama DELETE /invites/{id}', () => {
    service.revoke('uuid-1').subscribe();

    const req = httpMock.expectOne('/invites/uuid-1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
