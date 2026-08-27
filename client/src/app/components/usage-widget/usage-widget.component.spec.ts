import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { UsageWidgetComponent } from './usage-widget.component';
import { UsageSnapshot } from '../../models/usage.model';

describe('UsageWidgetComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [UsageWidgetComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function init(): ReturnType<typeof TestBed.createComponent<UsageWidgetComponent>> {
    const fixture = TestBed.createComponent(UsageWidgetComponent);
    fixture.detectChanges();
    return fixture;
  }

  function flush(snapshot: UsageSnapshot): void {
    httpMock.expectOne('/api/usage').flush(snapshot);
  }

  it('renders no row at all when both providers are unavailable', () => {
    const fixture = init();
    flush({
      claude: { available: false, fiveHour: null, weekly: null },
      codex: { available: false, fiveHour: null, weekly: null },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.usage-widget')).toBeNull();
  });

  it('shows the collapsed row when at least one provider has data, and expands on click', () => {
    const fixture = init();
    flush({
      claude: { available: true, fiveHour: { percentLeft: 75, resetsAt: new Date().toISOString() }, weekly: null },
      codex: { available: false, fiveHour: null, weekly: null },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.usage-widget')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.usage-panel')).toBeNull();

    fixture.componentInstance.toggle();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.usage-panel')).not.toBeNull();
  });

  it('shows "unavailable" only for the provider that failed, leaving the other provider\'s data intact', () => {
    const fixture = init();
    flush({
      claude: { available: true, fiveHour: { percentLeft: 40, resetsAt: new Date().toISOString() }, weekly: null },
      codex: { available: false, fiveHour: null, weekly: null },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();
    fixture.componentInstance.toggle();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('unavailable');
    expect(text).toContain('40% left');
  });

  it('formats percent-left and reset labels from a window', () => {
    const fixture = init();
    flush({
      claude: { available: false, fiveHour: null, weekly: null },
      codex: { available: false, fiveHour: null, weekly: null },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    const window = { percentLeft: 33.4, resetsAt: new Date(Date.now() + 5 * 60_000).toISOString() };
    expect(fixture.componentInstance.percentLeftLabel(window)).toBe('33% left');
    expect(fixture.componentInstance.percentLeftLabel(null)).toBe('—');
    expect(fixture.componentInstance.resetLabel(window)).toBe('resets in 5m');
    expect(fixture.componentInstance.resetLabel(null)).toBe('');
  });

  it('flags a window as low once it drops under the low-percent threshold', () => {
    const fixture = init();
    flush({
      claude: { available: false, fiveHour: null, weekly: null },
      codex: { available: false, fiveHour: null, weekly: null },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    const low = { available: true, fiveHour: { percentLeft: 5, resetsAt: new Date().toISOString() }, weekly: null };
    const healthy = { available: true, fiveHour: { percentLeft: 50, resetsAt: new Date().toISOString() }, weekly: null };
    expect(fixture.componentInstance.isLow(low)).toBeTrue();
    expect(fixture.componentInstance.isLow(healthy)).toBeFalse();
  });
});
