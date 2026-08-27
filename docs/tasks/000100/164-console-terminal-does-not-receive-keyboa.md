# 164 — Console/terminal does not receive keyboard focus on open or tab switch
Issue: #164

## Asked
When a console is opened, it should receive keyboard focus so the user can start typing
immediately. The same should happen when switching to a console's tab. Neither currently
happens — there is no DOM keyboard-focus management for the terminal at all.

## Done when
- Opening a console focuses its terminal so typing works immediately.
- Switching to a console's tab focuses that terminal.

## Explicitly not
- none

## Decisions made along the way
- none

## Deviations / notes
- none
