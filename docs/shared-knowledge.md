# Shared project knowledge

Claude and Codex use the same repository documentation and instructions. Claude
auto memory is disabled for this project. Do not read or write its local memory
directory during routine development. The old local index is historical evidence,
not a second set of current instructions.

## Read

Start at the [knowledge index](knowledge/INDEX.md), then read the owning feature or
decision topic. Use [CATALOG.md](knowledge/CATALOG.md) as a lookup table when the
short map is not enough. Use source search to confirm current behavior. The old
[project-memory path](PROJECT-MEMORY.md) is a compatibility pointer.

## Capture

| Knowledge | Home | Writer |
|---|---|---|
| Product behavior and engineering lessons | Owning topic under `docs/knowledge/` | Documentation stage, or an interactive maintainer in a reviewed change |
| Requirements, review findings and unfinished work | Ticket or pull request | The role doing that work |
| Agent workflow and review practice | Agent or dispatcher repository | Workflow maintainer |
| Personal preferences, project direction and operating history | Personal vault | Interactive assistants |

Builders and verifiers record durable discoveries on their ticket or pull request.
The documentation stage folds product lessons into the owning topic and updates the
short index and catalog when a document is added. Workflow findings stay with the
agent or dispatcher repository. Do not save a lesson only in local memory.

Record the failure that would otherwise recur, its cause and how to check it. Do
not repeat an implementation summary. Update an existing topic before creating a
new one. Historical observations need a date and a current-code check before they
become instructions. The vault can link to these documents but should not keep a
second active copy.

## Maintenance

Keep feature overviews under the size limit enforced by
[`scripts/docs-guard.sh`](../scripts/docs-guard.sh). The per-ticket files under
`knowledge/codebase/` are frozen history. Do not append to the compatibility
pointer or to frozen history. Re-run the documentation guard after documentation
changes and include the result in the handoff.
