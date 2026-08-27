import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { ConsolesPageComponent } from './consoles-page.component';
import { OpenProjectConsole } from '../../services/project-console.service';

describe('ConsolesPageComponent', () => {
  let httpMock: HttpTestingController;

  const CONSOLES: OpenProjectConsole[] = [
    {
      sessionId: '1-console-aaaa1111',
      workingDirectory: '/repo',
      createdAt: '2026-08-27T10:00:00Z',
      lastAttachedAt: '2026-08-27T10:05:00Z',
    },
    {
      sessionId: '1-console-bbbb2222',
      workingDirectory: '/repo',
      createdAt: '2026-08-27T11:00:00Z',
      lastAttachedAt: '2026-08-27T11:00:00Z',
    },
  ];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ConsolesPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function init(projectId = 1): ReturnType<typeof TestBed.createComponent<ConsolesPageComponent>> {
    const fixture = TestBed.createComponent(ConsolesPageComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.detectChanges();
    return fixture;
  }

  it('lists the open consoles with their session id and start time', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush(CONSOLES);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    const rows = compiled.querySelectorAll('.console');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('.id')?.textContent).toBe('1-console-aaaa1111');
    expect(rows[0].querySelector('.time')?.textContent).toContain('started');
    expect(rows[0].querySelector('.time')?.textContent).toContain('Aug 27, 2026');
  });

  it('shows an empty state when the project has no open console', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('no open consoles');
    expect(compiled.querySelector('.console')).toBeFalsy();
  });

  it('shows an error state when the list call fails', () => {
    const fixture = init();
    httpMock
      .expectOne('/api/projects/1/console/sessions')
      .flush(null, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      "could not load this project's consoles",
    );
  });

  it('opening a console navigates to the console page with that session id in the URL', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush(CONSOLES);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');

    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.console');
    rows[1].querySelector<HTMLButtonElement>('.open')!.click();

    expect(router.navigate).toHaveBeenCalledWith(['/projects', 1, 'console'], {
      queryParams: { session: '1-console-bbbb2222' },
    });
  });

  it('navigates back to the project\'s issues page', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush([]);
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    spyOn(router, 'navigate');

    fixture.componentInstance.back();

    expect(router.navigate).toHaveBeenCalledWith(['/projects', 1, 'issues']);
  });

  it('reloads when the project id changes', () => {
    const fixture = init();
    httpMock.expectOne('/api/projects/1/console/sessions').flush(CONSOLES);
    fixture.detectChanges();

    fixture.componentRef.setInput('projectId', 2);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects/2/console/sessions').flush([]);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('no open consoles');
    expect(compiled.querySelector('.console')).toBeFalsy();
  });
});
