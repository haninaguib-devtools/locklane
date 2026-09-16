import { WorkspaceStore } from './workspace-store';

describe('WorkspaceStore (#935)', () => {
  const KEY = 'locklane.workspaces';
  beforeEach(() => localStorage.removeItem(KEY));
  afterEach(() => localStorage.removeItem(KEY));

  it('starts empty', () => {
    expect(new WorkspaceStore().list()).toEqual([]);
  });

  it('create adds a workspace with an empty filter, hide-shipped on and unique project ids', () => {
    const store = new WorkspaceStore();
    const ws = store.create('Backend', [1, 2, 2]);

    expect(ws.id).toBeTruthy();
    expect(ws).toEqual(jasmine.objectContaining({ name: 'Backend', projectIds: [1, 2], filterText: '', hideShipped: true }));
    expect(store.list()).toEqual([ws]);
    expect(store.get(ws.id)).toEqual(ws);
    expect(store.workspaces()).toEqual([ws]);
  });

  it('rename changes only the name', () => {
    const store = new WorkspaceStore();
    const ws = store.create('Old', [1]);
    store.rename(ws.id, 'New');

    expect(store.get(ws.id)).toEqual({ ...ws, name: 'New' });
  });

  it('delete removes the workspace and leaves the others', () => {
    const store = new WorkspaceStore();
    const a = store.create('A');
    const b = store.create('B');
    store.delete(a.id);

    expect(store.list()).toEqual([b]);
    expect(store.get(a.id)).toBeNull();
  });

  it('setProjects replaces the project ids', () => {
    const store = new WorkspaceStore();
    const ws = store.create('A', [1]);
    store.setProjects(ws.id, [3, 4]);

    expect(store.get(ws.id)!.projectIds).toEqual([3, 4]);
  });

  it('updateFilters changes the filter text and hide-shipped independently', () => {
    const store = new WorkspaceStore();
    const ws = store.create('A');
    store.updateFilters(ws.id, { filterText: 'bug' });
    expect(store.get(ws.id)).toEqual(jasmine.objectContaining({ filterText: 'bug', hideShipped: true }));

    store.updateFilters(ws.id, { hideShipped: false });
    expect(store.get(ws.id)).toEqual(jasmine.objectContaining({ filterText: 'bug', hideShipped: false }));
  });

  it('ignores operations on an unknown id', () => {
    const store = new WorkspaceStore();
    const ws = store.create('A');
    store.rename('nope', 'x');
    store.setProjects('nope', [9]);
    store.updateFilters('nope', { filterText: 'x' });
    store.delete('nope');

    expect(store.list()).toEqual([ws]);
  });

  it('persists across a new instance (simulating a reload)', () => {
    const first = new WorkspaceStore();
    const ws = first.create('A', [1, 2]);
    first.updateFilters(ws.id, { filterText: 'x', hideShipped: false });

    expect(new WorkspaceStore().list()).toEqual([{ ...ws, filterText: 'x', hideShipped: false }]);
  });

  it('drops malformed entries and fills missing filter fields on reload', () => {
    localStorage.setItem(
      KEY,
      JSON.stringify([{ id: 'a', name: 'A', projectIds: [1, 'x', 1] }, { name: 'no id' }, 'junk', null]),
    );

    expect(new WorkspaceStore().list()).toEqual([
      { id: 'a', name: 'A', projectIds: [1], filterText: '', hideShipped: true },
    ]);
  });

  it('starts empty when storage holds garbage', () => {
    localStorage.setItem(KEY, '{not json');
    expect(new WorkspaceStore().list()).toEqual([]);
  });
});
