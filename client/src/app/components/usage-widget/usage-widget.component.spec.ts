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
      claude: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      codex: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      opencode: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.usage-widget')).toBeNull();
  });

  it('shows the collapsed row when at least one provider has data, and expands on click', () => {
    const fixture = init();
    flush({
      claude: { available: true, fiveHour: { percentLeft: 75, resetsAt: new Date().toISOString() }, weekly: null, modelWeeklyLimits: [] },
      codex: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      opencode: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
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
      claude: { available: true, fiveHour: { percentLeft: 40, resetsAt: new Date().toISOString() }, weekly: null, modelWeeklyLimits: [] },
      codex: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      opencode: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();
    fixture.componentInstance.toggle();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('unavailable');
    expect(text).toContain('60% used');
  });

  it('shows OpenCode as a third provider, alongside Claude and Codex', () => {
    const fixture = init();
    flush({
      claude: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      codex: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      opencode: { available: true, fiveHour: { percentLeft: 90, resetsAt: new Date().toISOString() }, weekly: null, modelWeeklyLimits: [] },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.usage-widget')).not.toBeNull();

    fixture.componentInstance.toggle();
    fixture.detectChanges();

    const names = Array.from(
      fixture.nativeElement.querySelectorAll('.usage-provider-name') as NodeListOf<HTMLElement>,
    ).map((el) => el.textContent);
    expect(names).toEqual(['Claude', 'Codex', 'OpenCode']);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('10% used');
  });

  it('renders one row per window when weekly data is present, and only the 5-hour row when it is not', () => {
    const fixture = init();
    flush({
      claude: {
        available: true,
        fiveHour: { percentLeft: 70, resetsAt: new Date().toISOString() },
        weekly: { percentLeft: 90, resetsAt: new Date().toISOString() },
        modelWeeklyLimits: [],
      },
      codex: {
        available: true,
        fiveHour: { percentLeft: 55, resetsAt: new Date().toISOString() },
        weekly: null,
        modelWeeklyLimits: [],
      },
      opencode: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();
    fixture.componentInstance.toggle();
    fixture.detectChanges();

    const providers = fixture.nativeElement.querySelectorAll('.usage-provider');
    expect(providers[0].querySelectorAll('.usage-window-row').length).toBe(2);
    expect(providers[1].querySelectorAll('.usage-window-row').length).toBe(1);

    const labels = Array.from(providers[0].querySelectorAll('.usage-window-label') as NodeListOf<HTMLElement>).map(
      (el) => el.textContent,
    );
    expect(labels).toEqual(['5-hour limit', 'Weekly']);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('30% used');
    expect(text).toContain('10% used');
    expect(text).toContain('45% used');
  });

  it('renders one row per per-model weekly limit, generically from the list', () => {
    const fixture = init();
    flush({
      claude: {
        available: true,
        fiveHour: { percentLeft: 70, resetsAt: new Date().toISOString() },
        weekly: { percentLeft: 65, resetsAt: new Date().toISOString() },
        modelWeeklyLimits: [
          { modelName: 'Fable', window: { percentLeft: 57, resetsAt: new Date().toISOString() } },
          { modelName: 'Opus', window: { percentLeft: 88, resetsAt: new Date().toISOString() } },
        ],
      },
      codex: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      opencode: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();
    fixture.componentInstance.toggle();
    fixture.detectChanges();

    const providers = fixture.nativeElement.querySelectorAll('.usage-provider');
    expect(providers[0].querySelectorAll('.usage-window-row').length).toBe(4);

    const labels = Array.from(providers[0].querySelectorAll('.usage-window-label') as NodeListOf<HTMLElement>).map(
      (el) => el.textContent,
    );
    expect(labels).toEqual(['5-hour limit', 'Weekly', 'Fable weekly', 'Opus weekly']);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('43% used');
    expect(text).toContain('12% used');
  });

  it('computes percent used and reset labels from a window', () => {
    const fixture = init();
    flush({
      claude: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      codex: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      opencode: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    const window = { percentLeft: 33.4, resetsAt: new Date(Date.now() + 5 * 60_000).toISOString() };
    expect(fixture.componentInstance.percentUsed(window)).toBeCloseTo(66.6);
    expect(fixture.componentInstance.percentUsed(null)).toBe(0);
    expect(fixture.componentInstance.usedLabel(window)).toBe('67% used');
    expect(fixture.componentInstance.usedLabel(null)).toBe('—');
    expect(fixture.componentInstance.resetLabel(window)).toBe('resets in 5m');
    expect(fixture.componentInstance.resetLabel(null)).toBe('');
  });

  it('flags a window as low once it drops under the low-percent threshold', () => {
    const fixture = init();
    flush({
      claude: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      codex: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      opencode: { available: false, fiveHour: null, weekly: null, modelWeeklyLimits: [] },
      updatedAt: new Date().toISOString(),
    });
    fixture.detectChanges();

    const low = {
      available: true,
      fiveHour: { percentLeft: 5, resetsAt: new Date().toISOString() },
      weekly: null,
      modelWeeklyLimits: [],
    };
    const healthy = {
      available: true,
      fiveHour: { percentLeft: 50, resetsAt: new Date().toISOString() },
      weekly: null,
      modelWeeklyLimits: [],
    };
    expect(fixture.componentInstance.isLow(low)).toBeTrue();
    expect(fixture.componentInstance.isLow(healthy)).toBeFalse();

    expect(fixture.componentInstance.isWindowLow({ percentLeft: 5, resetsAt: new Date().toISOString() })).toBeTrue();
    expect(fixture.componentInstance.isWindowLow({ percentLeft: 50, resetsAt: new Date().toISOString() })).toBeFalse();
    expect(fixture.componentInstance.isWindowLow(null)).toBeFalse();
  });
});
