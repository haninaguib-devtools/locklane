# <id> — <Title>
Issue: #<id>[ · Part of: #<tracking>]

## Asked
<the goal, from the issue — self-sufficient>

## Done when
<observable criteria, from the issue>

## Explicitly not
<exclusions; each deferred item names its issue: "… — split to #NNN">

## Origin
<system: name / url: durable URL, from the issue's own `## Origin` section — or
"Inherited from initiative #<tracking> — see its own Origin." when this task's issue
has no Origin of its own but its parent initiative does — or "none">

## Verification
<one entry per the plan's `verification:` list, in the same order — or "none". Each:
- role: <role> — required: <true/false>
  what: <what must be checked>
  state: pending | verified | rejected | risk-accepted
  evidence: <what was supplied, or "awaiting"> — revision: `<commit sha, or "none yet">`
  by: <who> — date: <when, or "—">
  risk: <only when state is risk-accepted — the residual risk being accepted, and why;
    never worded as a passing check>
>

## Feedback
<one entry per feedback pass (`docs/architecture/feedback-pass.md`) — or "none". Each:
- reference: <durable URL or comment permalink, verbatim — never fetched>
  source: <who or what surfaced it>
  classification: clarification | defect | in-scope adjustment | proposed scope
    expansion
  response: <what changed in response, or why nothing did>
  by: <who ran the pass> — date: <when>
>

## Decisions made along the way
- <decision (who, date)> — or "none"

## Deviations / notes
- <deviation + who approved it in the moment; dead ends worth remembering>
  — or "none"
