---
name: content-transcribe
display: Turn content into text
description: Summarise, quote or rewrite videos, podcasts and articles
aliases: ["summarise video", "summarise podcast", "summarise article", "extract subtitles", "get transcript"]
apps: ["bilibili", "xiaohongshu"]
sites: ["youtube.com", "xiaoyuzhoufm.com"]
terms: ["summary", "summarise", "transcribe", "transcript", "subtitles", "captions", "podcast", "chapters", "key points", "quotes"]
---
## Entry

First clarify which output format the user wants: full transcript, summary, chapters, quotes, or a rewritten article. If the user did not specify, write a five-to-ten-sentence summary.

Get the text from wherever the content lives: read page body text for articles; use subtitles/captions or a transcript in the description for videos; check the episode page for a transcript or show notes for podcasts. This device has no speech-to-text transcription: if only audio is available, ask the user for a transcript or subtitle file.

## Steps

1. **Get the full text**: Read page content with `web_read_page`; for subtitle or transcript files the user sent under `/workspace/inbox/`, read them with `read_file`.
2. **Read in chunks**: For long content, read it in several sections with a little overlap between chunks, remove duplicates after reading, and verify nothing was skipped from start to finish.
3. **Write in the requested format**: Read `references/transcript-output-formats.md` before writing for the layout and examples of each format. A summary follows this outline: a one- or two-sentence overview; three to eight topics in order of appearance, each with an approximate start time and two or three sentences; and a closing list of notable arguments, figures, and any books or works mentioned.
4. **Write in the source material's language**, unless the user asks for another language.
5. **Re-read**: Check the result against the source: is the order right, were any topics missed, and can every timestamp and quote be traced back to the original text?

## Common pitfalls

- Captions only contain spoken words: charts, demos and on-screen text are not visible in them, so never write as though you saw the visuals.
- Auto-generated captions often have typos and wrong sentence breaks; verify proper nouns, names and figures against the context or description.
- When timestamps are estimated from subtitle positions, write "around 23:00" rather than pretending second-level precision.
- Mark unclear or missing parts of captions as gaps instead of inventing text to fill them.

## Done criteria (verify evidence)

The output covers every section of the source material, and every quote and timestamp can be found in the fetched captions or body text.

## When to pause for the user

If the content requires a login or payment, or if there is only audio with no transcript or captions, tell the user what is missing and ask them to provide it.
