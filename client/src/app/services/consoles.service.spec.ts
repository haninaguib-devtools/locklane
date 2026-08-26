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

  it('lists open consoles from GET /api/projects/{projectId}/consoles', () => {
    service.list(1).subscribe((result) => expect(result).toEqual(['1-7-main-a1b2c3d4', '1-8-slug']));

    const req = httpMock.expectOne('/api/projects/1/consoles');
    expect(req.request.method).toBe('GET');
    req.flush(['1-7-main-a1b2c3d4', '1-8-slug']);
  });

  it('notifies onClosed subscribers when a console is closed', () => {
    let notified = false;
    service.onClosed.subscribe(() => (notified = true));

    service.notifyClosed();

    expect(notified).toBeTrue();
  });
});
