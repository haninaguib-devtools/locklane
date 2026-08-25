import { WorktreeTabsComponent } from './worktree-tabs.component';

describe('WorktreeTabsComponent', () => {
  it('emits the clicked worktree id', () => {
    const c = new WorktreeTabsComponent();
    c.worktreeIds = ['main', '174-rename-toggle'];
    let emitted: string | undefined;
    c.selectedChange.subscribe((id) => (emitted = id));

    c.select('174-rename-toggle');

    expect(emitted).toBe('174-rename-toggle');
  });

  it('emits start when requested', () => {
    const c = new WorktreeTabsComponent();
    let started = false;
    c.start.subscribe(() => (started = true));

    c.start.emit();

    expect(started).toBeTrue();
  });
});
