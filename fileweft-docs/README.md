# FileWeft Docs

Zero-dependency static documentation for the FileWeft 0.0.3 release contract. The site defaults to English and provides a complete Chinese switch, responsive grouped navigation, local search, hash routing and copyable code samples. This source tree is not publication evidence: treat `ai.icen:*:0.0.3` as remotely consumable only after the guarded `v0.0.3` tag matches the protected remote `main` HEAD and every required lane for that exact commit succeeds, followed by anonymous cold-cache resolution of all 19 coordinates and the Boot 2, Boot 3, and pure-SPI consumers.

Run locally with Node.js 18 or newer:

```powershell
Set-Location .\fileweft-docs
npm test
npm start
```

Then open `http://127.0.0.1:8090/`. Set `PORT` to use another local port. The production host may serve this directory as ordinary static files; route state stays in the URL hash, so no server rewrite rule is required.

The top-right **SKILL** button downloads `SKILL.md`, the AI integration guide kept at the repository root. `npm start` and `npm test` run `copy-skill.mjs` to copy it into this directory; for a production static host, run `npm run copy-skill` before serving.

The content is a curated public projection of the root `README.md` and documents under `docs/`. Update those authoritative sources first when product behavior changes, then update the corresponding bilingual page in `content.js`.
