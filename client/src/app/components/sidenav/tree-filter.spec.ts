import { filterPinnedTree, filterTree } from './tree-filter';
import { TreeNode } from '../../models/issue.model';

describe('tree-filter', () => {
  function task(
    number: number,
    title: string,
    state = 'OPEN',
    hasActiveBranch = false,
    labels: string[] = [],
  ): TreeNode {
    return { number, title, kind: 'TASK', state, hasActiveBranch, labels, children: [] };
  }

  function initiative(number: number, title: string, children: TreeNode[], state = 'OPEN'): TreeNode {
    return { number, title, kind: 'INITIATIVE', state, hasActiveBranch: false, labels: [], children };
  }

  it('with no filter text and hideShipped off, returns everything unchanged', () => {
    const tree = [task(1, 'A'), initiative(2, 'B', [task(3, 'C')])];

    expect(filterTree(tree, '', false, false)).toEqual(tree);
  });

  it('a leaf task matches by number or title, case-insensitively', () => {
    const tree = [task(42, 'Fix the thing')];

    expect(filterTree(tree, 'fix the', false, false)).toEqual(tree);
    expect(filterTree(tree, '#42', false, false)).toEqual(tree);
    expect(filterTree(tree, 'no match', false, false)).toEqual([]);
  });

  it('an initiative matching the filter keeps all of its children', () => {
    const tree = [initiative(1, 'Big project', [task(2, 'Unrelated child title')])];

    const result = filterTree(tree, 'big project', false, false);

    expect(result).toHaveSize(1);
    expect(result[0].children).toHaveSize(1);
    expect(result[0].children[0].number).toBe(2);
  });

  it('an initiative not matching the filter keeps only children that do', () => {
    const tree = [initiative(1, 'Umbrella', [task(2, 'Matches search'), task(3, 'Does not')])];

    const result = filterTree(tree, 'matches', false, false);

    expect(result).toHaveSize(1);
    expect(result[0].children).toHaveSize(1);
    expect(result[0].children[0].number).toBe(2);
  });

  it('an initiative with no matching children at all is dropped', () => {
    const tree = [initiative(1, 'Umbrella', [task(2, 'Nope'), task(3, 'Also nope')])];

    expect(filterTree(tree, 'nothing matches this', false, false)).toEqual([]);
  });

  it('hideShipped drops closed leaf tasks', () => {
    const tree = [task(1, 'Open one', 'OPEN'), task(2, 'Closed one', 'CLOSED')];

    const result = filterTree(tree, '', true, false);

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

    const result = filterTree([shipped, withOpenChild], '', true, false);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(3);
    expect(result[0].children).toHaveSize(1);
  });

  it('an active filter never resurrects a shipped node', () => {
    const tree = [task(1, 'Closed match', 'CLOSED')];

    expect(filterTree(tree, 'closed match', true, false)).toEqual([]);
  });

  it('activeBranchOnly drops leaf tasks with no active branch', () => {
    const tree = [task(1, 'Has a branch', 'OPEN', true), task(2, 'No branch', 'OPEN', false)];

    const result = filterTree(tree, '', false, true);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(1);
  });

  it('activeBranchOnly drops an initiative unless it has a surviving child with a branch', () => {
    const noBranch = initiative(1, 'No branch anywhere', [task(2, 'No branch', 'OPEN', false)]);
    const withBranch = initiative(3, 'Umbrella', [task(4, 'Has a branch', 'OPEN', true)]);

    const result = filterTree([noBranch, withBranch], '', false, true);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(3);
    expect(result[0].children).toHaveSize(1);
  });

  it('activeBranchOnly combines with hideShipped and the text filter (ANDed)', () => {
    const tree = [
      task(1, 'Match', 'CLOSED', true),
      task(2, 'Match', 'OPEN', false),
      task(3, 'Unrelated', 'OPEN', true),
      task(4, 'Match', 'OPEN', true),
    ];

    const result = filterTree(tree, 'match', true, true);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(4);
  });

  it('with no tags selected, the tag filter is a no-op', () => {
    const tree = [task(1, 'A', 'OPEN', false, ['bug']), task(2, 'B', 'OPEN', false, [])];

    expect(filterTree(tree, '', false, false, [])).toEqual(tree);
  });

  it('a tag filter drops leaf tasks that carry none of the selected tags', () => {
    const tree = [
      task(1, 'Bug', 'OPEN', false, ['bug']),
      task(2, 'Docs', 'OPEN', false, ['documentation']),
      task(3, 'Untagged', 'OPEN', false, []),
    ];

    const result = filterTree(tree, '', false, false, ['bug']);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(1);
  });

  it('selecting more than one tag matches a node carrying any of them (ORed)', () => {
    const tree = [
      task(1, 'Bug', 'OPEN', false, ['bug']),
      task(2, 'Docs', 'OPEN', false, ['documentation']),
      task(3, 'Question', 'OPEN', false, ['question']),
    ];

    const result = filterTree(tree, '', false, false, ['bug', 'documentation']);

    expect(result.map((n) => n.number)).toEqual([1, 2]);
  });

  it('a tag filter drops an initiative unless it has a surviving child with a selected tag', () => {
    const noMatch = initiative(1, 'No matching tags', [task(2, 'Untagged', 'OPEN', false, [])]);
    const withMatch = initiative(3, 'Umbrella', [task(4, 'Bug', 'OPEN', false, ['bug'])]);

    const result = filterTree([noMatch, withMatch], '', false, false, ['bug']);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(3);
    expect(result[0].children).toHaveSize(1);
  });

  it('the tag filter combines with hideShipped, activeBranchOnly, and the text filter (ANDed)', () => {
    const tree = [
      task(1, 'Match', 'CLOSED', true, ['bug']),
      task(2, 'Match', 'OPEN', false, ['bug']),
      task(3, 'Match', 'OPEN', true, ['documentation']),
      task(4, 'Match', 'OPEN', true, ['bug']),
    ];

    const result = filterTree(tree, 'match', true, true, ['bug']);

    expect(result).toHaveSize(1);
    expect(result[0].number).toBe(4);
  });
});

