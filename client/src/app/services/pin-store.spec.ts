import { PinStore } from './pin-store';

describe('PinStore', () => {
  beforeEach(() => localStorage.removeItem('locklane.pinnedIssues'));
  afterEach(() => localStorage.removeItem('locklane.pinnedIssues'));

  it('starts with nothing pinned', () => {
    expect(new PinStore().list()).toEqual([]);
  });

  it('pinning adds to the front of the list (most-recently-pinned first)', () => {
    const store = new PinStore();
    store.toggle(1);
    store.toggle(2);

    expect(store.list()).toEqual([2, 1]);
  });

  it('toggling an already-pinned issue unpins it', () => {
    const store = new PinStore();
    store.toggle(1);
    store.toggle(1);

    expect(store.isPinned(1)).toBeFalse();
    expect(store.list()).toEqual([]);
  });

  it('persists across a new instance (simulating a reload)', () => {
    new PinStore().toggle(7);

    expect(new PinStore().list()).toEqual([7]);
  });
});
