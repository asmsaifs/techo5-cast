# Android app design

The UI follows a Stitch project, "TECHO5 Cast (Android)" (project id `17628520851805697509`, design
system `assets/3173294586011338837`): dark, blue-black surfaces, one teal accent (`#2DD4BF` seed) for
the primary action and the casting state, amber for warnings, Manrope for headings and Inter for text,
rounded tonal cards with no heavy shadows.

Screens designed there: Home (casting card, Your Shows, Found nearby, Cast a file / Paste a link),
Settings (Picture, Power, Diagnostics, About) and the icon and logo sheet.

In the code:

- Tokens, typography and shapes: `android/app/src/main/kotlin/dev/techo5/cast/app/ui/Theme.kt`.
- Shared pieces (section label, panel, status pill): `ui/Components.kt`.
- Fonts (OFL): `res/font/manrope.ttf`, `res/font/inter.ttf`.
- The mark: `res/drawable/ic_logo_mark.xml`. The launcher icon is an adaptive icon
  (`res/mipmap-anydpi/ic_launcher.xml`) with the mark scaled into the safe zone, plus a monochrome
  layer for themed icons.
