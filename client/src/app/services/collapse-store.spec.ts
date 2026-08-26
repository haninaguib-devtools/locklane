import { CollapseStore } from './collapse-store';

describe('CollapseStore', () => {
  beforeEach(() => localStorage.removeItem('locklane.collapsedInitiatives'));
  afterEach(() => localStorage.removeItem('locklane.collapsedInitiatives'));

  it('starts with nothing collapsed', () => {
    expect(new CollapseStore().isCollapsed(1, 1)).toBeFalse();
  });

  it('toggling folds and unfolds', () => {
    const store = new CollapseStore();
    store.toggle(1, 1);
    expect(store.isCollapsed(1, 1)).toBeTrue();

    store.toggle(1, 1);
    expect(store.isCollapsed(1, 1)).toBeFalse();
  });

  it('the same issue number in a different project is a distinct fold', () => {
    const store = new CollapseStore();
    store.toggle(1, 5);

    expect(store.isCollapsed(1, 5)).toBeTrue();
    expect(store.isCollapsed(2, 5)).toBeFalse();
  });

  it('persists across a new instance (simulating a reload)', () => {
    new CollapseStore().toggle(1, 5);

    expect(new CollapseStore().isCollapsed(1, 5)).toBeTrue();
  });
});
