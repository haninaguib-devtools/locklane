import { PinStore } from './pin-store';

describe('PinStore', () => {
  beforeEach(() => localStorage.removeItem('locklane.pinnedIssues'));
  afterEach(() => localStorage.removeItem('locklane.pinnedIssues'));

  it('starts with nothing pinned', () => {
    expect(new PinStore().list()).toEqual([]);
  });

  it('pinning adds to the front of the list (most-recently-pinned first)', () => {
    const store = new PinStore();
    store.toggle(1, 1);
    store.toggle(1, 2);

    expect(store.list()).toEqual([
      { projectId: 1, issueNumber: 2 },
      { projectId: 1, issueNumber: 1 },
    ]);
  });

  it('toggling an already-pinned issue unpins it', () => {
    const store = new PinStore();
    store.toggle(1, 1);
    store.toggle(1, 1);

    expect(store.isPinned(1, 1)).toBeFalse();
    expect(store.list()).toEqual([]);
  });

  it('the same issue number in a different project is a distinct pin', () => {
    const store = new PinStore();
    store.toggle(1, 5);
    store.toggle(2, 5);

    expect(store.isPinned(1, 5)).toBeTrue();
    expect(store.isPinned(2, 5)).toBeTrue();

    store.toggle(1, 5);

    expect(store.isPinned(1, 5)).toBeFalse();
    expect(store.isPinned(2, 5)).toBeTrue();
  });

  it('persists across a new instance (simulating a reload)', () => {
    new PinStore().toggle(1, 7);

    expect(new PinStore().list()).toEqual([{ projectId: 1, issueNumber: 7 }]);
  });
});
