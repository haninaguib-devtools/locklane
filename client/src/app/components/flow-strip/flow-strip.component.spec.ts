import { FlowStripComponent } from './flow-strip.component';
import { FlowStep } from '../../models/issue.model';

describe('FlowStripComponent', () => {
  function steps(...done: boolean[]): FlowStep[] {
    return ['open', 'plan', 'work', 'review', 'ship'].map((name, i) => ({ name, done: done[i] }));
  }

  it('the current step is the first not-done step', () => {
    const c = new FlowStripComponent();
    c.steps = steps(true, true, false, false, false);
    expect(c.currentIndex()).toBe(2);
  });

  it('the current step is the last one once everything is done', () => {
    const c = new FlowStripComponent();
    c.steps = steps(true, true, true, true, true);
    expect(c.currentIndex()).toBe(4);
  });

  it('the current step is the first one when nothing is done yet', () => {
    const c = new FlowStripComponent();
    c.steps = steps(false, false, false, false, false);
    expect(c.currentIndex()).toBe(0);
  });
});
