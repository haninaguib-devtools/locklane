import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../services/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('logs in and clears any error on success', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.username = 'hani';
    fixture.componentInstance.password = 's3cret';

    fixture.componentInstance.submit();
    httpMock.expectOne('/api/auth/login').flush(null);

    expect(fixture.componentInstance.error).toBeNull();
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(true);
  });

  it('shows an error message on a failed login', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.username = 'hani';
    fixture.componentInstance.password = 'wrong';

    fixture.componentInstance.submit();
    httpMock.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(fixture.componentInstance.error).toContain('Incorrect username or password');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.error')?.textContent).toContain('Incorrect username or password');
  });

  it('switches to the code step when login answers twoFactorRequired', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.username = 'hani';
    fixture.componentInstance.password = 's3cret';

    fixture.componentInstance.submit();
    httpMock.expectOne('/api/auth/login').flush({ twoFactorRequired: true });
    fixture.detectChanges();

    expect(fixture.componentInstance.step).toBe('code');
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('input[name="code"]')).not.toBeNull();
    expect(compiled.querySelector('input[name="username"]')).toBeNull();
  });

  it('completes login when the code is accepted', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.step = 'code';
    fixture.componentInstance.code = '123456';

    fixture.componentInstance.submitCode();
    httpMock.expectOne('/api/auth/2fa/verify').flush({ username: 'hani' });

    expect(fixture.componentInstance.error).toBeNull();
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(true);
  });

  it('shows an inline error and stays on the code step when the code is rejected', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.step = 'code';
    fixture.componentInstance.code = '000000';

    fixture.componentInstance.submitCode();
    httpMock
      .expectOne('/api/auth/2fa/verify')
      .flush({ error: 'that code is not correct' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(fixture.componentInstance.step).toBe('code');
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.error')?.textContent).toContain('That code is not correct');
  });

  it('switches to the password-change step when login answers mustChangePasswordRequired', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.username = 'hani';
    fixture.componentInstance.password = 'temp-password';

    fixture.componentInstance.submit();
    httpMock.expectOne('/api/auth/login').flush({ mustChangePasswordRequired: true });
    fixture.detectChanges();

    expect(fixture.componentInstance.step).toBe('password-change');
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('input[name="newPassword"]')).not.toBeNull();
    expect(compiled.querySelector('input[name="username"]')).toBeNull();
  });

  it('completes login when the forced password change succeeds', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.step = 'password-change';
    fixture.componentInstance.password = 'temp-password';
    fixture.componentInstance.newPassword = 'a-brand-new-password';

    fixture.componentInstance.submitPasswordChange();
    const req = httpMock.expectOne('/api/auth/password/change');
    expect(req.request.body).toEqual({ currentPassword: 'temp-password', newPassword: 'a-brand-new-password' });
    req.flush({ username: 'hani' });

    expect(fixture.componentInstance.error).toBeNull();
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(true);
  });

  it('shows an inline error and stays on the password-change step when it is rejected', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.step = 'password-change';
    fixture.componentInstance.password = 'wrong-temp-password';
    fixture.componentInstance.newPassword = 'a-brand-new-password';

    fixture.componentInstance.submitPasswordChange();
    httpMock
      .expectOne('/api/auth/password/change')
      .flush({ error: 'that password is not correct' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(fixture.componentInstance.step).toBe('password-change');
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.error')?.textContent).toContain('Could not set that password');
  });

  it('disables the submit button while a login is in flight', () => {
    const fixture = TestBed.createComponent(LoginComponent);
    fixture.componentInstance.username = 'hani';
    fixture.componentInstance.password = 's3cret';

    fixture.componentInstance.submit();
    fixture.detectChanges();
    const button = (fixture.nativeElement as HTMLElement).querySelector('button');
    expect(button?.disabled).toBe(true);

    httpMock.expectOne('/api/auth/login').flush(null);
  });
});
