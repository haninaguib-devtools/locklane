import { TestBed } from '@angular/core/testing';
import { SessionListComponent } from './session-list.component';
import { ResumeSession } from '../../models/issue.model';

describe('SessionListComponent', () => {
  function session(overrides: Partial<ResumeSession> = {}): ResumeSession {
    return {
      worktreeId: '1-8-slug',
      tool: 'claude',
      resumeId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      capturedAt: '2026-08-27T10:00:00Z',
      ...overrides,
    };
  }

  function render(sessions: ResumeSession[], busy = false) {
    const fixture = TestBed.createComponent(SessionListComponent);
    fixture.componentInstance.sessions = sessions;
    fixture.componentInstance.busy = busy;
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [SessionListComponent] });
  });

  it('shows each session with its tool and captured time', () => {
    const fixture = render([
      session(),
      session({ tool: 'codex', resumeId: 'ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee' }),
    ]);

    const rows = fixture.nativeElement.querySelectorAll('.session');
    expect(rows.length).toBe(2);
    expect(rows[0].querySelector('.tool').textContent).toBe('claude');
    expect(rows[1].querySelector('.tool').textContent).toBe('codex');
    expect(rows[0].querySelector('.time').textContent).toContain('Aug 27');
  });

  it('emits the clicked session for reopening', () => {
    const past = session();
    const fixture = render([past]);
    const emitted: ResumeSession[] = [];
    fixture.componentInstance.reopen.subscribe((s: ResumeSession) => emitted.push(s));

    fixture.nativeElement.querySelector('.reopen').click();

    expect(emitted).toEqual([past]);
  });

  it('disables reopening while a console is already being started', () => {
    const fixture = render([session()], true);

    expect(fixture.nativeElement.querySelector('.reopen').disabled).toBeTrue();
  });
});
