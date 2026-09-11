import { TestBed } from '@angular/core/testing';
import { AttentionStore } from './attention-store';
import { EventsService } from './events.service';

// Session ids ("<projectId>-console[-<hex>]"), "<repo>-console-<hex>" worktree directories, the
// /console and /consoles REST paths and the 'console' route segment below keep their persisted and
// on-the-wire shape: compatibility surfaces kept under ADR-112 (#766 renamed only the identifiers).

describe('AttentionStore (#791)', () => {
  let store: AttentionStore;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(AttentionStore);
  });

  /** Reaches past EventsService's public API (#129) -- there is no other way to fake an incoming socket message. */
  function emitAppEvent(event: unknown): void {
    (TestBed.inject(EventsService) as unknown as { eventsSubject: { next: (e: unknown) => void } }).eventsSubject.next(
      event,
    );
  }

  it('reports nothing waiting until an event arrives', () => {
    expect(store.isWaiting('1-7-rename-toggle')).toBeFalse();
    expect(store.waiting().size).toBe(0);
  });

  it('a waiting event marks that session, an active event clears it', () => {
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting' });
    expect(store.isWaiting('1-7-rename-toggle')).toBeTrue();
    expect(store.isWaiting('1-8-other')).toBeFalse();

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'active' });
    expect(store.isWaiting('1-7-rename-toggle')).toBeFalse();
  });

  it('tracks sessions independently: one going active does not clear another still waiting', () => {
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-a', state: 'waiting' });
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-b', state: 'waiting' });
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-console-a', state: 'active' });

    expect(store.isWaiting('1-console-a')).toBeFalse();
    expect(store.isWaiting('1-console-b')).toBeTrue();
    expect(Array.from(store.waiting())).toEqual(['1-console-b']);
  });

  it('ignores every other event type, and a malformed consoleAttention message', () => {
    emitAppEvent({ type: 'consolesChanged', projectId: 1 });
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle' });
    emitAppEvent({ type: 'consoleAttention', sessionId: 7, state: 'waiting' });
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'idle' });

    expect(store.waiting().size).toBe(0);
  });

  it('exposes the set as a signal that only changes when the state actually changes', () => {
    const before = store.waiting();
    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'active' });
    expect(store.waiting()).toBe(before); // already not waiting: no new set

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting' });
    const afterWaiting = store.waiting();
    expect(afterWaiting).not.toBe(before);

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting' });
    expect(store.waiting()).toBe(afterWaiting); // repeat: same set, no notification

    emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'active' });
    expect(store.waiting()).not.toBe(afterWaiting);
    expect(store.isWaiting('1-7-rename-toggle')).toBeFalse();
  });

  it('applies an event handed to it directly, the same as one off the channel', () => {
    store.apply({ type: 'consoleAttention', sessionId: '2-9-other-project', state: 'waiting' });
    expect(store.isWaiting('2-9-other-project')).toBeTrue();
  });

  describe('reason (#854)', () => {
    it('reports null until a waiting event with a reason arrives', () => {
      expect(store.reason('1-7-rename-toggle')).toBeNull();
    });

    it('a waiting event records its reason, and an active event clears it', () => {
      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'quiet' });
      expect(store.reason('1-7-rename-toggle')).toBe('quiet');

      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'active' });
      expect(store.reason('1-7-rename-toggle')).toBeNull();
    });

    it('a quiet-to-bell upgrade updates the reason even though isWaiting stays true throughout', () => {
      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'quiet' });
      const waitingAfterQuiet = store.waiting();
      expect(store.reason('1-7-rename-toggle')).toBe('quiet');

      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });

      expect(store.isWaiting('1-7-rename-toggle')).toBeTrue();
      expect(store.reason('1-7-rename-toggle')).toBe('bell');
      // isWaiting never changed, but the reason did -- readers of `waiting` alone see
      // no update, which is correct; only a `reason()` reader is notified.
      expect(store.waiting()).toBe(waitingAfterQuiet);
    });

    it('a repeat waiting event with the same reason changes nothing', () => {
      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });
      const waitingAfterFirst = store.waiting();

      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });

      expect(store.waiting()).toBe(waitingAfterFirst);
      expect(store.reason('1-7-rename-toggle')).toBe('bell');
    });
  });

  describe('changes$ (#859)', () => {
    function collect(): { sessionId: string; waiting: boolean; reason: string | null }[] {
      const seen: { sessionId: string; waiting: boolean; reason: string | null }[] = [];
      store.changes$.subscribe((change) => seen.push(change));
      return seen;
    }

    it('emits once for a session becoming waiting with a reason', () => {
      const seen = collect();

      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });

      expect(seen).toEqual([{ sessionId: '1-7-rename-toggle', waiting: true, reason: 'bell' }]);
    });

    it('emits again on the quiet-to-bell upgrade, even though isWaiting stays true throughout', () => {
      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'quiet' });
      const seen = collect();

      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });

      expect(seen).toEqual([{ sessionId: '1-7-rename-toggle', waiting: true, reason: 'bell' }]);
    });

    it('emits with reason null when a session goes active', () => {
      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });
      const seen = collect();

      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'active' });

      expect(seen).toEqual([{ sessionId: '1-7-rename-toggle', waiting: false, reason: null }]);
    });

    it('never emits for a repeat that changes nothing', () => {
      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });
      const seen = collect();

      emitAppEvent({ type: 'consoleAttention', sessionId: '1-7-rename-toggle', state: 'waiting', reason: 'bell' });

      expect(seen).toEqual([]);
    });
  });
});
