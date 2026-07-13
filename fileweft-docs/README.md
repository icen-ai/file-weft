# FileWeft Docs

Zero-dependency static documentation for the FileWeft 0.0.2 release contract. The site defaults to English and provides a complete Chinese switch, responsive grouped navigation, local search, hash routing and copyable code samples. Treat `ai.icen:*:0.0.2` as remotely consumable only after the protected tag pipeline and anonymous cold-cache resolution succeed.

Run locally with Node.js 18 or newer:

```powershell
Set-Location .\fileweft-docs
npm test
npm start
```

Then open `http://127.0.0.1:8090/`. Set `PORT` to use another local port. The production host may serve this directory as ordinary static files; route state stays in the URL hash, so no server rewrite rule is required.

The top-right **SKILL** button downloads `SKILL.md`, the AI integration guide kept at the repository root. `npm start` and `npm test` run `copy-skill.mjs` to copy it into this directory; for a production static host, run `npm run copy-skill` before serving.

The content is a curated public projection of the root `README.md` and documents under `docs/`. Update those authoritative sources first when product behavior changes, then update the corresponding bilingual page in `content.js`.
