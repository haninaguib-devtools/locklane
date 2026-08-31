import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SessionUploadsService } from './session-uploads.service';

describe('SessionUploadsService', () => {
  let service: SessionUploadsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SessionUploadsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('uploads the file via POST /api/sessions/{sessionId}/uploads and returns the stored path', () => {
    const file = new File(['png-bytes'], 'shot.png', { type: 'image/png' });
    let result: { path: string } | null = null;
    service.upload('1-174-rename-toggle', file).subscribe((r) => (result = r));

    const req = httpMock.expectOne('/api/sessions/1-174-rename-toggle/uploads');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBeInstanceOf(FormData);
    expect((req.request.body as FormData).get('file')).toEqual(file);
    req.flush({ path: '/home/user/.locklane/uploads/1-174-rename-toggle/shot.png' });

    expect(result!.path).toBe('/home/user/.locklane/uploads/1-174-rename-toggle/shot.png');
  });
});
