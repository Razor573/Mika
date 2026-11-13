---
name: info-research
display: Research a topic
description: Research online: find primary sources, verify and cite
aliases: ["research", "look up", "find out about", "investigate"]
apps: ["xiaohongshu", "zhihu", "bilibili", "weibo"]
sites: ["zhihu.com", "tieba.baidu.com", "weibo.com", "douban.com"]
terms: ["research", "look up", "background", "compare", "reliable", "source", "latest", "verify"]
---
## Entry

Narrow down the subject first: exact name, region, time frame, version or model. If the name is ambiguous or could mean different things, ask the user once while checking any parts that do not depend on the answer. If the user left out bounds (such as a date range), pick sensible defaults and state them at the start of your findings.

Start by opening a search page with `web_navigate` (such as Bing, Google or Baidu), or use in-app search inside a relevant app.

## Steps

1. **Map the topic**: Run two or three broad searches to see what claims exist, which sites cover it, and who the authoritative publishers are. Use two to four keywords for in-app search rather than full sentences.
2. **Trace the primary source**: Track the specific fact the user asked about back to its earliest publisher: the issuer's own site, official announcements, original filings, papers, or the product or service's own page. Treat news reports and reposts as leads only: follow their links or names to the primary text.
3. **Check experiences and downsides**: Look for hands-on experiences on community forums, review sites and video platforms (such as Xiaohongshu, Zhihu, Tieba, Weibo, Bilibili, Douban, Reddit or YouTube), and check complaint sites for reported problems. Always run a search with negative terms ("issues", "complaints", "drawbacks").
4. **Cross-check**: Verify key claims in the original text of at least two independent sources. When two sources disagree, check publication dates and which one is closer to the event, and state the disagreement explicitly.
5. **Write the findings**: Give the source (site or publisher) and date for each claim; separate what the sources state from your own inference; for anything you searched for and could not find, state where you looked and what is still missing.

## Common pitfalls

- Search snippets are not the source text and are often truncated or out of date; open the page with `web_read_page` to read the full body and check which site you actually landed on.
- One press release is often syndicated across many sites, so many URLs may still count as a single source.
- Sponsored posts, reviews with affiliate links and anonymous rumours carry low weight; label them when used.
- A date on a page may be when it was reposted or updated, not when the event happened.
- Not finding something, a page failing to load, or hitting a login wall only means you could not read it this time; never state it as proof that something does not exist.

## Done criteria (verify evidence)

Every finding traces back to a page actually read in this session with its source and date; key conclusions have two independent sources; weak or conflicting evidence is explained.

## When to pause for the user

Hand over to the user when needed content sits behind a login, or when the next step becomes booking, buying, registering or any action that needs the user's decision or payment.
