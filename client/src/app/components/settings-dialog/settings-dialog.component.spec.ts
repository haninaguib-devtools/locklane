import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { SettingsDialogComponent } from './settings-dialog.component';

const DEFAULT_AGENT_STORAGE_KEY = 'locklane.defaultAgent';

describe('SettingsDialogComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    localStorage.removeItem(DEFAULT_AGENT_STORAGE_KEY);
    await TestBed.configureTestingModule({
      imports: [SettingsDialogComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem(DEFAULT_AGENT_STORAGE_KEY);
  });

  function create(): ReturnType<typeof TestBed.createComponent<SettingsDialogComponent>> {
    const fixture = TestBed.createComponent(SettingsDialogComponent);
    fixture.detectChanges();
    return fixture;
  }

  const ALL_AGENTS = [
    { id: 'claude', label: 'Claude' },
    { id: 'codex', label: 'Codex' },
    { id: 'opencode', label: 'OpenCode' },
  ];

  /** Also flushes the installed-agents request with all three, since every test but the ones
   *  exercising that picker directly (below) don't care about its value. */
  function flushStatus(fixture: ReturnType<typeof create>, enabled: boolean): void {
    httpMock.expectOne('/api/account/2fa/status').flush({ enabled });
    httpMock.expectOne('/api/agents/installed').flush({ installed: ALL_AGENTS });
    fixture.detectChanges();
  }

  function flushInstalledAgents(fixture: ReturnType<typeof create>, ids: string[]): void {
    const installed = ALL_AGENTS.filter((agent) => ids.includes(agent.id));
    httpMock.expectOne('/api/agents/installed').flush({ installed });
    fixture.detectChanges();
  }

  it('defaults to claude and lets the choice be switched to codex and back', () => {
    const fixture = create();
    flushStatus(fixture, false);
    const compiled = fixture.nativeElement as HTMLElement;
    const options = () => compiled.querySelectorAll<HTMLButtonElement>('.agent-option');

    expect(options()[0].classList.contains('chosen')).toBe(true);
    expect(options()[1].classList.contains('chosen')).toBe(false);

    options()[1].click();
    fixture.detectChanges();

    expect(options()[0].classList.contains('chosen')).toBe(false);
    expect(options()[1].classList.contains('chosen')).toBe(true);
  });

  it('remembers the default agent choice across dialog instances', () => {
    const first = create();
    flushStatus(first, false);
    (first.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.agent-option')[1].click();

    // DefaultAgentStore is a root singleton and only fetches installed agents once
    // (refreshInstalled is a no-op once already requested), so this second instance's
    // ngOnInit issues no second /api/agents/installed request to flush.
    const second = create();
    httpMock.expectOne('/api/account/2fa/status').flush({ enabled: false });
    second.detectChanges();
    const options = (second.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.agent-option');

    expect(options[1].classList.contains('chosen')).toBe(true);
  });

  it('renders a button only for an agent detected as installed', () => {
    const fixture = create();
    httpMock.expectOne('/api/account/2fa/status').flush({ enabled: false });
    flushInstalledAgents(fixture, ['claude', 'opencode']);
    const compiled = fixture.nativeElement as HTMLElement;
    const labels = Array.from(compiled.querySelectorAll<HTMLButtonElement>('.agent-option')).map((b) =>
      b.textContent?.trim(),
    );

    expect(labels).toEqual(['Claude', 'OpenCode']);
  });

  it('falls back to the first installed agent when the saved preference is no longer installed', () => {
    localStorage.setItem(DEFAULT_AGENT_STORAGE_KEY, 'codex');
    const fixture = create();
    httpMock.expectOne('/api/account/2fa/status').flush({ enabled: false });
    flushInstalledAgents(fixture, ['claude']);
    const compiled = fixture.nativeElement as HTMLElement;
    const options = compiled.querySelectorAll<HTMLButtonElement>('.agent-option');

    expect(options.length).toBe(1);
    expect(options[0].textContent?.trim()).toBe('Claude');
    expect(options[0].classList.contains('chosen')).toBe(true);
  });

  it('shows no agent option until the installed-agents fetch resolves, and stays empty on failure', () => {
    const fixture = create();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelectorAll('.agent-option').length).toBe(0);

    httpMock.expectOne('/api/account/2fa/status').flush({ enabled: false });
    httpMock.expectOne('/api/agents/installed').flush({ error: 'boom' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();

    expect(compiled.querySelectorAll('.agent-option').length).toBe(0);
  });

  it('renders a title bar and loads the 2FA status', () => {
    const fixture = create();
    const compiled = fixture.nativeElement as HTMLElement;

    expect(compiled.querySelector('.popup-header span')?.textContent?.trim()).toBe('settings');
    expect(compiled.textContent).toContain('Loading');

    flushStatus(fixture, false);

    expect(compiled.textContent).toContain('Two-factor authentication is off');
  });

  it('closes on the close button, on the backdrop, and on Escape', () => {
    const fixture = create();
    flushStatus(fixture, false);
    const compiled = fixture.nativeElement as HTMLElement;
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);

    compiled.querySelector<HTMLButtonElement>('.close')!.click();
    expect(closed).toBe(1);

    compiled.querySelector<HTMLElement>('.backdrop')!.click();
    expect(closed).toBe(2);

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(closed).toBe(3);
  });

  it('does not close when the panel itself is clicked', () => {
    const fixture = create();
    flushStatus(fixture, false);
    const compiled = fixture.nativeElement as HTMLElement;
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);

    compiled.querySelector<HTMLElement>('.popup-body')!.click();

    expect(closed).toBe(0);
  });

  it('walks the enable flow: enroll, confirm, shows backup codes once, then enabled', () => {
    const fixture = create();
    flushStatus(fixture, false);
    const compiled = fixture.nativeElement as HTMLElement;
    const component = fixture.componentInstance;

    component.startEnroll();
    httpMock
      .expectOne('/api/account/2fa/enroll')
      .flush({ qrCodeDataUri: 'data:image/png;base64,abc', manualKey: 'SECRET123', otpauthUri: 'otpauth://x' });
    fixture.detectChanges();

    expect(component.stage()).toBe('enrolling');
    expect(compiled.querySelector('.secret')?.textContent?.trim()).toBe('SECRET123');
    expect(compiled.querySelector<HTMLImageElement>('.qr')?.src).toContain('data:image/png');

    component.confirmCode = '123456';
    component.confirmEnroll();
    httpMock
      .expectOne('/api/account/2fa/confirm')
      .flush({ enabled: true, backupCodes: ['AAAAA-11111', 'BBBBB-22222'] });
    fixture.detectChanges();

    expect(component.stage()).toBe('backup-codes');
    expect(compiled.textContent).toContain('Save these backup codes');
    expect(compiled.querySelectorAll('.backup-codes li').length).toBe(2);
    expect(compiled.querySelector('.backup-codes li')?.textContent?.trim()).toBe('AAAAA-11111');

    component.acknowledgeBackupCodes();
    fixture.detectChanges();

    expect(component.stage()).toBe('enabled');
    expect(compiled.textContent).toContain('Two-factor authentication is enabled');
  });

  it('walks the regenerate-backup-codes flow from the enabled state', () => {
    const fixture = create();
    flushStatus(fixture, true);
    const compiled = fixture.nativeElement as HTMLElement;
    const component = fixture.componentInstance;

    component.startRegenerateBackupCodes();
    fixture.detectChanges();
    expect(compiled.querySelector<HTMLInputElement>('input[name="regeneratePassword"]')).toBeTruthy();

    component.regeneratePassword = 'my-password';
    component.regenerateBackupCodes();
    httpMock
      .expectOne('/api/account/2fa/backup-codes/regenerate')
      .flush({ backupCodes: ['CCCCC-33333'] });
    fixture.detectChanges();

    expect(component.stage()).toBe('backup-codes');
    expect(compiled.querySelector('.backup-codes li')?.textContent?.trim()).toBe('CCCCC-33333');

    component.acknowledgeBackupCodes();
    fixture.detectChanges();

    expect(component.stage()).toBe('enabled');
  });

  it('shows an inline error on a wrong password while regenerating backup codes', () => {
    const fixture = create();
    flushStatus(fixture, true);
    const component = fixture.componentInstance;

    component.startRegenerateBackupCodes();
    component.regeneratePassword = 'wrong-password';
    component.regenerateBackupCodes();
    httpMock
      .expectOne('/api/account/2fa/backup-codes/regenerate')
      .flush({ error: 'that password is not correct' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(component.stage()).toBe('enabled');
    expect(compiled.querySelector('.error')?.textContent?.trim()).toBe('that password is not correct');
  });

  it('shows an inline error on a wrong confirmation code and stays enrolling', () => {
    const fixture = create();
    flushStatus(fixture, false);
    const component = fixture.componentInstance;

    component.startEnroll();
    httpMock
      .expectOne('/api/account/2fa/enroll')
      .flush({ qrCodeDataUri: 'data:image/png;base64,abc', manualKey: 'SECRET123', otpauthUri: 'otpauth://x' });
    fixture.detectChanges();

    component.confirmCode = '000000';
    component.confirmEnroll();
    httpMock
      .expectOne('/api/account/2fa/confirm')
      .flush({ error: 'that code is not correct' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(component.stage()).toBe('enrolling');
    expect(compiled.querySelector('.error')?.textContent?.trim()).toBe('that code is not correct');
    expect(compiled.querySelector('.secret')).toBeTruthy();
  });

  it('lets a cancelled enrollment be started over', () => {
    const fixture = create();
    flushStatus(fixture, false);
    const component = fixture.componentInstance;

    component.startEnroll();
    httpMock
      .expectOne('/api/account/2fa/enroll')
      .flush({ qrCodeDataUri: 'data:image/png;base64,abc', manualKey: 'SECRET123', otpauthUri: 'otpauth://x' });
    fixture.detectChanges();

    component.cancelEnroll();
    fixture.detectChanges();

    expect(component.stage()).toBe('off');
  });

  it('walks the disable flow', () => {
    const fixture = create();
    flushStatus(fixture, true);
    const compiled = fixture.nativeElement as HTMLElement;
    const component = fixture.componentInstance;

    expect(compiled.textContent).toContain('Two-factor authentication is enabled');

    component.password = 'my-password';
    component.disable();
    httpMock.expectOne('/api/account/2fa/disable').flush({ enabled: false });
    fixture.detectChanges();

    expect(component.stage()).toBe('off');
    expect(compiled.textContent).toContain('Two-factor authentication is off');
  });

  it('shows an inline error on a wrong password and stays enabled', () => {
    const fixture = create();
    flushStatus(fixture, true);
    const component = fixture.componentInstance;

    component.password = 'wrong-password';
    component.disable();
    httpMock
      .expectOne('/api/account/2fa/disable')
      .flush({ error: 'that password is not correct' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(component.stage()).toBe('enabled');
    expect(compiled.querySelector('.error')?.textContent?.trim()).toBe('that password is not correct');
  });

  it('changes the password and shows a confirmation', () => {
    const fixture = create();
    flushStatus(fixture, false);
    const component = fixture.componentInstance;

    component.currentPasswordForChange = 'old-password';
    component.newPasswordForChange = 'new-password';
    component.changePassword();
    const req = httpMock.expectOne('/api/account/password');
    expect(req.request.body).toEqual({ currentPassword: 'old-password', newPassword: 'new-password' });
    req.flush(null);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Password changed.');
    expect(component.currentPasswordForChange).toBe('');
    expect(component.newPasswordForChange).toBe('');
  });

  it('shows an inline error on a wrong current password when changing it', () => {
    const fixture = create();
    flushStatus(fixture, false);
    const component = fixture.componentInstance;

    component.currentPasswordForChange = 'wrong-password';
    component.newPasswordForChange = 'new-password';
    component.changePassword();
    httpMock
      .expectOne('/api/account/password')
      .flush({ error: 'that password is not correct' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.error')?.textContent?.trim()).toBe('that password is not correct');
  });
});
