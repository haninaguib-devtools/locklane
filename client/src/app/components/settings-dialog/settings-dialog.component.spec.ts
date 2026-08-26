import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { SettingsDialogComponent } from './settings-dialog.component';

describe('SettingsDialogComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SettingsDialogComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function create(): ReturnType<typeof TestBed.createComponent<SettingsDialogComponent>> {
    const fixture = TestBed.createComponent(SettingsDialogComponent);
    fixture.detectChanges();
    return fixture;
  }

  function flushStatus(fixture: ReturnType<typeof create>, enabled: boolean): void {
    httpMock.expectOne('/api/account/2fa/status').flush({ enabled });
    fixture.detectChanges();
  }

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
});
