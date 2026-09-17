# Tablist

A Paper plugin for Effect SMP that overhauls the tab list:

- Every player's name shows their live ping next to it, colored green/yellow/red.
- The whole tab list (header, footer, dividers) is purple-themed.
- `/display lp tab enable|disable` (op only) - toggles whether each player's LuckPerms
  prefix appears before their name **in the tab list**.
- `/display lp name enable|disable` (op only) - toggles whether the LuckPerms prefix
  appears **above players' heads** in the world (via a scoreboard team prefix).
- Your logo image is baked into a custom font and displayed as a banner across the
  top of the tab list, above everyone's names.

Built against the Paper API for **1.21.11** (Java 21). Paper's API is stable across
the whole 1.21.x line, so this will also run on other 1.21.x builds without changes.

## Building it

You'll need Java 21 and Maven, and an internet connection (the build pulls
`paper-api` from PaperMC's repo and `luckperms-api` from Maven Central).

```
mvn clean package
```

The finished jar is `target/Tablist-1.0.0.jar` - drop it in your server's `plugins/`
folder and restart. LuckPerms is optional: the plugin runs fine without it, the two
`/display` toggles just won't have anything to show until LuckPerms is installed.

*(This was written in a sandboxed environment with no access to any Maven
repository - not even Maven Central - so the jar couldn't be compiled here. The
source was checked carefully against Paper's and LuckPerms' current API docs
instead. Please give it a real test on a server before relying on it.)*

### Don't have Java/Maven installed? Let GitHub build it for you, for free

This project already includes `.github/workflows/build.yml`, which tells GitHub
to compile the jar automatically. No command line needed on your end:

1. Go to [github.com/new](https://github.com/new), create a new repository (any
   name, Public or Private both work), and don't add a README/gitignore.
2. On the empty repo's page, click **"uploading an existing file"**, then drag
   in every file/folder from this project (keep the folder structure - `src/`,
   `.github/`, `pom.xml`, etc.) and commit.
3. Click the **Actions** tab at the top of the repo. A "Build plugin jar" run
   should already be in progress (or click **Run workflow** if not).
4. Once it finishes (green checkmark, ~30 seconds), open that run and scroll
   down to **Artifacts** - download `Tablist-plugin-jar`. That's a zip
   containing the actual `.jar` - unzip it and put the jar in your server's
   `plugins/` folder.

That's the whole GitHub part - you never need to touch git or a terminal.

## The image banner - how it actually works, and what you need to do

Minecraft's tab list header is plain text - the game has no "put an image here"
feature. The standard trick (used by a lot of servers that show a logo in tab) is:

1. Your logo is sliced into 192 vertical strips, each mapped to its own character
   in a custom font shipped inside a small resource pack (this is already done -
   see `tools/header_preview.png` for exactly what will be shown).
2. The plugin runs a tiny built-in web server that serves that resource pack as a
   `.zip` file.
3. When a player joins, the plugin tells their client to download the pack from
   your server and sends the header text using that custom font, which draws the
   banner.

**This means two things you need to do before it'll work:**

1. Open `config.yml` and set `header-image.enabled: true`.
2. Set `header-image.public-address` to an address your *players'* clients can
   reach - e.g. your server's domain, or its public IP. Also make sure the port
   in `header-image.port` (default `25566`) is open/forwarded on your network,
   same as your Minecraft port. If players can already connect to your server at
   `play.example.com:25565`, then `play.example.com` with port `25566` forwarded
   will work.

If you don't set this up, leave `header-image.enabled: false` and the plugin will
show a plain purple text banner instead - everything else (ping, prefixes,
purple theme) still works fine either way.

Players get a resource pack prompt when they join (not forced to accept, unless
you set `header-image.required: true`). This is normal for any server-side
resource pack and is exactly how e.g. custom-item texture packs get delivered too.

### If it looks off in-game

Bitmap font sizing genuinely can only be judged by eye in a real client - I
couldn't test-render this without one. If the banner looks too big/small or
misaligned, open `tools/generate_pack.py`, tweak `FONT_HEIGHT` (overall size) or
`FONT_ASCENT` (vertical position), re-run it, and copy the new
`src/main/resources/pack/pack.zip` + `header_chars.txt` into place before
rebuilding:

```
python3 tools/generate_pack.py tools/source_image.png /tmp/packbuild
cp /tmp/packbuild/pack.zip src/main/resources/pack/pack.zip
cp /tmp/packbuild/header_chars.txt src/main/resources/pack/header_chars.txt
mvn clean package
```

You can also change `CROP_TOP` / `CROP_BOTTOM` in that script to crop a different
slice of `tools/source_image.png` if you ever want to re-center it.

## Config reference (`config.yml`)

```yaml
luckperms:
  tab-prefix: false      # set by /display lp tab
  name-prefix: false     # set by /display lp name

format: "<prefix><white><name> <dark_gray>(<ping><dark_gray>)"

ping:
  good-color: "<green>"
  good-max: 100
  ok-color: "<yellow>"
  ok-max: 250
  bad-color: "<red>"

tab-list:
  footer-enabled: true
  footer: "..."           # MiniMessage, supports <online> and <max>

header-image:
  enabled: false
  port: 25566
  public-address: ""
  required: false
  prompt: "..."
  fallback-header: "..."  # shown when enabled: false
```

All the text fields use [MiniMessage](https://docs.advntr.dev/minimessage/format.html)
formatting (`<light_purple>`, `<bold>`, etc.), so you can freely re-color or
re-word anything without touching Java code.

## Commands & permissions

| Command | Effect |
|---|---|
| `/display lp tab enable` | Show LuckPerms prefixes in the tab list |
| `/display lp tab disable` | Hide them again |
| `/display lp name enable` | Show LuckPerms prefixes above players' heads |
| `/display lp name disable` | Hide them again |

Restricted to server operators by default (permission node `tablist.admin`,
`default: op`).

## Project layout

```
pom.xml
src/main/java/net/effectsmp/tablist/
  TablistPlugin.java          - main class / wiring
  command/DisplayCommand.java - /display command
  hook/LuckPermsHook.java     - all LuckPerms API calls live here
  listener/PlayerConnectionListener.java
  resourcepack/PackServer.java - the built-in pack-hosting HTTP server
  tab/TabListManager.java     - ping/prefix formatting, header/footer, teams
  util/TextUtil.java          - MiniMessage / legacy text helpers
src/main/resources/
  plugin.yml, config.yml
  pack/pack.zip, pack/header_chars.txt  - the pre-built banner resource pack
tools/
  generate_pack.py    - regenerate the banner pack from source_image.png
  source_image.png    - your original logo image
  header_preview.png  - exactly what gets cropped/used as the banner
```
