import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { WorkspacePickerComponent } from './workspace-picker.component';
import { FocusPreservingRouter } from '../../services/current-project.service';
import { WorkspaceStore } from '../../services/workspace-store';
import { Project } from '../../models/issue.model';
import { routes } from '../../app.routes';

describe('WorkspacePickerComponent (#936)', () => {
  let httpMock: HttpTestingController;

  const PROJECT_A: Project = {
    id: 1,
    name: 'Alpha',
    gitUrl: 'url-a',
    workareaPath: '/tmp/a',
    defaultBranch: 'main',
    accentColor: null,
    template: null,
    status: 'READY',
    createdAt: '',
  };
  const PROJECT_B: Project = { ...PROJECT_A, id: 2, name: 'Beta', gitUrl: 'url-b', workareaPath: '/tmp/b' };

  beforeEach(() => {
    localStorage.removeItem('locklane.workspaces');
    TestBed.configureTestingModule({
      imports: [WorkspacePickerComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter(routes),
        { provide: Router, useClass: FocusPreservingRouter },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('locklane.workspaces');
  });

  function mount(url = '/projects/1/issues') {
    TestBed.inject(Router).navigateByUrl(url);
    tick();
    const fixture = TestBed.createComponent(WorkspacePickerComponent);
    fixture.detectChanges();
    httpMock.expectOne('/api/projects').flush([PROJECT_A, PROJECT_B]);
    fixture.detectChanges();
    return fixture;
  }

  function trigger(fixture: { nativeElement: HTMLElement }): HTMLButtonElement {
    return fixture.nativeElement.querySelector('.trigger') as HTMLButtonElement;
  }

  function rows(fixture: { nativeElement: HTMLElement }): HTMLElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('.row')) as HTMLElement[];
  }

  function key(fixture: { nativeElement: HTMLElement }, key: string): void {
    (fixture.nativeElement.querySelector('.rows') as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key, bubbles: true }),
    );
  }

  it('labels the trigger with the active workspace, or "All projects" without one', fakeAsync(() => {
    const ws = TestBed.inject(WorkspaceStore).create('Backend', [1]);
    let fixture = mount('/projects/1/issues');
    expect(trigger(fixture).textContent).toContain('All projects');
    fixture.destroy();

    fixture = TestBed.createComponent(WorkspacePickerComponent);
    TestBed.inject(Router).navigateByUrl(`/projects/1/issues?ws=${ws.id}`);
    tick();
    fixture.detectChanges();
    expect(trigger(fixture).textContent).toContain('Backend');
  }));

  it('selecting a workspace sets ws in the URL, and "All projects" removes it', fakeAsync(() => {
    const ws = TestBed.inject(WorkspaceStore).create('Backend', [1]);
    const fixture = mount('/projects/1/issues');
    const router = TestBed.inject(Router);

    trigger(fixture).click();
    fixture.detectChanges();
    expect(rows(fixture).map((r) => r.textContent!.trim())).toEqual(
      jasmine.arrayContaining(['All projects', jasmine.stringContaining('Backend'), 'New workspace…']),
    );
    rows(fixture)[1].click();
    tick();
    fixture.detectChanges();
    expect(router.url).toBe(`/projects/1/issues?ws=${ws.id}`);
    expect(fixture.nativeElement.querySelector('.picker')).toBeNull();
    expect(trigger(fixture).textContent).toContain('Backend');

    trigger(fixture).click();
    fixture.detectChanges();
    rows(fixture)[0].click();
    tick();
    fixture.detectChanges();
    expect(router.url).toBe('/projects/1/issues');
    expect(trigger(fixture).textContent).toContain('All projects');
  }));

  it('"New workspace…" asks for a name, picks projects, and activates the new workspace', fakeAsync(() => {
    const fixture = mount('/projects/1/issues');
    const router = TestBed.inject(Router);

    trigger(fixture).click();
    fixture.detectChanges();
    rows(fixture)[1].click(); // no workspaces yet: All, New
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('app-workspace-projects-dialog') as HTMLElement;
    expect(dialog).not.toBeNull();

    const name = dialog.querySelector('input[type=text]') as HTMLInputElement;
    name.value = 'Backend';
    name.dispatchEvent(new Event('input'));
    const boxes = Array.from(dialog.querySelectorAll('input[type=checkbox]')) as HTMLInputElement[];
    expect(boxes.length).toBe(2);
    boxes[1].click();
    fixture.detectChanges();
    (dialog.querySelector('.save') as HTMLButtonElement).click();
    tick();
    fixture.detectChanges();

    const stored = TestBed.inject(WorkspaceStore).list();
    expect(stored.length).toBe(1);
    expect(stored[0]).toEqual(jasmine.objectContaining({ name: 'Backend', projectIds: [2] }));
    expect(router.url).toBe(`/projects/1/issues?ws=${stored[0].id}`);
    expect(fixture.nativeElement.querySelector('app-workspace-projects-dialog')).toBeNull();
  }));

  it('"Edit projects" and "Rename" on the active row update the store', fakeAsync(() => {
    const store = TestBed.inject(WorkspaceStore);
    const ws = store.create('Backend', [1]);
    const fixture = mount(`/projects/1/issues?ws=${ws.id}`);

    trigger(fixture).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.action[aria-label="Edit projects"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    let dialog = fixture.nativeElement.querySelector('app-workspace-projects-dialog') as HTMLElement;
    expect(dialog.querySelector('input[type=text]')).toBeNull();
    const boxes = Array.from(dialog.querySelectorAll('input[type=checkbox]')) as HTMLInputElement[];
    expect(boxes.map((b) => b.checked)).toEqual([true, false]);
    boxes[0].click();
    boxes[1].click();
    (dialog.querySelector('.save') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(store.get(ws.id)!.projectIds).toEqual([2]);

    trigger(fixture).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.action[aria-label="Rename"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    dialog = fixture.nativeElement.querySelector('app-workspace-projects-dialog') as HTMLElement;
    expect(dialog.querySelector('input[type=checkbox]')).toBeNull();
    const name = dialog.querySelector('input[type=text]') as HTMLInputElement;
    expect(name.value).toBe('Backend');
    name.value = 'Services';
    name.dispatchEvent(new Event('input'));
    name.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
    expect(store.get(ws.id)!.name).toBe('Services');
    expect(trigger(fixture).textContent).toContain('Services');
  }));

  it('deleting the active workspace asks first, then lands on all projects', fakeAsync(() => {
    const store = TestBed.inject(WorkspaceStore);
    const ws = store.create('Backend', [1]);
    const fixture = mount(`/projects/1/issues?ws=${ws.id}`);
    const router = TestBed.inject(Router);

    trigger(fixture).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.action[aria-label="Delete"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const confirm = fixture.nativeElement.querySelector('app-confirm-dialog') as HTMLElement;
    expect(confirm).not.toBeNull();
    expect(store.get(ws.id)).not.toBeNull();

    (confirm.querySelector('.secondary') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(store.get(ws.id)).not.toBeNull();
    expect(router.url).toBe(`/projects/1/issues?ws=${ws.id}`);

    trigger(fixture).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.action[aria-label="Delete"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('app-confirm-dialog .danger') as HTMLButtonElement).click();
    tick();
    fixture.detectChanges();
    expect(store.get(ws.id)).toBeNull();
    expect(router.url).toBe('/projects/1/issues');
    expect(trigger(fixture).textContent).toContain('All projects');
  }));

  it('"Open in new window" opens the current URL with ws=<id>', fakeAsync(() => {
    const ws = TestBed.inject(WorkspaceStore).create('Backend', [1]);
    const fixture = mount(`/projects/1/issues?ws=${ws.id}`);
    const openSpy = spyOn(window, 'open');

    trigger(fixture).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.action[aria-label="Open in new window"]') as HTMLButtonElement).click();

    fixture.detectChanges();

    expect(openSpy).toHaveBeenCalledWith(`/projects/1/issues?ws=${ws.id}`, '_blank');
    expect(fixture.nativeElement.querySelector('.picker')).toBeNull();
  }));

  it('"Open in new window" is on every workspace row, first, and pops out that row (#969)', fakeAsync(() => {
    const store = TestBed.inject(WorkspaceStore);
    const active = store.create('Backend', [1]);
    const other = store.create('Frontend', [2]);
    const fixture = mount(`/projects/1/issues?ws=${active.id}`);
    const openSpy = spyOn(window, 'open');

    trigger(fixture).click();
    fixture.detectChanges();
    const labels = (row: HTMLElement) =>
      Array.from(row.querySelectorAll('.action')).map((b) => b.getAttribute('aria-label'));
    expect(labels(rows(fixture)[1])).toEqual(['Open in new window', 'Edit projects', 'Rename', 'Delete']);
    expect(labels(rows(fixture)[2])).toEqual(['Open in new window']);

    (rows(fixture)[2].querySelector('.action[aria-label="Open in new window"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(openSpy).toHaveBeenCalledWith(`/projects/1/issues?ws=${other.id}`, '_blank');
    expect(fixture.nativeElement.querySelector('.picker')).toBeNull();
  }));

  it('keyboard: arrows move, Enter selects, Escape closes', fakeAsync(() => {
    const ws = TestBed.inject(WorkspaceStore).create('Backend', [1]);
    const fixture = mount('/projects/1/issues');
    const router = TestBed.inject(Router);

    trigger(fixture).click();
    fixture.detectChanges();
    expect(rows(fixture)[0].classList).toContain('selected');
    key(fixture, 'ArrowDown');
    fixture.detectChanges();
    expect(rows(fixture)[1].classList).toContain('selected');
    key(fixture, 'ArrowUp');
    key(fixture, 'ArrowUp');
    fixture.detectChanges();
    expect(rows(fixture)[2].classList).toContain('selected'); // wraps to "New workspace…"
    key(fixture, 'Escape');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.picker')).toBeNull();

    trigger(fixture).click();
    fixture.detectChanges();
    key(fixture, 'ArrowDown');
    key(fixture, 'Enter');
    tick();
    fixture.detectChanges();
    expect(router.url).toBe(`/projects/1/issues?ws=${ws.id}`);
    expect(fixture.nativeElement.querySelector('.picker')).toBeNull();
  }));
});
