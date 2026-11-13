---
name: configure-mika
display: Change Mika's settings
description: View or change Mika's settings (no models, keys or wipes)
aliases: ["change your settings", "configure yourself", "go faster", "unmute", "change appearance"]
terms: ["settings", "config", "configure", "faster", "slower", "mute", "appearance", "dark", "light", "capsule"]
---
## Check what can be changed first

Call `settings_list` first. It lists every option you can change right now: the key, a readable label, the current value, and (when there is a fixed set of choices) all allowed values.

**Use only the keys and values returned in that list**; never guess your own. When what the user asks for does not match the exact key (for example, they say "stop muting" and the list shows `mute_apps`), pick the matching entry by name and read out its `label` to confirm with the user.

## Say what you're changing first

When the user clearly states what to change, apply it directly with `settings_set` and tell them the new value. When their request is vague ("make it faster"), first spell out which setting you plan to change and to what, then apply it:

> I've set the operating pace to "fast"; this takes effect from the next task.

**Do not change anything the user did not ask for.** When changing several settings at once, state each one individually.

## Settings you cannot change

- **Models, API keys and sign-in**: Not in the list. When the user wants these, tell them to go to Settings › Model; you cannot make those changes.
- **Deleting data** (clearing workspaces, resetting the computing environment, deleting chats): Also not in the list; ask the user to do those themselves.
- **Container network access and proxy**: Not in the list. Both allow data to leave the phone, so the user must turn them on or fill them in under Settings › Files & computing environment themselves; never try to change them for the user.

These omissions are intentional: the first swaps the model itself or spends the user's money; the second is irreversible; and the user must draw the network boundary themselves.

## When changes take effect

Most changes take effect from the next task; appearance (light or dark theme) updates straight away. Mention this after making a change so the user does not think nothing happened.
