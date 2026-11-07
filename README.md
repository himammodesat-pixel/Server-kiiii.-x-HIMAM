
PvP follows the overworld clock in every world: it is disabled from tick 23000
until tick 13999, turns on at tick 14000 when night is darker, and turns off at
tick 23000 when the moon has set at dawn. Players see a title when the schedule
changes and when they join; the scoreboard also shows whether PvP is allowed.
Build the `HimamPvPSchedule` plugin with
`bash custom-plugins/HimamPvPSchedule/build.sh` and restart Paper to load it.
# Minecraft server

The Codespaces `postStartCommand` starts the server and its control services
automatically whenever the container starts. These processes run independently
from the VS Code terminal, so closing the terminal does not stop them. You can
also start them from VS Code's Tasks menu or a terminal:

```bash
./start-server-background.sh
```

Launcher output is written to `logs/server-manager.log`. The launcher lock
prevents duplicate Paper servers if the task is run again. To run the launcher
attached to the current terminal for live output instead, use `./start-server.sh`.

Stop a running server safely, even if the terminal used to start it is gone:

```bash
./stop-server.sh
```

The Android-friendly control panel starts automatically when the workspace
opens. It shows server status, online players, and recent logs, and provides
start/stop buttons, broadcasts, player moderation, whitelist controls, quick
commands, and an authenticated Minecraft console. To start it manually, run
`./start-server-panel.sh`.
Forward port `8765` in the Codespaces **Ports** tab and keep its visibility set
to **Private**. Open the forwarded HTTPS URL on your Android phone and use the
browser menu to install/add the panel to your home screen. Log in to GitHub
when prompted.

Get the panel's access code in the Codespace terminal with
`cat .paper/server-panel-token`, then enter it in the panel. This code has no
expiration and remains the same across service restarts while the Codespace
workspace file is preserved. The web panel saves it in that browser's local
storage until removed; use this only on a trusted personal device because it
grants control over this server. The Android app keeps its saved code encrypted
on the device. If the Codespace workspace or token file is deleted, a new code
will be generated and must be saved again. The panel can start or gracefully
stop Paper, but it cannot keep a Codespace alive past GitHub's idle timeout or
maximum lifetime.
If the code is rejected, copy it again directly from that terminal command,
paste it into the panel's code field without quotation marks, and press
**Simpan**. A green “Kode akses diterima” message confirms it worked. The
browser panel remembers the code until you clear it or remove browser data.
The Minecraft console can run powerful server commands; it never runs Linux
terminal commands. Use the dedicated **Matikan** button for a graceful server
shutdown.

## Android app

The Android app uses GitHub's OAuth device flow with the `codespace` scope to
start this Codespace through GitHub's API. Once GitHub login is approved, the
app can start the Codespace, wait for the control panel's Cloudflare Tunnel to
return, and control the server directly in the app. It does not open or require
a Codespaces panel link. The user enters the random panel access code once;
the app encrypts it using Android Keystore. The GitHub access token is also
encrypted and saved in the app's private storage, so normal app restarts and
updates do not require another device login. The Android Keystore key does not
require periodic user authentication. GitHub controls whether its token stays
valid: revoking the OAuth grant, clearing app data, uninstalling the app, or
changing devices can require login again. The app preserves a saved token when
GitHub denies Codespaces access, so permission errors do not automatically
erase it. The OAuth Client ID is public app configuration, not a secret.
Never put an OAuth client secret, GitHub token, or Cloudflare token in the APK.

Create a GitHub OAuth App under **Settings → Developer settings → OAuth Apps**.
Set its homepage to this repository URL and its authorization callback URL to
`https://github.com/login/device` (the device flow does not use a redirect
callback). Under **Optional features**, enable **Device Flow**. The app
requests only the `codespace` OAuth scope for the start action. Use the
OAuth App's public Client ID when building the APK; do not use or distribute
the OAuth App's client secret:

```bash
cd android-panel
./gradlew assembleDebug \
  -PgithubOAuthClientId=YOUR_PUBLIC_CLIENT_ID \
  -PpublicPanelUrl=https://panel.vannilasmp.my.id/
```

The build requires JDK 17 or newer and Android SDK Platform 36. The APK is
written to `android-panel/app/build/outputs/apk/debug/app-debug.apk`. This is a
debug-signed APK for personal installation, not Play Store distribution.
After installing, choose **Hubungkan GitHub** once and approve the device
code. The encrypted login is reused on later launches; choose **GitHub
tersambung · login ulang** only if you intentionally need to reconnect. Then
press **Nyalakan Codespace**. GitHub may ask for confirmation, and the
Codespace consumes billable compute while running. Enter the panel access
code once in the app; it is stored encrypted on that Android device. The app
then waits for the Codespace, Paper, and Cloudflare Tunnel and provides
native server-status, start/stop, broadcast, moderation, and console controls.

