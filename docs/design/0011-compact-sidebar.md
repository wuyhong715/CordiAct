# Compact navigation drawer — 2026-11-15

Approved: compact brand + one search/new-conversation row; workspace heading, flat directory; bottom-right settings; bottom-left reserved for future account identity, deliberately no placeholder account or dead tap target.

- Width: 84% screen, max360dp. Brand48dp; action row48dp; workspace heading48dp.
- Search swaps in place; only one entry, single-line field; new conversation collapses to plus for narrow screens/large font.
- Workspace folder tint/semantics expresses active context, no second filled selection panel; conversations retain a subtle selected surface, 48dp min rows, one-line ellipsis, actual update date.
- Scrolling source behind fixed header/footer, shared masked progressive fades; content padding derived from measured chrome. Settings has48dp target and accessibility label. User area left blank.
- Existing loading/error/retry, workspace expand/create/rename/switch, current-workspace new-session and global search preserved.
- No session-title persistence migration or automatic overwrite in this visual slice: repeated legacy default names can still occur; update date helps disambiguate. Automatic titling requires a separate user-title provenance rule.

Verify: Kotlin-only local compile; CI UI component test for in-place search and settings placement/click, existing suite. Device density, keyboard and large-font acceptance remain unverified until installation.
