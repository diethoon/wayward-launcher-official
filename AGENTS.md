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