describe('filterPinnedTree', () => {
  function task(
    number: number,
    title: string,
    state = 'OPEN',
    hasActiveBranch = false,
    labels: string[] = [],
  ): TreeNode {
    return { number, title, kind: 'TASK', state, hasActiveBranch, labels, children: [] };
  }

  it('unlike filterTree, a shipped pinned entry is never dropped', () => {
    const pinned = [task(1, 'Closed but pinned', 'CLOSED')];

    expect(filterPinnedTree(pinned, '', true, false)).toEqual(pinned);
  });

  it('unlike filterTree, a pinned entry with no active branch is never dropped', () => {
    const pinned = [task(1, 'Pinned, no branch', 'OPEN', false)];

    expect(filterPinnedTree(pinned, '', false, true)).toEqual(pinned);
  });

  it('unlike filterTree, a pinned entry with none of the selected tags is never dropped', () => {
    const pinned = [task(1, 'Pinned, untagged', 'OPEN', false, [])];

    expect(filterPinnedTree(pinned, '', false, false, ['bug'])).toEqual(pinned);
  });

  it('the text filter still applies to a pinned entry', () => {
    const pinned = [task(1, 'Pinned thing')];

    expect(filterPinnedTree(pinned, 'no match', false, false)).toEqual([]);
  });

  it("a pinned initiative's children still respect the ship filter", () => {
    const initiative: TreeNode = {
      number: 1,
      title: 'Pinned initiative',
      kind: 'INITIATIVE',
      state: 'OPEN',
      hasActiveBranch: false,
      labels: [],
      children: [task(2, 'Open child', 'OPEN'), task(3, 'Closed child', 'CLOSED')],
    };

    const result = filterPinnedTree([initiative], '', true, false);

    expect(result[0].children.map((c) => c.number)).toEqual([2]);
  });

  it("a pinned initiative's children still respect the active-branch filter", () => {
    const initiative: TreeNode = {
      number: 1,
      title: 'Pinned initiative',
      kind: 'INITIATIVE',
      state: 'OPEN',
      hasActiveBranch: false,
      labels: [],
      children: [task(2, 'Has branch', 'OPEN', true), task(3, 'No branch', 'OPEN', false)],
    };

    const result = filterPinnedTree([initiative], '', false, true);

    expect(result[0].children.map((c) => c.number)).toEqual([2]);
  });

  it("a pinned initiative's children still respect the tag filter", () => {
    const initiative: TreeNode = {
      number: 1,
      title: 'Pinned initiative',
      kind: 'INITIATIVE',
      state: 'OPEN',
      hasActiveBranch: false,
      labels: [],
      children: [task(2, 'Bug', 'OPEN', false, ['bug']), task(3, 'Docs', 'OPEN', false, ['documentation'])],
    };

    const result = filterPinnedTree([initiative], '', false, false, ['bug']);

    expect(result[0].children.map((c) => c.number)).toEqual([2]);
  });
});
