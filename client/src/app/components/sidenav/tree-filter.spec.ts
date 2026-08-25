import { filterPinnedTree, filterTree } from './tree-filter';
import { TreeNode } from '../../models/issue.model';

describe('tree-filter', () => {
  function task(number: number, title: string, state = 'OPEN'): TreeNode {
    return { number, title, kind: 'TASK', state, children: [] };
  }

  function initiative(number: number, title: string, children: TreeNode[], state = 'OPEN'): TreeNode {
    return { number, title, kind: 'INITIATIVE', state, children };
  }

  it('with no filter text and hideShipped off, returns everything unchanged', () => {
    const tree = [task(1, 'A'), initiative(2, 'B', [task(3, 'C')])];

    expect(filterTree(tree, '', false)).toEqual(tree);
  });

  it('a leaf task matches by number or title, case-insensitively', () => {
    const tree = [task(42, 'Fix the thing')];

    expect(filterTree(tree, 'fix the', false)).toEqual(tree);
    expect(filterTree(tree, '#42', false)).toEqual(tree);
    expect(filterTree(tree, 'no match', false)).toEqual([]);
  });

  it('an initiative matching the filter keeps all of its children', () => {
    const tree = [initiative(1, 'Big project', [task(2, 'Unrelated child title')])];

    const result = filterTree(tree, 'big project', false);

    expect(result).toHaveSize(1);
    expect(result[0].children).toHaveSize(1);
    expect(result[0].children[0].number).toBe(2);
  });

  it('an initiative not matching the filter keeps only children that do', () => {
    const tree = [initiative(1, 'Umbrella', [task(2, 'Matches search'), task(3, 'Does not')])];

    const result = filterTree(tree, 'matches', false);

    expect(result).toHaveSize(1);
    expect(result[0].children).toHaveSize(1);
    expect(result[0].children[0].number).toBe(2);
  });

  it('an initiative with no matching children at all is dropped', () => {
    const tree = [initiative(1, 'Umbrella', [task(2, 'Nope'), task(3, 'Also nope')])];

    expect(filterTree(tree, 'nothing matches this', false)).toEqual([]);
  });

  it('hideShipped drops closed leaf tasks', () => {
    const tree = [task(1, 'Open one', 'OPEN'), task(2, 'Closed one', 'CLOSED')];

    const result = filterTree(tree, '', true);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(1);
  });

  it('hideShipped drops a closed initiative unless it has a surviving open child', () => {
    const shipped = initiative(1, 'Shipped umbrella', [task(2, 'Also closed', 'CLOSED')], 'CLOSED');
    const withOpenChild = initiative(
      3,
      'Closed umbrella, open child',
      [task(4, 'Still open', 'OPEN')],
      'CLOSED',
    );

    const result = filterTree([shipped, withOpenChild], '', true);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(3);
    expect(result[0].children).toHaveSize(1);
  });

  it('an active filter never resurrects a shipped node', () => {
    const tree = [task(1, 'Closed match', 'CLOSED')];

    expect(filterTree(tree, 'closed match', true)).toEqual([]);
  });
});

describe('filterPinnedTree', () => {
  function task(number: number, title: string, state = 'OPEN'): TreeNode {
    return { number, title, kind: 'TASK', state, children: [] };
  }

  it('unlike filterTree, a shipped pinned entry is never dropped', () => {
    const pinned = [task(1, 'Closed but pinned', 'CLOSED')];

    expect(filterPinnedTree(pinned, '', true)).toEqual(pinned);
  });

  it('the text filter still applies to a pinned entry', () => {
    const pinned = [task(1, 'Pinned thing')];

    expect(filterPinnedTree(pinned, 'no match', false)).toEqual([]);
  });

  it("a pinned initiative's children still respect the ship filter", () => {
    const initiative: TreeNode = {
      number: 1,
      title: 'Pinned initiative',
      kind: 'INITIATIVE',
      state: 'OPEN',
      children: [task(2, 'Open child', 'OPEN'), task(3, 'Closed child', 'CLOSED')],
    };

    const result = filterPinnedTree([initiative], '', true);

    expect(result[0].children.map((c) => c.number)).toEqual([2]);
  });
});
