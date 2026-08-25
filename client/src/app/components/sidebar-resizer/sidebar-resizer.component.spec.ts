import { SidebarResizerComponent } from './sidebar-resizer.component';
import { SIDEBAR_MAX_WIDTH, SIDEBAR_MIN_WIDTH } from './sidebar-width';

describe('SidebarResizerComponent', () => {
  function keyEvent(key: string): KeyboardEvent {
    return new KeyboardEvent('keydown', { key, cancelable: true });
  }

  it('ArrowRight widens by the step, ArrowLeft narrows by the step', () => {
    const c = new SidebarResizerComponent();
    c.width = 264;
    let emitted: number | undefined;
    c.widthChange.subscribe((w) => (emitted = w));

    c.onKeyDown(keyEvent('ArrowRight'));
    expect(emitted).toBe(280);

    c.onKeyDown(keyEvent('ArrowLeft'));
    expect(emitted).toBe(248);
  });

  it('Home jumps to the minimum, End jumps to the maximum', () => {
    const c = new SidebarResizerComponent();
    c.width = 300;
    let emitted: number | undefined;
    c.widthChange.subscribe((w) => (emitted = w));

    c.onKeyDown(keyEvent('Home'));
    expect(emitted).toBe(SIDEBAR_MIN_WIDTH);

    c.onKeyDown(keyEvent('End'));
    expect(emitted).toBe(SIDEBAR_MAX_WIDTH);
  });

  it('ArrowRight never emits past the maximum', () => {
    const c = new SidebarResizerComponent();
    c.width = SIDEBAR_MAX_WIDTH - 5;
    let emitted: number | undefined;
    c.widthChange.subscribe((w) => (emitted = w));

    c.onKeyDown(keyEvent('ArrowRight'));

    expect(emitted).toBe(SIDEBAR_MAX_WIDTH);
  });

  it('an unrelated key does nothing', () => {
    const c = new SidebarResizerComponent();
    c.width = 264;
    let emitted: number | undefined;
    c.widthChange.subscribe((w) => (emitted = w));

    c.onKeyDown(keyEvent('a'));

    expect(emitted).toBeUndefined();
  });
});
