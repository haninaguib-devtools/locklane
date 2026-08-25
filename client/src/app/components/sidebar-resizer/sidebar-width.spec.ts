import { SIDEBAR_MAX_WIDTH, SIDEBAR_MIN_WIDTH, clampSidebarWidth } from './sidebar-width';

describe('clampSidebarWidth', () => {
  it('leaves a width inside the range unchanged', () => {
    expect(clampSidebarWidth(300)).toBe(300);
  });

  it('clamps below the minimum up to the minimum', () => {
    expect(clampSidebarWidth(10)).toBe(SIDEBAR_MIN_WIDTH);
  });

  it('clamps above the maximum down to the maximum', () => {
    expect(clampSidebarWidth(9999)).toBe(SIDEBAR_MAX_WIDTH);
  });

  it('the bounds themselves pass through unchanged', () => {
    expect(clampSidebarWidth(SIDEBAR_MIN_WIDTH)).toBe(SIDEBAR_MIN_WIDTH);
    expect(clampSidebarWidth(SIDEBAR_MAX_WIDTH)).toBe(SIDEBAR_MAX_WIDTH);
  });
});
