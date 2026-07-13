/**
 * Minimal Markdown + YAML frontmatter parser for FileWeft docs.
 *
 * Supports frontmatter, headings, paragraphs, lists, code fences,
 * tables, blockquote callouts and inline bold/code/links.
 * It is intentionally small so the docs stay self-hosted without a CDN.
 */

export function parseFrontmatter(source) {
  const trimmed = source.trim();
  if (!trimmed.startsWith("---")) {
    return { meta: {}, body: trimmed };
  }
  const end = trimmed.indexOf("\n---", 3);
  if (end === -1) {
    return { meta: {}, body: trimmed };
  }
  const front = trimmed.slice(3, end).trim();
  const body = trimmed.slice(end + 4).trimStart();
  const meta = {};
  for (const line of front.split("\n")) {
    const index = line.indexOf(":");
    if (index > 0) {
      const key = line.slice(0, index).trim();
      let value = line.slice(index + 1).trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1).replace(/\\"/g, '"').replace(/\\'/g, "'");
      }
      if (/^-?\d+$/.test(value)) {
        meta[key] = Number.parseInt(value, 10);
      } else if (value === "true") {
        meta[key] = true;
      } else if (value === "false") {
        meta[key] = false;
      } else {
        meta[key] = value;
      }
    }
  }
  return { meta, body };
}

export function markdownToHtml(source) {
  const lines = source.split("\n");
  const out = [];
  let i = 0;

  while (i < lines.length) {
    const raw = lines[i];
    const line = raw.trimEnd();
    if (line.trim() === "") {
      i += 1;
      continue;
    }

    if (line.startsWith("```")) {
      const lang = line.slice(3).trim();
      const start = i + 1;
      let end = start;
      while (end < lines.length && !lines[end].trim().startsWith("```")) {
        end += 1;
      }
      const code = lines.slice(start, end).join("\n");
      out.push(renderCodeBlock(lang, code));
      i = end + 1;
      continue;
    }

    if (line.startsWith("## ")) {
      out.push(`<h2>${escapeInline(line.slice(3))}</h2>`);
      i += 1;
      continue;
    }

    if (line.startsWith("### ")) {
      out.push(`<h3>${escapeInline(line.slice(4))}</h3>`);
      i += 1;
      continue;
    }

    if (line.startsWith("> ")) {
      const quote = [];
      while (i < lines.length && lines[i].startsWith("> ")) {
        quote.push(lines[i].slice(2));
        i += 1;
      }
      out.push(renderQuote(quote));
      continue;
    }

    if (line.startsWith("|")) {
      const table = [];
      while (i < lines.length && lines[i].startsWith("|")) {
        table.push(lines[i]);
        i += 1;
      }
      out.push(renderTable(table));
      continue;
    }

    if (/^[-*]\s+/.test(line)) {
      const { html, next } = renderList(lines, i, "ul");
      out.push(html);
      i = next;
      continue;
    }

    if (/^\d+\.\s+/.test(line)) {
      const { html, next } = renderList(lines, i, "ol");
      out.push(html);
      i = next;
      continue;
    }

    if (line === "---") {
      out.push("<hr />");
      i += 1;
      continue;
    }

    const block = [];
    while (i < lines.length && lines[i].trim() !== "") {
      block.push(lines[i]);
      i += 1;
    }
    out.push(`<p>${renderInline(block.join(" "))}</p>`);
  }

  return out.join("\n");
}

function renderCodeBlock(language, code) {
  const escaped = code
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  const label = escapeHtml(language || "text");
  return `<div class="code-block"><div class="code-label"><span>${label}</span></div><pre><code>${escaped}</code></pre></div>`;
}

function renderQuote(lines) {
  const first = lines[0].trim();
  const calloutMatch = first.match(/^\[!([A-Z]+)\]\s*(.*)$/);
  if (calloutMatch) {
    const mark = calloutMatch[1];
    const title = escapeInline(calloutMatch[2] || mark);
    const bodyLines = lines.slice(1);
    const body = bodyLines.length > 0
      ? `<p>${renderInline(bodyLines.map((l) => l.trim()).join(" "))}</p>`
      : "";
    const warning = mark === "WARNING" || mark === "CAUTION" || mark === "DANGER" ? " warning" : "";
    return `<aside class="callout${warning}" data-mark="${mark}"><div><strong>${title}</strong>${body}</div></aside>`;
  }
  const body = lines.map((l) => renderInline(l.trim())).join("<br />\n");
  return `<blockquote><p>${body}</p></blockquote>`;
}

function renderTable(lines) {
  const rows = lines
    .map((row) => row.split("|").map((cell) => cell.trim()).filter((cell, idx, arr) => cell !== "" || (idx > 0 && idx < arr.length - 1)))
    .filter((cells) => cells.length > 0);
  if (rows.length < 2) {
    return `<p>${escapeHtml(lines.join("\n"))}</p>`;
  }
  const heads = rows[0];
  const bodyRows = rows.slice(2);
  const headHtml = `<thead><tr>${heads.map((h) => `<th>${renderInline(h)}</th>`).join("")}</tr></thead>`;
  const bodyHtml = bodyRows.length > 0
    ? `<tbody>${bodyRows.map((row) => `<tr>${row.map((cell) => `<td>${renderInline(cell)}</td>`).join("")}</tr>`).join("")}</tbody>`
    : "";
  return `<table class="comparison-table">${headHtml}${bodyHtml}</table>`;
}

function renderList(lines, start, tag) {
  const marker = tag === "ul" ? /^[-*]\s+/ : /^\d+\.\s+/;
  const items = [];
  let i = start;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trim() === "") {
      i += 1;
      continue;
    }
    const match = line.match(marker);
    if (!match) break;
    const itemLines = [line.slice(match[0].length)];
    i += 1;
    while (i < lines.length && lines[i].startsWith("  ") && lines[i].trim() !== "") {
      itemLines.push(lines[i].slice(2));
      i += 1;
    }
    items.push(`<li>${renderInline(itemLines.join(" "))}</li>`);
  }
  return { html: `<${tag}>\n${items.join("\n")}\n</${tag}>`, next: i };
}

function renderInline(text) {
  return escapeInline(text)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>');
}

function escapeInline(text) {
  return escapeHtml(text);
}

export function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

export function stripHtml(value) {
  const template = document.createElement("template");
  template.innerHTML = value;
  return template.content.textContent?.replace(/\s+/g, " ").trim() || "";
}
