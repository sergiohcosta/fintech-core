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
});
