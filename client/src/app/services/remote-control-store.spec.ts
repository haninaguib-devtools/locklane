import { TestBed } from '@angular/core/testing';
import { RemoteControlStore } from './remote-control-store';

const STORAGE_KEY = 'locklane.remoteControl';

describe('RemoteControlStore (#979)', () => {
  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  it('is off with no stored preference', () => {
    const store = TestBed.inject(RemoteControlStore);

    expect(store.enabled()).toBeFalse();
  });

  it('updates the signal immediately on set', () => {
    const store = TestBed.inject(RemoteControlStore);

    store.setEnabled(true);

    expect(store.enabled()).toBeTrue();
  });

  it('remembers the choice across instances (i.e. across reloads)', () => {
    TestBed.inject(RemoteControlStore).setEnabled(true);

    expect(TestBed.inject(RemoteControlStore).enabled()).toBeTrue();
  });
});
