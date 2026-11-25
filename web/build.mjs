// Builds web/AudioVerse.html: one self-contained file (no CDN, no server).
// Usage: node web/build.mjs <hls.light.min.js> <mpegts.js>
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const [hlsPath, mpegtsPath] = process.argv.slice(2);
if (!hlsPath || !mpegtsPath) { console.error("usage: node build.mjs <hls.light.min.js> <mpegts.js>"); process.exit(1); }

// Inline scripts must not contain a closing script tag.
const safe = code => code.replace(/<\/script/gi, "<\\/script").replace(/<!--/g, "<\\!--");
const stations = JSON.parse(readFileSync(join(here, "../audioverse/app/src/main/assets/stations.json"), "utf8"));
const lib = (path, name) => `/* ${name} — Apache License 2.0 (bundled, unmodified) */\n` + safe(readFileSync(path, "utf8"));

let html = readFileSync(join(here, "app.html"), "utf8");
html = html.replace("/*__HLS__*/", () => lib(hlsPath, "hls.js 1.5.20 light, © Dailymotion"))
           .replace("/*__MPEGTS__*/", () => lib(mpegtsPath, "mpegts.js 1.7.3, © xqq / bilibili"))
           .replace("/*__STATIONS__*/", () => safe(JSON.stringify(stations)));
writeFileSync(join(here, "AudioVerse.html"), html);
console.log("wrote web/AudioVerse.html", (html.length / 1024).toFixed(0), "KB");
