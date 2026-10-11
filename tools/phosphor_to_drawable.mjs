// 从 Phosphor Icons（@phosphor-icons/core，regular 字重）生成 Android vector drawable。
//
//   node tools/phosphor_to_drawable.mjs
//
// 需要联网。生成前会校验脚本里的名单与 AuroraIcons.kt 的 byName 完全一致——
// 两边不一致说明有人只改了一边，直接失败比悄悄少生成一个图标好。
// 生成后会删掉 res/drawable 里不在名单中的 ic_aurora_*.xml，避免留下孤儿资源。
import { readFileSync, writeFileSync, mkdirSync, readdirSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';

const OUT = 'app/src/main/res/drawable';
const ICONS_KT = 'app/src/main/java/dev/aurora/browser/ui/icons/AuroraIcons.kt';
const CDN = 'https://unpkg.com/@phosphor-icons/core@2.1.1/assets/regular';

// Phosphor 名 → 资源名。必须与 AuroraIcons.kt 的 byName 键一一对应（下面会校验）。
const ICONS = {
  'arrow-left': 'back', 'arrow-right': 'forward', 'house-simple': 'home',
  'squares-four': 'tabs', 'dots-three': 'more', 'magnifying-glass': 'search',
  'x': 'close', 'plus': 'add', 'shield-check': 'shield_check', 'lock-simple': 'lock',
  'star': 'star', 'clock-counter-clockwise': 'history', 'download-simple': 'download',
  'book-open-text': 'reader', 'eye-slash': 'incognito', 'share-network': 'share',
  'gear-six': 'settings', 'puzzle-piece': 'extensions', 'microphone': 'mic',
  'qr-code': 'qr_code', 'caret-right': 'chevron_right', 'minus': 'minus',
  'text-aa': 'text_size', 'moon': 'moon', 'speaker-high': 'volume',
  'globe': 'globe', 'trash-simple': 'trash', 'clock': 'clock',
  'desktop': 'desktop', 'translate': 'translate', 'image': 'image', 'sparkle': 'sparkle',
  'arrow-clockwise': 'refresh', 'clipboard-text': 'clipboard',
};

function assertNamesMatchAuroraIcons() {
  const source = readFileSync(ICONS_KT, 'utf8');
  const block = source.match(/private val byName = mapOf\(([\s\S]*?)\n\s*\)/);
  if (!block) {
    console.error(`无法从 ${ICONS_KT} 解析 byName，脚本需要同步更新。`);
    process.exit(1);
  }
  const declared = new Set(
    [...block[1].matchAll(/"([^"]+)"\s+to\s+/g)].map((m) => m[1]),
  );
  const here = new Set(Object.keys(ICONS));
  const missingHere = [...declared].filter((n) => !here.has(n));
  const extraHere = [...here].filter((n) => !declared.has(n));
  if (missingHere.length || extraHere.length) {
    if (missingHere.length) console.error(`AuroraIcons.kt 有但本脚本缺: ${missingHere.join(', ')}`);
    if (extraHere.length) console.error(`本脚本有但 AuroraIcons.kt 缺: ${extraHere.join(', ')}`);
    process.exit(1);
  }
  return declared;
}

const declared = assertNamesMatchAuroraIcons();
mkdirSync(OUT, { recursive: true });

let written = 0;
const expectedFiles = new Set();

for (const [name, res] of Object.entries(ICONS)) {
  const url = `${CDN}/${name}.svg`;
  const response = await fetch(url);
  if (!response.ok) {
    console.error(`下载失败 ${url} → HTTP ${response.status}`);
    process.exitCode = 1;
    continue;
  }
  const svg = await response.text();
  const d = svg.match(/<path d="([^"]+)"/)?.[1];
  if (!d) {
    console.error(`MISSING path for ${name}`);
    process.exitCode = 1;
    continue;
  }
  const xml = `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="256" android:viewportHeight="256"
    android:tint="?android:attr/colorControlNormal">
  <path android:fillColor="@android:color/white" android:pathData="${d}"/>
</vector>
`;
  const file = `ic_aurora_${res}.xml`;
  writeFileSync(join(OUT, file), xml);
  expectedFiles.add(file);
  written++;
}

// 清掉孤儿：图标从名单里移除后，旧 drawable 会一直留在 res 里（lint 也不会报）。
// 只有整轮下载都成功才敢删，否则一次网络抖动会把好文件也删掉。
let pruned = 0;
if (process.exitCode !== 1) {
  for (const file of readdirSync(OUT)) {
    if (file.startsWith('ic_aurora_') && file.endsWith('.xml') && !expectedFiles.has(file)) {
      unlinkSync(join(OUT, file));
      console.log(`pruned stale ${file}`);
      pruned++;
    }
  }
}

console.log(`wrote ${written}/${declared.size} drawables to ${OUT}${pruned ? `, pruned ${pruned}` : ''}`);
if (written !== declared.size) process.exitCode = 1;
