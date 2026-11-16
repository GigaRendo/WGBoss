# WGBosses (WorstGen SMP)

Server-side Fabric mod (MC 26.3, Java 25). Random giant bosses made from any mob on your server.

## Build (GitHub, no setup needed)
1. Unzip this folder. Create a new GitHub repo.
2. Upload EVERYTHING inside the folder (including the hidden `.github` folder) to the repo.
   - If `.github/workflows/build.yml` didn't upload, use "Add file > Create new file" and paste it with that exact path.
3. Open the **Actions** tab > latest "build" run > download the **wgbosses-jar** artifact.
4. Put the jar in your server's `mods` folder.

If the build fails, paste the error log to Claude and it will be fixed.

## Commands
- `/wgboss spawn [mob] [tier]` (op) - replaces any active boss silently
- `/wgboss kill` (op) - removes the boss with no drops
- `/wgboss bloodmoon` (op) - force a Blood Moon
- `/wgboss reload` (op) - reload config.json and messages.json
- `/wgboss top [kills|damage|calamity|highest|mvp]`
- `/wgboss info`

## Config
`config/wgbosses/config.json` (spawn timing, warnings, tiers, rewards, blacklist, blood moon)
`config/wgbosses/messages.json` (taunts, names, taglish lines)

## Placeholders (needs placeholder-api)
`%wgboss:top kills 1 name%`, `%wgboss:top damage 1 value%`, `%wgboss:boss name%`, `%wgboss:boss active%`, `%wgboss:boss hp%`
