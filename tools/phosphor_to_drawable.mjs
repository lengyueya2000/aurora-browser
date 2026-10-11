import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const OUT = 'app/src/main/res/drawable';
const CDN = 'https://unpkg.com/@phosphor-icons/core@2.1.1/assets/regular';

// 名称 → 资源名。必须与 AuroraIcons.kt 的常量一一对应。
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

mkdirSync(OUT, { recursive: true });
let n = 0;
for (const [name, res] of Object.entries(ICONS)) {
  const svg = await (await fetch(`${CDN}/${name}.svg`)).text();
  const d = svg.match(/<path d="([^"]+)"/)?.[1];
  if (!d) { console.error(`MISSING path for ${name}`); process.exitCode = 1; continue; }
  const xml = `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="256" android:viewportHeight="256"
    android:tint="?android:attr/colorControlNormal">
  <path android:fillColor="@android:color/white" android:pathData="${d}"/>
</vector>
`;
  writeFileSync(join(OUT, `ic_aurora_${res}.xml`), xml);
  n++;
}
console.log(`wrote ${n} drawables to ${OUT}`);
