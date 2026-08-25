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
