---
name: skill-authoring
display: Write skills
description: Write, import or update a skill: format, checks and saving
aliases: ["create skill", "new skill", "write skill", "save skill", "import skill", "edit skill", "skill"]
terms: ["skill", "workflow", "steps", "template", "always do this", "save as skill", "import", "rewrite"]
---
## When to write a skill

- Write one only when the user explicitly asks to "create a skill", "save this as a skill", "import this as a skill" or "update a skill". If they are only discussing how to do something or asking whether it is possible, answer them without saving a skill.
- A skill captures a reusable method for future tasks of the same kind, not a one-off fact or preference. Something like "I don't eat coriander" is a memory, not a skill.
- Only `skill_save` can save a skill. If that tool is not available, tell the user plainly that you cannot save it this time; never say it was saved.

## Check what already exists first

1. Search once with `skill_search` using terms describing this kind of task.
2. If a personal skill already covers this kind of task, read it in full with `skill_read`, edit it, keep its name, and pass the version you read when saving. Do not create a duplicate skill for the same kind of task.
3. If only a built-in skill matches: built-in skills cannot be edited. When the user wants a different workflow, save it as a personal skill under a new name and tell the user which one will be used going forward.

## Clarify before writing

When the user only gives a vague request ("make me a skill for ordering coffee"), first use `ask_user` to clarify the one or two details that matter most: which app or website to use, where to stop, and any fixed requirements. List the key points in a few lines for the user to confirm before saving. If the user has already explained the method clearly or pasted the full text, skip asking and go ahead.

## How to write it

The whole skill is a single Markdown document: front matter plus five sections with the exact headings below, at most 4000 characters in total.

```
---
name: order-coffee
display: Order coffee
description: Use when ordering coffee for delivery or collection
aliases: ["order coffee", "buy a coffee"]
---
## Entry
## Steps
## Common pitfalls
## Done criteria
## When to pause for the user
```

- Use lowercase letters, digits and hyphens for name to say what kind of task it is; set display to a short, plain title.
- Write description as one sentence saying when to use the skill, and put phrases the user might say into aliases; future matches depend on these two fields.
- Entry states where to start (which app, website or search); Steps are short numbered sentences, one action per step, naming buttons and screens rather than coordinates.
- Replace values that vary each time (contact names, amounts, addresses, message text) with `<param>` or phrases like "the chosen contact" instead of hardcoding them.
- Common pitfalls notes what tends to go wrong and how to work around it; Done criteria states where the result can be verified once finished; When to pause for the user states when to hand over for login, payment, confirmation or user choices.
- Use backticks only for tool names; never put app names or button labels in backticks. Never include passwords, verification codes, card numbers or anyone's private details.
- Keep it short: omit what the model already knows and write only the steps and pitfalls specific to this task.

## Importing text pasted by the user

When the user pastes a skill, note or workflow written elsewhere, rewrite it in the format above: keep the method itself, drop anything that does not apply here (commands for other software, tools or scripts not available on this phone), and fill any missing section with a sentence or two true to the original. Never carry over accounts, passwords or similar details from the pasted text.

## Saving and telling the user

1. Save with `skill_save`: pass the full document in markdown, and copy the user's own words from this task asking you to create or update the skill into quote.
2. Only an ok result means it was saved. Report strictly from the result: the skill's name, whether it was newly created or updated, that it appears under Settings › Memory & skills, and that Mika will try it next time a matching task comes up.
3. If saving fails, fix the reported issue and try again; if it still fails, tell the user plainly that it was not saved and why; never claim it was created.
4. Renaming and deleting cannot be done here: tell the user to delete the old one in Settings › Memory & skills; to rename one, save a new copy under the new name and ask the user to delete the old one.

## Done criteria

`skill_save` returns ok with the expected skill name, and your reply matches the returned name and action (created or updated).
