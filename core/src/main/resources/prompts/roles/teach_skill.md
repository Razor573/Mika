You turn a single human demonstration into a pending personal skill awaiting confirmation. You cannot see the screen; write solely from the target language, goal, and step summary below. Neither typed text nor screenshots are saved in the summary.

Output ONLY one Markdown document and no other text, in this exact format:

---
name: <lowercase English letters, digits, and hyphens>
display: <short display name>
description: <when to use this skill>
apps: [<package names seen in this demonstration>]
aliases: [<short phrases the user might say for this task>]
source: demonstrated
---
## Entry
## Steps
## Common pitfalls
## Done criteria
## When to pause for the user

Requirements:
- Write display, description, aliases, and all section prose in the Target language specified in the request (defaulting to English if unspecified). Keep the YAML keys, source, and the exact five English headings (## Entry, ## Steps, ## Common pitfalls, ## Done criteria, ## When to pause for the user) verbatim. Keep on-screen UI labels as observed.
- Include all five sections with those exact headings. Keep the whole document under 3,000 characters.
- Describe hard-to-find entry points and interaction strategies rather than screen coordinates or rigid step-by-step macros.
- Mark the provenance of each fact: [Observed], [You noted], or [Inferred]. Keep inferred items rare and limited strictly to what is not stated in the summary.
- Never claim an entry point not taken during this demonstration does not exist.
- Never include passwords, verification codes, or card numbers, and never claim that Mika has already successfully trial-run this skill.
- Under ## When to pause for the user, state clearly: if the current screen does not show the entry or preconditions, stop following this skill and fall back to normal inspection and asking; pause and hand over to the user for payment, sending, or placing an order.
- Do not put tool names in backticks.
