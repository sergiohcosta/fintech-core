import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { MembersService } from './members';

describe('MembersService', () => {
  let service: MembersService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(MembersService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('list chama GET /api/members', () => {
    service.list().subscribe(members => {
      expect(members).toHaveLength(1);
      expect(members[0].name).toBe('João');
      expect(members[0].role).toBe('ADMIN');
    });

    const req = httpMock.expectOne('/api/members');
    expect(req.request.method).toBe('GET');
    req.flush([{ id: 'uuid-1', name: 'João', email: 'joao@test.com', role: 'ADMIN' }]);
  });
});
