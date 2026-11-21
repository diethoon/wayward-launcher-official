# ABSOLUTE ENGINEERING RULE — MINIMUM CHANGE, MINIMUM IMPLEMENTATION, INCREMENTAL VERIFICATION

**STATUS: NON-NEGOTIABLE / MANDATORY FOR EVERY TASK / EVERY BRANCH**

This is a repository-wide engineering invariant. It applies to every engineer, coding agent, automated workflow, and delegated task in this repository, including bug fixes, feature work, refactors, migrations, build/release work, diagnostics, and infrastructure changes. Read it before acting. Task-specific instructions do not silently waive it. Only an explicit user instruction may authorize a documented exception, and safety, data integrity, and truthful reporting remain mandatory.

## 1. Minimum necessary change — absolute rule

- Make only the smallest change that is demonstrably necessary to satisfy the exact requested outcome or fix a reproduced/verified defect.
- Before editing, identify and record: the exact target repository, branch, base commit, working-tree state, affected files, relevant functions/call paths, observed failure, and evidence supporting the proposed cause.
- Pinpoint the actual change location: file, class/module, function/method, event or execution path, and the behavior before/after the change. Do not issue or implement vague instructions such as "fix the server" without identifying the responsible path.
- Do not modify unrelated files, neighboring features, formatting, naming, architecture, dependencies, APIs, data formats, or configuration merely because they could be improved.
- Do not perform broad refactors, rewrites, speculative hardening, opportunistic cleanup, or abstraction work as part of a targeted fix.
- Preserve all existing working behavior and compatibility contracts. Preserve user data, settings, credentials, save formats, identifiers, RNG, undo/history, economic rules, network/API contracts, and lifecycle behavior wherever applicable.
- If evidence does not establish the cause or safe change location, do not guess. Continue the smallest relevant investigation until the decision is evidence-based; if a truly indispensable external fact is unavailable, state the precise blocker.

## 2. Minimum necessary implementation — absolute rule

- Implement only the behavior required by the confirmed acceptance criteria. No extra feature, UI redesign, optional enhancement, unrelated dependency, or speculative fallback.
- Prefer the smallest localized patch over new frameworks, new services, new abstractions, or cross-module changes.
- Reuse existing project patterns, lifecycle ownership, state management, error handling, logging, packaging, and test infrastructure.
- Do not replace a functioning implementation merely to make the change easier.
- Do not weaken validation, remove tests, suppress errors, fake success, or loosen acceptance criteria to obtain a passing result.
- If the smallest safe fix requires more than one file or module, explain the dependency between those changes and keep the patch limited to that chain.
- If implementation scope must expand, record the concrete evidence that makes the expansion necessary and obtain explicit user authorization before unrelated or high-impact expansion.

## 3. Incremental verification and testing — mandatory during implementation

Testing is not a final-only activity. Verification must happen throughout the change.

1. **Before editing:** inspect existing tests and run the smallest relevant baseline test/build when feasible. Record pre-existing failures separately.
2. **After each logical change:** immediately run the narrowest test, static check, or reproducible scenario that validates that change. Do not stack multiple unverified edits.
3. **At each integration boundary:** verify the interaction between the changed component and its caller, lifecycle, storage, network/API, packaging, or UI as applicable.
4. **After the complete patch:** run targeted regression tests, relevant project test suites, static analysis, build/package checks, and the requested runtime/device verification.
5. **After any failure:** stop adding changes; inspect the actual error and evidence, identify whether it is pre-existing or introduced, make the smallest correction, and repeat the affected checks.
6. **Before completion:** inspect the final diff and repository state; confirm that every changed line is necessary, no unrelated files changed, and all required checks have actual recorded results.

A test that was not run is NOT passed. A build is not a runtime test. A mock/emulator is not physical-device verification. Source inspection is not proof of execution. Report each evidence class separately.

## 4. Scope and change-control guard

- Work on the explicitly authorized branch or an isolated task branch. Never overwrite existing user work or another task's branch.
- Record the starting commit and working-tree state before editing.
- Keep a reviewable diff. Do not force-push, reset, merge, release, deploy, delete data, or change production infrastructure unless explicitly authorized.
- Do not modify generated files or lockfiles unless the required build/tooling change actually demands it.
- Do not claim a fix based only on a UI change, a successful process spawn, a green unrelated workflow, or an unverified artifact.
- Preserve failure evidence and test reports needed to explain the result.

## 5. Required implementation record

Every completed engineering task must report:

- Exact repository, branch, base commit, and resulting commit/PR.
- Confirmed defect or requested behavior and the evidence establishing it.
- Exact files and functions changed, with the reason each change is necessary.
- What was deliberately left untouched to preserve existing behavior.
- Incremental checks run during implementation and their actual results.
- Final regression/build/runtime/device checks, clearly distinguishing passed, failed, blocked, and not run.
- Remaining risks, limitations, and unverified conditions.

## 6. Completion gate — no exceptions by implication

A task is not complete merely because code was edited, a PR was opened, a build was started, or a UI appears correct. Completion requires the minimum necessary implementation, successful applicable incremental and final verification, final diff review, and truthful reporting. If any required check cannot be performed, report the task as **PARTIALLY VERIFIED / BLOCKED**, name the exact missing evidence, and never describe it as fully complete.

## 7. Absolute decision order

**Evidence → smallest safe change → smallest required implementation → immediate incremental test → integration/regression verification → final diff review → truthful report.**

This order is mandatory. Do not reverse it by coding first and investigating later. Do not batch unverified edits. Do not expand scope without evidence and authorization.

---
This policy is intentionally strict. It is the default operating rule for every task in this repository and must be preserved in future edits to repository instructions.
