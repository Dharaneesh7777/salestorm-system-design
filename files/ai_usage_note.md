# SALESTORM – AI Usage Note

**Policy followed:** design first; AI used only as an accelerator, never as a replacement for design decisions.

| Item | Detail |
|---|---|
| AI tool used | Claude (Anthropic) |
| Purpose | Drafting and formatting documentation and UML text (Mermaid) from the team's own design decisions: ER diagram text, scalability / reliability write-up, security and observability write-up, README, pitch slide text |
| Not used for | Requirement analysis, architecture selection, concurrency strategy, inventory consistency, payment and order state modelling, trade-off analysis (these were decided by the team in Stages 1-8) |
| Code generated | None. No prototype or simulation is submitted (optional deliverable 19 intentionally skipped) |

## Validation performed by the team
| Check | How |
|---|---|
| Consistency with earlier stages | Table names, states, event names and numbers (5-minute TTL, 3 s timeout, 50% / 10-call circuit breaker, 30 s open) were cross-checked against Stage 3, Stage 4, Stage 6 and Stage 7 documents |
| Business-rule correctness | Inventory invariant, conditional update, outbox / inbox, saga and reconciler behaviour reviewed by team members |
| Ownership | Every team member must be able to explain each artefact; AI-drafted text was edited where it did not match the design |

## Prompt summary
1. "Create the missing deliverables except coding" based on the uploaded Stage 3, 4, 6A, 6B, 7B documents and the hackathon brief.
2. Requested outputs: database / ER design, scalability and reliability design, security and observability design, final 5-minute presentation, README.
