# Aurora Browser — Design System MASTER

> Global source of truth for Aurora Browser UI. Page-specific deviations live in `pages/<page>.md` and override this file.
> Source: `ui-ux-pro-max` dataset — style `dark-mode-oled` + `glassmorphism` (overlays); color `Developer Tool / IDE` dark palette; typography `Minimal Swiss` (Inter) + `JetBrains Mono`.

## 1. Style

- **Base**: Dark Mode (OLED) — deep background, low white emission, high contrast, eye-friendly, power efficient.
- **Overlay layer**: Glassmorphism for floating toolbars and bottom sheets (backdrop blur 16px, translucent surface, 1px light border).
- **Mode**: dark-first; light is a derived theme (v1 tokens defined, not implemented).
- **Complexity**: low. Minimal glow only. No decorative animation.

## 2. Color

```css
:root{
  /* primitive */
  --slate-950:#0B1120; --slate-900:#0F172A; --slate-850:#131C31; --slate-800:#1B2336;
  --slate-700:#272F42; --slate-600:#334155; --slate-500:#475569; --slate-400:#64748B;
  --slate-300:#94A3B8; --slate-200:#CBD5E1; --slate-50:#F8FAFC;
  --sky-400:#38BDF8; --sky-500:#0EA5E9; --green-500:#22C55E; --amber-500:#F59E0B; --red-500:#EF4444;

  /* semantic (dark) */
  --color-bg:var(--slate-900);
  --color-bg-elevated:var(--slate-850);
  --color-surface:var(--slate-800);
  --color-surface-hover:var(--slate-700);
  --color-border:var(--slate-600);
  --color-divider:rgba(148,163,184,.16);
  --color-text:var(--slate-50);
  --color-text-secondary:var(--slate-200);
  --color-text-muted:var(--slate-300);
  --color-text-disabled:var(--slate-400);
  --color-brand:var(--sky-400);
  --color-brand-strong:var(--sky-500);
  --color-on-brand:var(--slate-900);
  --color-success:var(--green-500);
  --color-warning:var(--amber-500);
  --color-danger:var(--red-500);
  --color-scrim:rgba(2,6,23,.72);
  --color-glass:rgba(19,28,49,.72);
  --color-glass-border:rgba(148,163,184,.14);
}
```

Contrast vs `--color-bg` (#0F172A): text 17.1:1 · secondary ~12.4:1 · muted 7.0:1 · brand 8.3:1 · success 7.8:1. Border #334155 is 1.7:1 — structural only, never a sole information carrier.

## 3. Typography

- UI: **Inter** (fallback Roboto / system, CJK → Noto Sans SC). Mono: **JetBrains Mono** for URL / domain / counters only.

| Token | Size/Line | Weight |
|---|---|---|
| --text-display | 28/34 | 700 |
| --text-title | 20/26 | 600 |
| --text-heading | 17/24 | 600 |
| --text-body | 15/22 | 400 |
| --text-label | 13/18 | 500 |
| --text-caption | 11/15 | 500 |
| --text-mono | 14/20 | 500 |

## 4. Space / Radius / Elevation / Motion

- Space (4/8dp): 4 · 8 · 12 · 16 · 24 · 32 · 48
- Radius: xs 6 · sm 10 · md 14 · lg 20 · xl 28 · full 999
- Elevation (dark): `--shadow-1 0 1px 2px rgba(0,0,0,.40)` · `--shadow-2 0 4px 12px rgba(0,0,0,.45)` · `--shadow-3 0 12px 32px rgba(0,0,0,.50)`
- Motion: fast 120ms · base 200ms · slow 320ms; ease `cubic-bezier(.2,0,0,1)`; enter decelerate, exit ≤ enter; press feedback 80–150ms; **never change layout bounds on press**; honor reduced motion.

## 5. Iconography

- **Phosphor Icons (Regular / outline)**. One style per hierarchy level. Sizes: sm 16 · md 20 · lg 24 · xl 32 dp.
- Decorative icons beside visible text are hidden from the a11y tree; icon-only buttons carry an accessible name and expose selected/expanded state.
- **No emoji as structural icons.**

## 6. Hard Rules

- Min touch target 48×48dp (Android); ≥8dp between adjacent targets.
- Respect `WindowInsets` for status bar + gesture nav; nothing tappable under system chrome.
- Body/secondary text contrast ≥4.5:1 in both themes; dividers visible in both themes.
- One scroll container per screen; `LazyColumn`/`LazyVerticalGrid` for lists.
- Semantic tokens only — no hardcoded hex in components.
- Back key: close panel → exit omnibox → page back → double-press to exit.

## 7. Anti-patterns

- Light mode default · Claymorphism / playful toy styling · emoji icons · layout-shifting press states · spinner over content on first load · color as sole state indicator · nested scroll containers.
