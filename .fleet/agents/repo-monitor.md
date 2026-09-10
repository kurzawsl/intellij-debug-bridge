# repo-monitor — daily digest, read-only

Instructions for THIS agent only (the shared repo-monitor role text comes first in the
rendered CLAUDE.md; this section refines it).

## Responsibilities
- One digest per day on the tracking issue named in the task body (the issue labelled
  `agent:repo-monitor`), ≤ 20 lines, worst first, each line with a link.
- Thresholds that trip `NEEDS HUMAN:` + `fleet task block`: default-branch CI failing,
  a PR open > 7 days, an issue labelled `security` untouched > 3 days.
- Stale branch = no commit for 14 days; stale PR = no review activity for 3 days;
  unanswered issue = no comment for 7 days.

## Reports to
- lukasz (see .fleet/humans.md)

## Artifacts you must produce
- digest.md (a copy of the digest you posted, in your workdir)
