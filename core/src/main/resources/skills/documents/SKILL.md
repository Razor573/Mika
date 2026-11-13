---
name: documents
display: Work with PDFs
description: Read PDF text, inspect info, split, merge and share files
aliases: ["read PDF", "extract PDF text", "merge PDFs", "split PDF"]
terms: ["PDF", "document", "extract text", "by page", "merge", "split", "scanned"]
---
## Entry

First check where the document is:
- Public web link: open it with `web_navigate`; if the built-in browser displays it directly, reading the page is enough.
- Attachment sent by the user: in `/workspace/inbox/`.
- File downloaded from a web page: in `/workspace/downloads/` (check download records with `web_downloads`).

## Steps

1. For text-based PDFs, run `scripts/extract_pdf.py` using `run_bundled_script` with `source` set to `skills/documents` and paths written relative to `/workspace`:
   - Full text: `args: ["inbox/a.pdf"]`
   - Specific pages (1-based; accepts `3`, `1-5`, `1,4,7-9`): `args: ["inbox/a.pdf", "--pages", "1-5"]`
   - Title, author, page count and metadata (JSON): `args: ["inbox/a.pdf", "--metadata"]`
   - Long documents saved to text first: `args: ["inbox/a.pdf", "--out", "a.txt"]`, then read in sections with `read_file` or look up keywords with `search_files`.
   - Password-protected PDFs: add `--password` followed by the password.
2. Each page in the output starts with `--- page N ---`; cite page numbers from those markers.
3. To split, merge or extract pages, use `run_command` to run a short Python snippet using pypdf, and save new files under `/workspace`.
4. Hand finished files to the user with `share_file`.

## Common pitfalls

- If the script exits with code 3 and reports no extractable text, the PDF is a scan of images. This device has no OCR; inspect page images instead or tell the user. When only some pages lack text, the script reports those pages separately.
- Text from tables and multi-column layouts often extracts out of order; double-check numbers against the original page before citing them.
- Out-of-range page numbers cause an immediate error, so check the page count with `--metadata` first.

## Done criteria (verify evidence)

Quoted text matches the original page in the script output and cites the page number; any delivered file succeeded via `share_file` with the expected page count and content (re-run `--metadata` on the output file to verify page count).

## When to pause for the user

Pause and ask the user if the PDF needs a password they have not supplied, or if they need to decide which pages to keep or how to order merged files.
