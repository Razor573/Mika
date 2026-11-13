You write a reusable "skill" document in Markdown for a mobile assistant based on a completed task (whose final answer was reviewed and confirmed as done). You cannot see the phone screen; write strictly from the target language, goal, final answer, answer sources, and action trace provided below. You will also be given the user's existing personal skills (including drafts awaiting confirmation) to check whether this belongs to an existing skill.

Output ONLY one Markdown document and no other text, in this exact format:

---
name: <lowercase English letters, digits, and hyphens, e.g. send-wechat-message>
display: <short display name>
description: <one sentence: when to use this skill>
apps: [<Android package names used>]
domains: [<website domains used; leave empty if none>]
aliases: [<two or three short phrases the user might say for this task>]
source: learned
learned_from: <run ID>
updates: <name of an existing personal skill; omit this line if this is a new kind of task>
---
## Entry
## Steps
## Common pitfalls
## Done criteria
## When to pause for the user

Requirements:
- Write display, description, aliases, and all section prose in the Target language specified in the request (defaulting to English if unspecified). Keep the YAML keys, name, source, and the exact five English headings (## Entry, ## Steps, ## Common pitfalls, ## Done criteria, ## When to pause for the user) verbatim. Keep on-screen app names and UI labels as observed.
- Include all five sections with those exact headings. Keep the whole document under 3,000 characters.
- First check Existing personal skills: if this task is the same kind of workflow as one of them (even with a different target person, value, or wording), set updates: to its name in the front matter and rewrite that skill using the verified route from this run; otherwise remove the updates line and pick a fresh name that does not collide with any existing skill. Never create a duplicate skill for the same workflow.
- Write a general procedure for this kind of task, not a log of this single run. Replace concrete run values (contacts, amounts, quantities, addresses, message text, dates) with <param> or generic descriptions such as "the requested contact". Keep <param> wherever the goal has already redacted a value.
- Base the steps on the actions in the trace that actually succeeded; document failed attempts that had to be worked around under ## Common pitfalls.
- Put tool names in backticks, using ONLY names listed under Available tools; do not put app names, button labels, or package names in backticks.
- Under ## Done criteria, describe the visible outcome confirming the task succeeded (using the answer sources showing where to verify it); under ## When to pause for the user, note when login, payment, confirmation, or user choice is required.
- Never write passwords, verification codes, card numbers, ID numbers, or any personal information.
