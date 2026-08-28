import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UsageService } from './usage.service';
import { UsageSnapshot } from '../models/usage.model';

describe('UsageService', () => {
  let service: UsageService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UsageService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches the snapshot from GET /api/usage', () => {
    const snapshot: UsageSnapshot = {
      claude: {
        available: true,
        fiveHour: { percentLeft: 75, resetsAt: '2026-01-01T00:00:00Z' },
        weekly: null,
        modelWeeklyLimits: [],
      },
      codex: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      updatedAt: '2026-01-01T00:00:00Z',
    };
    service.snapshot().subscribe((result) => expect(result).toEqual(snapshot));

    const req = httpMock.expectOne('/api/usage');
    expect(req.request.method).toBe('GET');
    req.flush(snapshot);
  });
});
