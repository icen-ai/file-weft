import { copyFileSync, existsSync } from "fs";
import { resolve, dirname } from "path";
import { fileURLToPath } from "url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const source = resolve(__dirname, "..", "SKILL.md");
const target = resolve(__dirname, "SKILL.md");

if (!existsSync(source)) {
  console.error("SKILL.md not found at repository root:", source);
  process.exit(1);
}

copyFileSync(source, target);
console.log("Copied SKILL.md to fileweft-docs/SKILL.md");
