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
});
