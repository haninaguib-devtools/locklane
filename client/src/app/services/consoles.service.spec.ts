import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ConsolesService } from './consoles.service';

describe('ConsolesService', () => {
  let service: ConsolesService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ConsolesService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('lists open consoles from GET /api/consoles', () => {
    service.list().subscribe((result) => expect(result).toEqual(['7-main-a1b2c3d4', '8-slug']));

    const req = httpMock.expectOne('/api/consoles');
    expect(req.request.method).toBe('GET');
    req.flush(['7-main-a1b2c3d4', '8-slug']);
  });
});
