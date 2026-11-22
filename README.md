# ServerRules

> **AI-generated project.** The whole source code of this plugin was written by
> an **AI assistant**, with no line-by-line human review. It is provided as-is:
> test it on a staging server before using it in production.

A Paper plugin that publishes the server rules, lets players file complaints, and
lets judges settle them. It also supports **oaths**: a small group of players
promises something and agrees on a sanction if the promise is broken.

Everything that is available in an in-game menu (GUI) is also available as a
command, and the command names are configurable.

- Target: **Paper 26.3** (Java 25)
- Economy: **Vault** with **EssentialsX** as the economy provider
- Storage: SQLite (bundled, no setup required)
- Languages: 13 bundled files, each falling back to English

## Dependencies

ServerRules does **not** work without these plugins:

| Plugin | Why it is required |
|---|---|
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | Economy abstraction used to apply fines |
| [EssentialsX](https://essentialsx.net/) | Must be the registered **Vault economy provider** (checked at startup) |

If Vault is missing, or if the Vault economy provider is not EssentialsX, the
plugin logs a clear error and disables itself.

Because fines may push a player's balance below zero, allow negative balances in
your EssentialsX configuration (`economy.min-money`), otherwise the fine is
refused (a warning is logged at startup).

## Installation

1. Make sure the server runs **Paper 26.3 on Java 25**.
2. Put `Vault` and `EssentialsX` in the server's `plugins/` folder.
3. Drop `ServerRules-x.y.jar` into `plugins/`.
4. Start the server once to generate `plugins/ServerRules/config.yml` and the
   `plugins/ServerRules/lang/` folder.
5. Add your rules in `config.yml` (see below), then run `/rules reload`.

### Building from source

The project uses the Gradle wrapper, so no local Gradle install is needed, but a
**JDK 25** must be available.

```bash
# with a JDK 25 installed system-wide
./gradlew build
```

The plugin jar is produced in `build/libs/`. The build uses the Shadow plugin to
bundle the SQLite JDBC driver.

If your default `java` is not Java 25, point `JAVA_HOME` at a JDK 25:

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

## Configuration

`config.yml` is generated on first start. It is shipped with **no default
rules**: administrators add their own.

| Key | Meaning |
|---|---|
| `language.default` | Default language code (file `lang/<code>.yml`) |
| `language.per-player` | If `true`, each player sees messages in their client's language when a file exists |
| `rules-display-delay-seconds` | Delay before showing the rules after a player's first join |
| `commands.<rules/plaint/judge/oath>.name` | Command name (configurable) |
| `commands.<...>.aliases` | Command aliases |
| `complaint.cooldown-seconds` | Minimum delay between two complaints from the same player |
| `gui.input-timeout-seconds` | Delay before a chat input request is cancelled |
| `oath.code-length` | Length of an oath join code |
| `oath.code-expiry-seconds` | How long a code stays valid |
| `oath.code-max-failed-attempts` / `oath.code-lockout-seconds` | Wrong-code lockout |
| `oath.cancel-request-expiry-seconds` | How long a cancellation request stays pending |
| `oath.complaint-grace-days` | Days after an oath expires during which it can still be cited |
| `oath.max-players` | Maximum number of parties in an oath |
| `judge.min-percent` / `judge.max-percent` | Modulation range for a judge |
| `superjudge.min-percent` / `superjudge.max-percent` | Modulation range for a superjudge |
| `rules.confirm-timeout-seconds` | Delay to confirm a rule change |
| `rules.list` | The rules themselves (see below) |

### Adding a rule

Rules are stored in `rules.list`, keyed by their position (1, 2, 3...):

```yaml
rules:
  list:
    1:
      id: 1
      name: "No griefing"
      description: "Do not destroy or modify other players' builds."
      sanction:
        type: FINE
        amount: 500
    2:
      id: 2
      name: "No insults"
      description: "Insults are forbidden in chat."
      sanction:
        type: TEMP_BAN
        duration: "24h"
  ```

`type` is either `FINE` (with `amount`) or `TEMP_BAN` (with `duration`, a
duration string such as `30m`, `24h` or `7d`). Each rule also carries an
internal, stable `id` that is generated automatically: **do not edit it by
hand**.

You can also manage rules in game with `/rules add|edit|remove|move`, or through
the management menu `/rules manage` (`serverrules.manage`).

## Commands

Command names and aliases are configurable (`/rules`, `/plaint`, `/judge`,
`/oath` by default).

### Rules

| Command | Effect |
|---|---|
| `/rules` | Open the rules menu (GUI); the console still gets the chat table |
| `/rules <number>` | Show a single rule |
| `/rules new` | Review changes since your last visit |
| `/rules gui` | Explicit alias of `/rules` |
| `/rules manage` | Open the rules management menu (editing, `serverrules.manage`) |
| `/rules add` `edit <n>` `remove <n>` `move <n> <position>` `reload` | Manage rules |
| `/rules confirm` | Confirm a pending rule change |

### Complaints (players)

| Command | Effect |
|---|---|
| `/plaint` | Open the complaint menu: your complaints + "New complaint" |
| `/plaint <player> <rule or oath> <details>` | File a complaint in one line. The reference is a rule number (`3`) or an oath id (`oath:12`) |

### Judge and superjudge

| Command | Effect |
|---|---|
| `/judge` | Open the complaints menu |
| `/judge list [status]` | List complaints in chat |
| `/judge view <id>` | Complaint details |
| `/judge sanction <id>` | Apply the planned sanction (with confirmation) |
| `/judge sanction <id> <value>` | Apply a modulated value (rules only, within role limits) |
| `/judge dismiss <id>` | Dismiss without further action |
| `/judge done <id>` | Mark a custom sanction as executed |
| `/judge rule <id> <n>` | Change the cited rule (superjudge only) |

All of these respect the conflict-of-interest rule: a judge who is involved in a
complaint cannot handle it (unless they have `serverrules.conflict.bypass`).

### Oaths

| Command | Effect |
|---|---|
| `/oath` | Open the oath menu |
| `/oath create` | Open the oath creation menu |
| `/oath create <duration> fine <amount> <promise>` | One-line creation, fine |
| `/oath create <duration> ban <ban-duration> <promise>` | One-line creation, temporary ban |
| `/oath create <duration> custom "<sanction>" <promise>` | One-line creation, custom sanction |
| `/oath join <code>` | Join an oath with its code (then Accept / Refuse) |
| `/oath accept` / `/oath refuse` | Answer a pending invitation |
| `/oath info <id>` | Oath details |
| `/oath cancel <id>` | Request, or confirm, the cancellation of an oath |

When an oath is created, the founder receives the join **code** in chat (click to
copy).

## Permissions

| Permission | Default | Role |
|---|---|---|
| `serverrules.complaint` | everyone | File a complaint |
| `serverrules.oath` | everyone | Create and join oaths |
| `serverrules.judge` | op | Receive and handle complaints; `/judge` |
| `serverrules.superjudge` | op | Everything a judge can do, plus changing the cited rule, wider modulation (10%-500%) |
| `serverrules.manage` | op | Open `/rules manage` and use every action inside it (add, edit, remove, move) |
| `serverrules.conflict.bypass` | op | Handle complaints you are involved in |
| `serverrules.add` | op | Add a rule |
| `serverrules.edit` | op | Edit a rule |
| `serverrules.remove` | op | Remove a rule |
| `serverrules.move` | op | Reorder rules |
| `serverrules.reload` | op | Reload configuration, languages and rules |

## Languages

The plugin ships with 13 language files in `src/main/resources/lang/`
(`en`, `fr`, `es`, `de`, `it`, `pt`, `nl`, `pl`, `ru`, `tr`, `ja`, `ko`,
`zh_CN`), copied to `plugins/ServerRules/lang/` on first start.

Messages use [MiniMessage](https://docs.advntr.dev/minimessage/index.html)
formatting.

### Adding a language

1. Copy an existing file, for example `lang/en.yml`, to `lang/<code>.yml` in
   `plugins/ServerRules/lang/` (`<code>` is the client locale, e.g. `sv`).
2. Translate the values. **Keep the keys and the `{placeholders}` unchanged.**
3. Add the code to the `Lang.AVAILABLE` list in
   `src/main/java/com/pokrag/serverrules/lang/Lang.java` and rebuild, or set
   `language.per-player: false` and use `language.default: <code>`.
4. Missing keys fall back to the default language, then to the raw key, so a
   partial file never breaks the plugin.

Editing files directly under `plugins/ServerRules/lang/` takes effect after
`/rules reload`: external files override the copies bundled in the jar.

## Credits

This plugin was **generated by an AI assistant** from a written specification,
including its source code, configuration and translations.

ServerRules is released under the **LGPL v3** license.
