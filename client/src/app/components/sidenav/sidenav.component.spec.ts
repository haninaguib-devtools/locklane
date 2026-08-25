import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SidenavComponent } from './sidenav.component';
import { GhIssue } from '../../models/issue.model';

describe('SidenavComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SidenavComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('loads and lists issues from the backend', () => {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.detectChanges();

    const issues: GhIssue[] = [
      { number: 1, title: 'First', state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' },
    ];
    httpMock.expectOne('/api/issues').flush(issues);
    fixture.detectChanges();

    expect(fixture.componentInstance.issues).toEqual(issues);
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('reports an error state when the fetch fails, rather than staying stuck loading', () => {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.detectChanges();

    httpMock.expectOne('/api/issues').error(new ProgressEvent('network error'));
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toBeTrue();
    expect(fixture.componentInstance.loading).toBeFalse();
  });

  it('emits the selected issue number on click', () => {
    const fixture = TestBed.createComponent(SidenavComponent);
    fixture.detectChanges();
    httpMock
      .expectOne('/api/issues')
      .flush([{ number: 7, title: 'Seven', state: 'OPEN', labels: [], body: '', createdAt: '', updatedAt: '' }]);

    let emitted: number | undefined;
    fixture.componentInstance.selectedChange.subscribe((n) => (emitted = n));
    fixture.componentInstance.select({ number: 7 } as GhIssue);

    expect(emitted).toBe(7);
  });
});
