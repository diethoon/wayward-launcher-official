# Repository-wide Agent Instructions

These instructions are mandatory for every agent, engineer, or automated worker operating in this repository. Read this file before inspecting source paths, GitHub Actions, CI runs, or workflow logs. Do not begin workflow investigation until this file has been read.

## 1. Avoid duplicate repository and CI work

- Resolve the target repository, branch, and commit SHA once at the beginning. Reuse those exact values throughout the task.
- When locating source files, inspect the relevant repository tree/path once, record the exact paths, and reuse them. Do not repeat equivalent path searches with different wording.
- Do not issue multiple semantically identical GitHub Actions searches (for example: "workflow jobs", "CI job status", "build status", and "run status") for the same run.
- Keep a compact working record of the selected workflow run ID, branch, head SHA, event, and job IDs/statuses. Reuse it instead of rediscovering the same run.

## 2. Required single-pass CI inspection

For a CI/workflow status or log request, use this sequence:

1. Identify the requested repository and target branch. If not specified, use the branch currently being worked on; do not silently switch to the default branch.
2. List workflow runs once, filtering by the target branch where supported.
3. Select the relevant run by branch, head SHA, workflow name, event, and recency. If multiple runs are equally plausible, state the ambiguity or ask one concise question; do not inspect every run indiscriminately.
4. Fetch the selected run's jobs/statuses once and record every job's name, conclusion/status, and URL/ID.
5. Fetch logs only for failed, timed-out, cancelled, or otherwise diagnostically relevant jobs. Fetch successful-job logs only when the user explicitly asks or a specific inconsistency requires it.
6. Produce one consolidated report: run ID/link, branch, commit SHA, overall status, each job's status, failure cause, and whether artifacts were actually uploaded.
7. If the run is still queued/in progress, report that state accurately. Recheck only when a later status is necessary to answer the user; reuse the same run ID and do not search for a new run.

## 3. What counts as completion

- Distinguish workflow conclusion from individual job conclusions. A green build does not mean the entire workflow passed if artifact upload or another required job failed.
- Distinguish build/test/publish success from artifact-upload success, and state storage quota, permission, or retention failures separately.
- Never call a run "fully passed", "CI green", "artifact available", or "verified" unless the corresponding run/job/artifact evidence confirms it.
- Do not claim physical-device, real-network, UI, or production validation from CI results alone.
- A rerun is not a substitute for inspecting the original failure. Do not trigger reruns, dispatch workflows, cancel runs, or change workflow files unless the user authorized that action or it is explicitly part of the task.

## 4. Minimal tool usage and no redundant verification

- One run-list query + one job-list query is the default for a single CI status request.
- Read only the logs needed to diagnose failures. Do not download all logs or artifacts "just in case".
- Do not repeat a query because its wording can be improved. Repeat only when the previous result is demonstrably incomplete, stale, or failed, and explain why.
- Do not re-fetch a result already available in the current task context unless its freshness is material and cannot otherwise be established.
- Do not inspect unrelated workflows, branches, commits, or source directories.
- Do not create progress narration that merely says the same lookup is underway. Report useful findings as soon as evidence supports them.

## 5. Final report format

Report once, concisely:

- Repository / branch / commit
- Workflow name, run ID, run URL, event, and timestamp
- Overall status
- Job-by-job status (successful, failed, cancelled, skipped, queued, or in progress)
- Exact failure reason, supported by the relevant log excerpt
- Artifact status (uploaded / unavailable / not configured / not checked)
- Scope limitations (for example, CI-only; no physical-device validation)

Do not repeat the same facts under multiple headings. Separate verified facts from assumptions and unverified items.

## 6. Change safety

- Do not modify application code, workflow definitions, branch protections, secrets, or release settings merely to investigate CI.
- Preserve existing uncommitted work and the user's current branch.
- Never force-push, merge to the default branch, publish, or deploy without explicit authorization.
- Keep changes minimal and limited to the requested scope.
