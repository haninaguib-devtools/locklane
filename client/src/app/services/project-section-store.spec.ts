import { ProjectSectionStore } from './project-section-store';

describe('ProjectSectionStore', () => {
  beforeEach(() => localStorage.removeItem('locklane.collapsedProjectSections'));
  afterEach(() => localStorage.removeItem('locklane.collapsedProjectSections'));

  it('starts with nothing collapsed', () => {
    expect(new ProjectSectionStore().isCollapsed(1)).toBeFalse();
  });

  it('toggling folds and unfolds', () => {
    const store = new ProjectSectionStore();
    store.toggle(1);
    expect(store.isCollapsed(1)).toBeTrue();

    store.toggle(1);
    expect(store.isCollapsed(1)).toBeFalse();
  });

  it('persists across a new instance (simulating a reload)', () => {
    new ProjectSectionStore().toggle(3);

    expect(new ProjectSectionStore().isCollapsed(3)).toBeTrue();
  });
});
