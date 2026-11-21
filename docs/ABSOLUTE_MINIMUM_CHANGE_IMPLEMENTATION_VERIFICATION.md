# ABSOLUTE CHIEF-OF-STAFF ENGINEERING DIRECTIVE
## Complete evidence first · exact minimal diff · fastest safe implementation · continuous verification

**AUTHORITY: REPOSITORY-WIDE / ALL TASKS / ALL BRANCHES / ALL CONTRIBUTORS AND AGENTS**

This is a mandatory operating rule, not optional guidance. It applies to every task in this repository: incident response, bug fixes, features, diagnostics, migrations, refactors, builds, releases, and infrastructure work. Every task owner and delegated agent must read and follow it before acting. Task-specific instructions may add stricter requirements but may not silently weaken this directive. Only the user's explicit instruction can authorize a narrowly stated exception; safety, data integrity, evidence integrity, and truthful reporting are never waived.

# 1. Chief-of-staff first: establish the complete situation before changing anything

**The chief of staff must understand the whole relevant incident/task before directing implementation. Coding first and investigating later is prohibited.**

Before editing code, changing configuration, launching a fix, or asking the user to repeat information:

1. Pin the exact repository, target branch, base commit, current HEAD, working-tree state, active PRs, and any parallel/in-progress work. Preserve all existing work.
2. Read the current task, prior handoffs, relevant project instructions, known decisions, previous investigation results, and already-produced diffs. Reuse verified evidence; do not restart completed investigations without a concrete reason.
3. Collect and inspect all relevant evidence already available:
   - application, service, host, device, and runtime logs;
   - crash reports, diagnostic bundles, telemetry, event journals, and server stdout/stderr;
   - CI/workflow runs, job logs, test reports, build outputs, artifacts, and deployment/runtime status;
   - issue/PR discussions, review comments, commit history, recent changes, and configuration relevant to the failure;
   - reproducible user observations and exact failure timestamps, versions, device/OS/runtime details.
4. Retrieve missing evidence through available repository, CI, deployment, service, diagnostic, and connected-app tools. Do not ask the user to fetch logs or poll routine jobs when the agent can retrieve them. If one source is unavailable, record the exact limitation and continue collecting every independent source.
5. Correlate evidence into a single timeline. Identify which version/build/device/session/process/request produced each event. Separate facts, strong inferences, hypotheses, and unknowns. Do not combine different installs, sessions, builds, or reports without proof they belong together.
6. Trace the complete relevant execution path from user action to the observed outcome: UI/event → caller → service/module → process/network/storage/API → result/error → state/UI → diagnostics/log persistence/package/upload, as applicable.
7. Identify every confirmed defect and every required correction in the requested scope. Distinguish root cause from symptoms, downstream effects, unrelated defects, and environmental blockers.
8. Do not claim the investigation is complete while a relevant available log, CI result, diagnostic artifact, or execution path remains uninspected. Do not perform endless unrelated exploration: stop when evidence is sufficient to explain the failure and safely define the complete minimal patch.

**Pre-change evidence gate:** No implementation begins until the chief of staff can state, with evidence:
- what failed and how it was observed;
- the confirmed root cause, or the exact remaining uncertainty;
- the complete relevant call/data/control path;
- the precise code/configuration locations responsible;
- which behavior must change and which behavior must remain untouched;
- how the fix will be proven.

If a root cause cannot be proven, label it unconfirmed and design the smallest discriminating test/instrumentation step. Never disguise a hypothesis as a finding.

# 2. Exact minimal-diff direction before implementation

After completing the evidence review, the chief of staff must prepare a precise change specification before editing:

- List each exact file, class/module, function/method, event handler, configuration key, workflow, or packaging step that needs modification.
- For each location, state the verified defect, evidence, intended behavior, and why that particular change is necessary.
- Specify the smallest safe code change and its dependencies. Identify the exact callers, consumers, compatibility boundaries, and lifecycle effects.
- State explicitly which files and systems must not change, including unrelated features, stable APIs, schemas, data formats, user settings, credentials, save data, IDs, RNG, undo/history, economic/game rules, and production infrastructure where applicable.
- Define acceptance criteria and the smallest tests that distinguish success from failure.
- Produce a reviewable, complete diff (or a precise patch plan if implementation has not yet begun). The diff must contain only required changes; explain every changed hunk. No speculative or placeholder diff.
- Choose the fastest safe execution order, parallelizing independent read-only checks and serializing only dependent or conflicting writes.

The diff is the implementation contract. If new evidence requires changing it, update the rationale and diff before proceeding with the newly expanded change. Do not silently grow scope.

# 3. Absolute minimum-change rule

- Make only the smallest evidence-backed change necessary to satisfy the request or correct a confirmed defect.
- No unrelated cleanup, formatting churn, renaming, dependency upgrades, architectural redesign, broad refactoring, rewrites, speculative hardening, or bonus features.
- Reuse existing project patterns, dependencies, state ownership, lifecycle, error handling, logging, packaging, and tests.
- Do not replace functioning code merely because a new implementation is easier.
- If several defects are confirmed, fix only those within the requested scope and necessary to achieve the acceptance criteria. Keep independently shippable changes separate where appropriate.
- If a safe correction genuinely spans multiple files, change only the minimum connected set and explain each dependency.
- Never weaken validation, remove tests, suppress errors, falsify diagnostics, or loosen acceptance criteria to obtain a green result.
- Preserve existing working behavior, compatibility contracts, and user data. Never reset or overwrite unrelated user changes.

