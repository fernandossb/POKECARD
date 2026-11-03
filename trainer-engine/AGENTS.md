# CPU comparison requirement

The user requires every CPU improvement to be compared against the previous version they were using.

- Before changing CPU behavior, freeze the current working-tree engine with `scripts/freeze-cpu-baseline.ps1`, using a new name. Do not overwrite old snapshots. Preserve the manifest and reference its SHA-256 in the report.
- Use the arena's `FROZEN` mode with `-PcpuArenaBaselineJar=<absolute path to snapshot engine.jar>` for the primary comparison. MASTER/TACTICAL/HEURISTIC modes using current code are additional diagnostics, not a previous-version comparison.
- Keep decks, seed schedule, difficulty, thinking mode and tuning comparable; alternate seats. Use Mega Lucario ex mirror unless the user requests another matchup. State the sample size and number of paired seeds.
- Always pass `-PcpuArenaKnowledge` explicitly to match the app's `CpuKnowledge.appMode` (currently `OPPONENT_HAND`). The arena's compatibility default FAIR is not the current app mode. The frozen bridge preserves its original policy; report information asymmetry when comparing CPU+ against older FAIR versions. The user explicitly requested the hand advantage, so do not silently remove it in later snapshots or comparisons.
- Fail explicitly if the frozen artifact is missing or incompatible. Never silently substitute the current engine.
- Run relevant regressions and Android compilation. Preserve the report before the next arena run overwrites it. Report wins, losses, errors and timing scope; do not claim improvement without evidence.
- Snapshot artifacts are local under `.cpu-baselines/` (ignored by Git). On a different machine, freeze the previous version there before editing, or restore the identified artifact and verify its hash.
