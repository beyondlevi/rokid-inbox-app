# GitHub PRs

GitHub Pull Requests as a **read-only** inbox: each PR is a chat, and its
description + issue comments are the messages. Auth is a Personal Access Token you
create; nothing is hardcoded.

Supports: list PRs, read the PR conversation. (No sending, no reactions, no media.)

## 1. Create a Personal Access Token

Go to **GitHub → Settings → Developer settings → Personal access tokens**:

- **Classic token**: scope `repo` (for private repos) or `public_repo` (public only).
- **Fine-grained token**: grant **Pull requests: Read** (and repo access as needed).

Copy the token.

## 2. (Optional) Choose a PR filter

The app lists PRs from GitHub search. Default query:

```
is:open is:pr involves:@me
```

You can use any [GitHub issue search](https://docs.github.com/en/search-github/searching-on-github/searching-issues-and-pull-requests)
query, e.g. `is:open is:pr review-requested:@me` or `is:pr author:@me org:yourorg`.

## 3. Add it in the app

On the phone: **[ INBOXES / SETTINGS ] → + Add GitHub PRs** and enter the **token**
and, optionally, a **PR filter**. The app validates by calling `/user`.

## Troubleshooting

- **401** — invalid/expired token.
- **403 on a PR body** — fine-grained tokens sometimes 403 the issues endpoint;
  the app falls back to the pulls endpoint for the PR body and best-effort comments.
- **No PRs** — your filter matched nothing; try the default query.