# 4. Minimum implementation and fastest safe execution

- Implement only the required behavior defined by the evidence-backed diff and acceptance criteria.
- Prefer a localized patch over new abstractions, services, frameworks, dependencies, or cross-module changes.
- Use the shortest reliable path to a verified result. Avoid repeated broad scans, duplicate investigations, unnecessary builds, and redundant test suites.
- Run independent checks concurrently when safe. Track asynchronous jobs to completion and inspect their actual outputs.
- Do not make multiple unverified changes in a batch.
- Do not stop after creating a PR, starting a workflow, launching a build, or uploading an artifact. Continue through result inspection and applicable verification.
- If an implementation path fails, inspect the actual failure, correct only the necessary cause, and resume. Do not abandon independent work.

# 5. Incremental verification during implementation — mandatory

Verification is continuous and must accompany implementation, not be postponed until the end.

**Stage A — Baseline, before edits**
- Inspect existing relevant tests and latest CI/build/runtime evidence.
- Run the smallest useful baseline check when feasible.
- Record pre-existing failures separately so they are not misattributed to the patch.

**Stage B — Every logical change**
- Immediately after each logical code/configuration change, run the narrowest relevant unit test, static check, syntax check, or reproducible scenario.
- Inspect the actual output before making the next logical change.
- Do not stack additional changes on top of an unverified failing change.

**Stage C — Each integration boundary**
- Verify changed code with its caller and consumer.
- Verify lifecycle, process ownership, state transitions, persistence, API/network contracts, diagnostics, packaging, and UI integration wherever the patch touches them.
- Check both success and failure paths relevant to the change.

**Stage D — Final verification**
- Run targeted regression tests first, then the applicable project test suite, static analysis, build/package checks, and runtime/device/infrastructure checks required by the acceptance criteria.
- Inspect CI logs, test reports, APK/package metadata, artifact structure/integrity, and deployment/runtime results directly.
- Test the actual deliverable where the environment permits.

**Stage E — Failure handling**
- On any failure, stop adding scope. Inspect the exact error and logs; determine whether it is pre-existing, environmental, flaky, or introduced by the patch.
- Apply only the smallest correction, then rerun the failed check and affected regressions.
- Never delete or weaken a failing test merely to make CI green.

A check that was not run is **NOT RUN**, not passed. A build is not runtime verification. A mock/emulator is not physical-device verification. Source inspection is not proof of execution. Report each evidence class separately.

# 6. Diff integrity and preservation gate

Before completion:
- Review the full final diff against the pinned base commit.
- Explain every changed file and every non-obvious hunk.
- Confirm no unrelated files, generated artifacts, secrets, credentials, user data, or local changes were included.
- Confirm the patch did not alter protected behavior or contracts outside the stated scope.
- Run whitespace/conflict checks and relevant repository-specific validation.
- Verify final branch, HEAD/commit, PR, workflow IDs, artifact paths, hashes, and runtime evidence as applicable.
- If the final diff contains unnecessary changes, remove them before reporting completion.

No force-push, reset, merge, release, deployment, destructive data operation, or production infrastructure change unless explicitly authorized.

# 7. Mandatory chief-of-staff report

Every task handoff and final report must include:

1. **Situation established:** repository/branch/base commit; app/service/build/device/session identifiers; relevant logs and diagnostic/CI artifacts inspected.
2. **Evidence timeline:** key events with timestamps and source references; facts separated from hypotheses and unknowns.
3. **Root cause:** confirmed cause and proof, or explicit unconfirmed status and the missing evidence.
4. **Exact change direction:** files and functions to change; intended behavior; protected behavior; why each change is necessary.
5. **Diff:** complete reviewable diff or exact patch reference, with changed hunks explained.
6. **Execution:** actual implementation and resulting commit/PR.
7. **Incremental verification:** checks run after each logical change and their actual results.
8. **Final verification:** tests, CI, build, package, runtime/device, deployment, and artifact integrity, each marked PASS / FAIL / BLOCKED / NOT RUN.
9. **Scope control:** confirmation that only necessary changes were made and unrelated systems were preserved.
10. **Remaining items:** exact blockers, missing evidence, risks, and next action.

# 8. Absolute completion gate

A task is complete only when:
- relevant available logs and evidence have been reviewed;
- the cause and required scope are understood;
- the minimal diff is justified;
- only the necessary implementation is applied;
- incremental and final applicable tests have been inspected;
- the final diff and repository state are verified;
- the result is reported truthfully.

If any required evidence or test is unavailable, report **PARTIALLY VERIFIED / BLOCKED** and identify the exact missing item. Never call it fully complete.

**MANDATORY ORDER:**
COMPLETE RELEVANT EVIDENCE & LOG REVIEW
→ CORRELATED TIMELINE & ROOT-CAUSE DETERMINATION
→ EXACT FILE/FUNCTION CHANGE SPECIFICATION
→ COMPLETE MINIMAL DIFF
→ MINIMUM IMPLEMENTATION
→ IMMEDIATE INCREMENTAL TESTS
→ INTEGRATION & REGRESSION VERIFICATION
→ FINAL DIFF/STATE REVIEW
→ EVIDENCE-BASED REPORT

This order is absolute for every task in this repository.
