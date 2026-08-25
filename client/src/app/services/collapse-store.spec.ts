import { CollapseStore } from './collapse-store';

describe('CollapseStore', () => {
  beforeEach(() => localStorage.removeItem('locklane.collapsedInitiatives'));
  afterEach(() => localStorage.removeItem('locklane.collapsedInitiatives'));

  it('starts with nothing collapsed', () => {
    expect(new CollapseStore().isCollapsed(1)).toBeFalse();
  });

  it('toggling folds and unfolds', () => {
    const store = new CollapseStore();
    store.toggle(1);
    expect(store.isCollapsed(1)).toBeTrue();

    store.toggle(1);
    expect(store.isCollapsed(1)).toBeFalse();
  });

  it('persists across a new instance (simulating a reload)', () => {
    new CollapseStore().toggle(5);

    expect(new CollapseStore().isCollapsed(5)).toBeTrue();
  });
});
