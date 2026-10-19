# STARFALL RPG — asset credits

All bundled art and sound assets in this change are free-to-use **CC0** assets. They are included locally so the prototype remains offline.

- **RPG Urban Kit / urban tilemap** — Kenney. CC0 1.0 Universal. https://kenney-assets.itch.io/rpg-urban-kit
- **Roguelike/RPG tilesheet** — Kenney. CC0 1.0 Universal. https://kenney.nl/assets/roguelike-rpg-pack
- **RPG sound effects** — Kenney. CC0 1.0 Universal. https://kenney.nl/assets/rpg-audio

Bundled effects: footsteps, weapon slice, impact, and a book-opening cue used for magic. The current change bundles effects only; background music is not yet included. Attribution is not required by CC0, but credited as a courtesy.

- **Animated hero/companion/boar sprites** — Ninja Adventure asset pack (sprites mirrored in sang-guru/NinjaAdventure), CC0 1.0 Universal. The character sheets use a 4-direction, 7-row animation layout. Source: https://github.com/sang-guru/NinjaAdventure


## Additional audio and uploaded environment backgrounds

- **Kenney Sci-Fi Sounds** — CC0 1.0 Universal, used for varied projectile/magic shots and boss blasts. https://kenney.nl/assets/sci-fi-sounds
- **Kenney Impact Sounds** — CC0 1.0 Universal, used for varied impacts and grass footsteps. https://kenney.nl/assets/impact-sounds
- **User-provided area backgrounds** — copied from `asset starfall rpg/<area>/` into the app assets at build time. Original filenames are preserved. The forest backdrop is downsampled at runtime to limit memory use.


## Additional shooter audio and biome art

- **Kenney Sci-Fi Sounds** — CC0 1.0 Universal; varied laser shots and explosion sounds. https://kenney.nl/assets/sci-fi-sounds
- **Kenney Impact Sounds** — CC0 1.0 Universal; varied impact effects. https://kenney.nl/assets/impact-sounds
- **Kenney RPG Audio** — CC0 1.0 Universal; varied footstep sounds. https://kenney.nl/assets/rpg-audio
- **User-provided biome backgrounds** — all supported image files from `asset starfall rpg/{forest,rawa,pantai,pegunungan,salju,gurun,vulkanik}/` are copied into app assets during the build, retaining original filenames.
- **Biome presentation** — one downsampled backdrop is loaded at a time; the active biome advances every 30 defeats and uses lightweight region-specific particles. This keeps bitmap memory bounded on phones.
