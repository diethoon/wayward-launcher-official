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

# Wayward Engineering Instructions

These instructions apply to all engineering work in this repository and take precedence over task-specific preferences unless a task explicitly requires otherwise.

## Execution and verification first

The goal is to deliver a working product, not to maximize investigation.

- Prefer checking the current build, actual launch/runtime behavior, and the latest GitHub Actions results over repeating source investigations or forensic analysis.
- Before investigating, inspect the latest handoff/task notes and reuse already-established findings. Do not repeat an investigation whose conclusion is already documented unless new evidence, a changed commit, or a reproducible failure makes it necessary.
- Follow this order: confirm the latest branch/HEAD and working changes; read the relevant handoff; inspect the latest CI results; build and run the affected application; investigate only concrete failures; make the smallest targeted fix; run relevant tests and CI again; update the handoff.
- Do not spend extended time re-proving known facts when a build, smoke test, runtime check, or CI run can answer the question directly.
- Do not continue forensic analysis as a substitute for execution. Once the available evidence is sufficient to identify the next action, act on it.
- Preserve working code and user configuration. Do not rewrite or refactor functioning components without a demonstrated need.
- Avoid speculative improvements, unrelated feature work, and broad refactors during a verification/fix task.
- Parallelize independent checks where safe. Do not create unnecessary pauses or repetitive progress reports.

## Evidence and reporting

Always distinguish:
1. **Runtime verified** — actually launched and exercised in the stated environment.
2. **Automated tests passed** — the named tests completed successfully.
3. **GitHub Actions passed** — cite the exact workflow run.
4. **Code inspected only** — implementation was reviewed but not executed.
5. **Not verified** — required hardware, device, network, credentials, or external environment was unavailable.

Never describe a build, mock test, or source inspection as proof of real-device, real-GPU, router, or external-network behavior. Report limitations plainly.

## Safe change policy

- Fix only demonstrated defects, using the smallest practical change.
- Do not delete or weaken tests merely to obtain a green CI result.
- Do not overwrite user data, existing configuration, model files, or unrelated in-progress changes.
- Do not reset, force-push, merge, release, deploy, or change external infrastructure unless explicitly authorized.
- Update the repository handoff/documentation with the actual changes, verification performed, commit, and remaining unverified items.


## Mandatory global policy documents — read before any task
- `docs/WORKFLOW_GATEWAY.md`
- `docs/WORKFLOW_EXECUTION_POLICY.md`
- `docs/FORENSIC_AND_VERIFICATION_POLICY.md`

Read all three documents before inspecting code, branches, services, CI, or starting implementation. If any is unavailable, restore it when authorized or stop product work and report the exact blocker. This requirement supplements all repository-specific instructions below; preserve their CI, build, ownership, and platform-specific rules. The global forensic, evidence-integrity, safety, and data-preservation rules cannot be weakened.


# ABSOLUTE RULE — MINIMUM CHANGE / MINIMUM IMPLEMENTATION / INCREMENTAL TESTING

**MANDATORY BEFORE EVERY TASK, ON EVERY BRANCH.** Read and obey `docs/ABSOLUTE_MINIMUM_CHANGE_IMPLEMENTATION_VERIFICATION.md` before investigation or implementation. This is a repository-wide, non-negotiable engineering invariant. It overrides conflicting workflow preferences unless the user explicitly authorizes a specific exception; safety, data integrity, and truthful reporting are never waived.

- Make only the smallest evidence-backed change required by the request or confirmed defect.
- Before editing, pin the repository, branch, base commit, working state, exact affected files/functions/call path, and evidence for the cause.
- Implement only the required behavior. No speculative fixes, unrelated cleanup, broad refactors, rewrites, extra features, or unnecessary dependencies.
- Preserve existing behavior, compatibility, data formats, IDs, settings, user data, and project-specific invariants.
- Test incrementally: baseline before edits; the narrowest relevant check immediately after each logical change; integration checks at boundaries; then targeted regression, full applicable tests, build/package, and runtime/device verification.
- Never stack unverified edits. On a failure, stop, inspect the actual evidence, isolate introduced vs pre-existing failures, and apply only the smallest correction.
- Review the final diff and prove every changed line is necessary. A test not run is not passed; code inspection/build/mock is not proof of runtime or physical-device behavior.
- Report exact changed files/functions, why each change was necessary, incremental and final test outcomes, and all unverified items. Never claim full completion when required verification is blocked.

**REQUIRED ORDER:** evidence → minimum safe change → minimum implementation → immediate incremental test → regression/integration verification → final diff review → truthful report.


---

# ABSOLUTE CHIEF-OF-STAFF PRE-FLIGHT — READ FIRST, EVERY TASK

**NON-NEGOTIABLE.** Before any code/configuration edit, implementation, build, test, deployment investigation, or task direction, read and obey:
- `docs/ABSOLUTE_MINIMUM_CHANGE_IMPLEMENTATION_VERIFICATION.md`

The required sequence is:
1. Pin repository, branch, base commit, HEAD, working-tree state, PRs, and parallel work.
2. Read prior handoffs, decisions, relevant project rules, previous diffs, and existing verified findings.
3. Retrieve and inspect ALL relevant available logs and evidence first: app/service/device/runtime logs, crash reports, diagnostic bundles, stdout/stderr, CI/workflow/job logs, test reports, build artifacts, deployment status, issue/PR discussion, commit history, and exact failure/version/session context.
4. Correlate evidence into a timeline. Separate facts, inferences, hypotheses, and unknowns. Do not mix unrelated builds, sessions, devices, or reports.
5. Trace the complete relevant execution path and identify the confirmed root cause and every necessary correction. Do not guess or code before this understanding exists.
6. Before editing, specify exact files/classes/functions/call paths, why each location must change, what must remain untouched, acceptance criteria, and produce a complete reviewable minimal diff. Explain every changed hunk.
7. Implement only that minimum diff, using the fastest safe execution order. No speculative fixes, unrelated cleanup, broad refactors, or bonus features.
8. Test incrementally: baseline first; immediately test after each logical change; verify each integration boundary; then run targeted regression, applicable full tests, build/package, and runtime/device checks.
9. Inspect the final diff and actual test/log/artifact results. Report PASS / FAIL / BLOCKED / NOT RUN accurately. Never claim unrun checks passed.

**Do not ask the user to retrieve logs, poll routine jobs, or repeat information that available tools can obtain.** If a source is inaccessible, state the exact blocker and continue all independent evidence collection and work.

**Completion is forbidden** until relevant available evidence has been inspected, the minimal diff is justified, incremental tests have been checked, and final state is verified. If required evidence is unavailable, report PARTIALLY VERIFIED / BLOCKED.

This is mandatory on every branch and for every contributor/agent. It supplements project-specific rules; preserve stricter platform, security, CI, and ownership constraints.
