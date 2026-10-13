# Notices

## Third-party assets

### OpenPigeon game posters

The images under `app/src/main/assets/game_posters/` are taken from **OpenPigeon**
(<https://github.com/OpenBubbles/OpenPigeon>), Copyright (C) 2023-2026 OpenPigeon
Contributors, and are used unmodified except for being renamed to this app's game
ids and re-encoded as JPEG thumbnails when a message carries one.

They are taken from the **`v1-final-agpl` tag**, where they were published under
**AGPL-3.0**. This matters, and is not interchangeable with taking them from
`main`:

> This license applies only to releases after the tag v1-final-agpl. Assets as
> they existed in earlier releases were published under AGPL-3.0 and remain
> available under that license to anyone who received them.
> — `LICENSE-ASSETS`, OpenPigeon Asset License 1.0, §1

Assets in releases **after** that tag are under the OpenPigeon Asset License 1.0,
whose §3 forbids making them available to any third party "as part of any
software". Nothing from those releases is included here, and nothing may be
added. When refreshing or extending this set, take it from `v1-final-agpl`:

```
git show v1-final-agpl:app/src/main/res/drawable/<name> > app/src/main/assets/game_posters/<id>.<ext>
```

| Local file | Upstream (`v1-final-agpl`) |
|---|---|
| `game_posters/knock.png` | `res/drawable/kom1ph.png` |
| `game_posters/connect.png` | `res/drawable/connect4.png` |
| `game_posters/anagrams.png` | `res/drawable/anagrams_6l.png` |
| `game_posters/hunt.jpg` | `res/drawable/wordhunt.jpg` |
| `game_posters/wordbites.png` | `res/drawable/wordbites.png` |
| `game_posters/sea.png` | `res/drawable/battleship.png` |
| `game_posters/pool.jpg` | `res/drawable/pool_normal_preview.jpg` |
| `game_posters/golf.png` | `res/drawable/golf.png` |
| `game_posters/beer.png` | `res/drawable/cuppong.png` |
| `game_posters/darts.png` | `res/drawable/darts.png` |

The same rule the pigeon project records in its own `NOTICE.md` applies here.
