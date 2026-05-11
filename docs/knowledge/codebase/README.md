# Per-ticket implementation notes

One file per ticket: `<N>.md`. The directory listing is the index.

Each file captures what shipped in that ticket and anything a future agent should know that isn't obvious from the code or git history. Include:

- **Summary** — what was built, in two or three sentences.
- **Files touched** — the actual files, with line refs for the load-bearing bits.
- **Patterns established** — only if this ticket sets a precedent later tickets should follow (e.g. "previews always pass `darkTheme` explicitly to the theme"). Skip if nothing new.
- **Lessons learned** — Compose recomposition surprises, lifecycle quirks, dependency-version mismatches, anything that bit during implementation and would bite again. Skip if none.
- **Links** — issue, spec, related tickets, related features/decisions.

Never edit a sibling ticket's file. Lessons that used to land in a shared `lessons.md` go here instead.
