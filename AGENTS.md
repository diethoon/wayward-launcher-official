# GLOBAL WORK-CONTINUITY RULE — READ FIRST, APPLY ALWAYS

**MANDATORY PRE-FLIGHT:** Before any task, repository/branch inspection, service lookup, tool call, build, test, or deployment investigation, read and apply this section first. It supplements, and does not replace, project-specific instructions below.

## End-to-end ownership
- The user is the requester, not the operator of the workflow. Never make the user monitor routine progress, inspect results, poll jobs, download logs, check artifacts, or tell you when to continue.
- Never stop after launching an asynchronous action, CI run, build, test, upload, or download. Retain its identifiers, track it yourself, inspect the actual result, and continue.
- Never send a progress-only message and then wait for the user's next prompt. A progress update does not substitute for doing the work.
- Never ask the user to perform checks that available tools and permissions allow you to perform.

## No avoidable waiting
- While jobs run, perform independent safe work. Parallelize independent tasks; serialize only genuine dependencies or shared-resource writes.
- Poll/query asynchronous work yourself. On completion, inspect the result immediately.
- For failures, retrieve and inspect logs, test reports, and failure artifacts yourself; diagnose, fix within scope, and rerun only necessary checks.
- For artifacts, list, download, inspect, verify existence/size/integrity, and test the download URL yourself. An upload message alone is not proof an artifact is downloadable.
- If one path is blocked, continue all independent work and pursue alternatives. Never freeze the whole task because one subtask is waiting.

## Blockers and user involvement
- Routine status checks, CI/build completion, artifact inspection, log retrieval, and ordinary resource lookups are agent responsibilities, not reasons to pause or ask the user.
- A blocker is valid only when a specific external dependency, permission, credential, physical device, or explicit approval is demonstrably required. Identify the exact blocker and its evidence; continue every independent part.
- Never wait for physical-device verification before completing code, automated tests, packaging, and delivery when those can proceed independently.
- Ask the user only for an indispensable action that genuinely requires their authority. Ask one concise actionable question, not a broad request to supervise the work.

## Evidence and safety
- Never claim a command, test, CI run, deployment, upload, download, artifact, or runtime check succeeded unless its actual result was inspected.
- Distinguish code review, automated tests, CI, emulator, and physical-device evidence. A mock/emulator result is not proof of physical-device behavior.
- Reuse verified findings; do not repeat investigations or broad suites without a concrete reason.
- Work autonomously within scope and permissions. Do not force-push, reset, merge, release, deploy, alter production infrastructure, delete user data, expose secrets, or make high-impact irreversible changes unless explicitly authorized.
- Preserve unrelated work, branch boundaries, project isolation, and all existing project-specific instructions.

## Mandatory finalization
Before reporting completion, verify the final repository state, commit/result identifiers, actual test outcomes, and deliverable paths/links. State exactly what remains blocked and why.

**NON-NEGOTIABLE:** Do not abandon, park, or hand routine workflow management to the user. Track every asynchronous step, inspect evidence, resolve what can be resolved, continue independent work, and deliver the most complete verified result possible.

---

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


## Mandatory global policy documents — read before any task
- `docs/WORKFLOW_GATEWAY.md`
- `docs/WORKFLOW_EXECUTION_POLICY.md`
- `docs/FORENSIC_AND_VERIFICATION_POLICY.md`

Read all three documents before inspecting code, branches, services, CI, or starting implementation. If any is unavailable, restore it when authorized or stop product work and report the exact blocker. This requirement supplements all repository-specific instructions below; preserve their CI, build, ownership, and platform-specific rules. The global forensic, evidence-integrity, safety, and data-preservation rules cannot be weakened.
