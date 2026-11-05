/*
 * Halo serves a legacy plugin's stylesheet through a shared bundle.css that is built
 * from every provider's style file, so the page loses all of its styling whenever that
 * one file is missing, stale or cached as empty — the script still runs, the page just
 * renders as plain text.
 *
 * The styles are therefore appended to main.js as text and injected on load as well.
 * Duplicated rules are harmless; a page without its stylesheet is not.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const dist = resolve(process.argv[2] ?? 'build/dist')
const jsPath = resolve(dist, 'main.js')
const cssPath = resolve(dist, 'style.css')

if (!existsSync(jsPath) || !existsSync(cssPath)) {
  console.log('[inline-style] nothing to inline yet')
  process.exit(0)
}

const css = readFileSync(cssPath, 'utf8')
if (!css.trim()) {
  console.log('[inline-style] stylesheet is empty, skipped')
  process.exit(0)
}

const banner = '/* styles inlined below by scripts/inline-style.mjs */'
let js = readFileSync(jsPath, 'utf8')
if (js.includes(banner)) {
  console.log('[inline-style] already inlined')
  process.exit(0)
}

// JSON.stringify handles the quotes, backslashes and newlines in the stylesheet.
const inject =
  `\n${banner}\n;(function(){try{` +
  `var id="online-stats-inline-style";` +
  `if(document.getElementById(id)){return;}` +
  `var el=document.createElement("style");` +
  `el.id=id;` +
  `el.textContent=${JSON.stringify(css)};` +
  `(document.head||document.documentElement).appendChild(el);` +
  `}catch(e){}})();\n`

js = js + inject
writeFileSync(jsPath, js)
console.log(`[inline-style] ${css.length} bytes of css inlined into main.js`)