Starting a stopped Codespace does not guarantee that its web services are
already ready at the instant GitHub reports it as available. Device network,
GitHub authorization, Codespaces availability, Android background limits, and
Cloudflare availability can all affect startup; uninterrupted operation
cannot be guaranteed.

GitHub's 240-minute setting is an **idle timeout**, not a timer that stops the
Codespace exactly four hours after it starts. GitHub documents a 12-hour
maximum Codespace lifetime even when it is active. The timeout setting for an
existing Codespace is selected when that Codespace is created. Set the default
to 240 minutes at
[GitHub Codespaces settings](https://github.com/settings/codespaces) before
creating a new Codespace if you need that idle timeout.

## Cloudflare Tunnel

The prepared tunnel publishes only the web control panel at
`https://panel.vannilasmp.my.id/`; it does not expose Minecraft ports and it
does not keep a stopped Codespace online. Java/Bedrock game traffic continues
to use the separately configured Playit tunnels.

1. `panel.vannilasmp.my.id` is routed to the existing Cloudflare tunnel
   `panel.vannilasmp.my.id` (tunnel ID
   `b3723ba3-3b73-4c92-963a-d620d755dbd4`). The DNS CNAME is created by
   `cloudflared tunnel route dns`; its ingress rule forwards only to
   `http://127.0.0.1:8765`.
2. The tunnel does not create a Cloudflare Access policy. In Cloudflare, add a
   self-hosted application for this hostname and an **Allow** policy restricted
   to your own verified email. Until that is enabled, the panel website is
   internet-accessible; its server-control APIs still require the random panel
   access code.
3. The connector credential for this existing tunnel is already stored as the
   repository Codespaces secret `CLOUDFLARE_TUNNEL_CREDENTIALS_B64`. It is a
   base64-encoded scoped tunnel credential; the startup script decodes it into
   a mode-600 file under `.paper/`. Never put it in chat or commit it.
4. Rebuild the Codespace container once to apply its `.devcontainer` setup.
   The build installs `cloudflared` from Cloudflare's signed package repository.

The Codespaces `postStartCommand` automatically starts Paper, the control
panel, and Cloudflare Tunnel whenever the existing Codespace starts. The
**Cloudflare Tunnel** task starts `cloudflared` from the Codespaces secret
and writes a mode-600 credential file under `.paper/`, which is excluded from Git.
Tunnel logs go to `logs/cloudflare-tunnel.log`. When the Codespace stops,
`cloudflared` stops too; the hostname becomes available again after the
Codespace and tunnel restart.

The `.devcontainer` `postStartCommand` starts Paper, the control panel, and
the configured tunnel whenever the container starts, including when the app
starts a stopped Codespace through GitHub. Rebuild the container once after
adding this configuration. `postCreateCommand` installs `cloudflared` from
Cloudflare's signed Debian repository.

This also starts the server when you reconnect to a Codespace if the server is
not already running. Codespaces can still stop the workspace when it reaches
its idle timeout or maximum lifetime; detaching the launcher cannot keep a
stopped Codespace online or prevent GitHub from stopping it.

GitHub currently allows a default idle timeout of 5–240 minutes (up to 4
hours), not 6 hours. Set it to 240 minutes at
[GitHub Codespaces settings](https://github.com/settings/codespaces). This
default applies to newly created Codespaces; the timeout for an existing
Codespace is selected when it is created. A Codespace also has a maximum
lifetime of 12 hours, regardless of activity. Keeping a Codespace running
uses billable compute time.

This sends Paper's `stop` command through its local console pipe and waits for
worlds to save. It does not force-kill the server. Set `STOP_SERVER_TIMEOUT`
to change the wait limit (default 120 seconds).

The launcher starts Paper with a 16 GB maximum and 14 GB initial heap. It checks
Playit at startup and starts or restarts its agent as needed. The agent must be
claimed at `~/.config/playit/playit.toml`. Playit runs even when
`online-mode=false`; this exposes the offline-mode server publicly, where
players can impersonate usernames (including operators). Use `online-mode=true`
for a public server where player accounts are authenticated. A launcher lock
prevents duplicate starts.

The server list shows the HIMAM SMP MOTD while Paper is online. When the server
is fully stopped, Minecraft clients cannot receive a custom offline MOTD from
it; they show their own connection-failed status instead.

The HIMAM SMP server artwork is in `server-icon.png` at the server root as a
64x64 PNG. Paper loads this icon when it starts, so the new design appears when
the server next starts.

The launcher connects Paper's console input to a private named pipe, so the
server does not depend on the terminal used to start it. Send a console command
with:

```bash
printf 'list\n' > .paper/server-console
```

When public online-mode is enabled, the Java Playit tunnel must use TCP and
forward to `127.0.0.1:25565`.

Bedrock Edition is provided by Geyser and Floodgate. Its public Playit tunnel
must use UDP and forward to `127.0.0.1:19132`. Java and Bedrock tunnels have
separate public addresses and ports.

Geyser uses Floodgate authentication so Bedrock players can join without
linking a Java account. Restart Paper with `./start-server.sh` after
configuration changes.

GriefPrevention starts new players with 10,000 claim blocks, enough for a
10,000-block claim such as 100x100. The accrued claim-block cap remains 80,000.
Online players received a 10,000-block bonus when this setting was applied;
configuration changes to starting blocks take effect for new players after
GriefPrevention loads the updated config on server startup.
Its common claim, trust, chest-protection, and claim-list messages use the
HIMAM color theme and Indonesian wording. Core protection rules and enabled
worlds were left unchanged. Hopper access, wooden doors, trapdoors, and pets
outside claims now use the stricter protection options.

Every 60 minutes a player is online—whether AFK or active—they receive 1,000
bonus claim blocks. The online-time timer pauses while disconnected and saves
progress across reconnects and server restarts. The scoreboard displays the
countdown, reward amount, and remaining claim blocks. This uses the
`HimamClaimRewards` plugin, built from
`bash custom-plugins/HimamClaimRewards/build.sh`; Paper must restart once to
load the plugin for the first time.

## Rank kits and scoreboard

Use `/kit` to open the Indonesian kit menu. Food and iron equipment kits are
available to every rank. The rank kits are locked to their matching LuckPerms
groups with `playerkits.kit.<kit>` permissions: `default` gets `member`, and
`vip` gets `diamond`; `builder`, `helper`, `moderator`, `admin`, and `owner`
get their matching kits. The VIP kit keeps its balance and level requirements;
the owner wildcard can use every rank kit. New PlayerKits2 kits now require
permission by default, and kit previews also check permission.

AnimatedScoreboard now uses the same layout for every rank and world, with a
layered HIMAM title, section headers, and bordered rows for a live rank label
from Vault/LuckPerms (`owner`, `admin`, `moderator`, `helper`, `builder`, `vip`,
or `default`), player name, balance, world, ping, and claim reward
countdown/balance. It also shows the current 24-hour Minecraft time from the
overworld clock, refreshed from live world ticks, and the ClearLag auto-removal
countdown from its active task. The scoreboard links to `/shop`, `/kit`, and
`/claim`. No staff-only scoreboard is selected.

Killing a hostile mob triggers one of 20 random HIMAM kill effects at the
defeated mob's location, not at the killer. The `HimamKillEffects` plugin
bridges Paper's entity-death event to the datapack. Build it with
`bash custom-plugins/HimamKillEffects/build.sh`, then restart Paper to load it.
Particles use normal visibility and reduced counts to limit client/network
load. Effects use particles and sounds only; players and passive mobs do not
trigger them, and visual lightning will not ignite blocks or damage nearby
players. No resource pack is needed: all effects use vanilla particles and
sounds. Download the standalone datapack as
[`himam_kill_effect-datapack.zip`](./himam_kill_effect-datapack.zip); install it
in a world's `datapacks` directory. The current server is Minecraft 26.2;
Bedrock connects through Geyser, whose particle translation should be checked
with a Bedrock client.

## Shop

Open the Indonesian shop with `/shop`. Left-click an item to buy it, right-click
to sell it, and Shift + right-click to sell all matching items. Prices use
Koin and Indonesian number formatting. `/sell` is routed through
EconomyShopGUI's prices instead of Essentials' separate worth list. For bulk
sales use `/sellall inventory`.

## Custom stack sizes

The `HimamItemStackSizes` plugin lets Ender Pearls stack to the vanilla
maximum of 99 and Totems of Undying stack to 64. It updates existing items when
players join or open inventories, and normalizes inventories before player
actions and automated transfers so items can merge using the custom sizes.
Build it with `bash custom-plugins/HimamItemStackSizes/build.sh`, then restart
Paper to load the plugin.

## Custom weapons

`HimamCustomWeapons` adds four original skill weapons: Riffbreaker, Feedback,
Stormcaller, and Soul Amplifier. Operators can give them with
`/himamweapon give <player> <weapon-id>`; right-click to use a weapon's skill.
Custom weapons dropped when their owner dies remain at the death location,
cannot despawn or be damaged, and emit a green particle beacon. The owner gets
a compass that tracks each weapon. Other players cannot pick the weapon up
before tick 14000; at night it can be looted. The owner's compass is removed
when the weapon is picked up. Tracking data survives server restarts. Build
with `bash custom-plugins/HimamCustomWeapons/build.sh`, then restart Paper.

Player-facing plugin labels use the HIMAM SMP theme and Indonesian where the
plugin provides a translation: this includes the scoreboard, TAB list, skills,
Bedrock server label, and join/quit messages. This presentation pass does not
change survival rules, skill balance, anti-cheat punishments, or Veinminer and
ClearLag behavior.
