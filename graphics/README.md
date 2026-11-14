## Colors
The main "brand" colors are:
- ![#a4c639](https://placehold.co/15x15/a4c639/a4c639.png) `#a4c639` [Android green (pre-2018)](https://en.wikipedia.org/wiki/Android_green)
- ![#1a1a1a](https://placehold.co/15x15/1a1a1a/1a1a1a.png) `#1a1a1a` 90% gray

## Logo
When used as an icon, the gray background is used with a green logo. <br>
In other cases, the logo is used with a white background and a green logo.

The logo is a fountain-pen nib pointing up that doubles as the letter 'A' (`allnotes-icon.svg`):
- the breather hole is the counter of the A,
- the slit runs from near the tip down to the hole,
- the pointed notch cut from the base separates the two legs.

It is a single path with `fill-rule="evenodd"` and no masks or filters, so it converts directly to an Android vector
drawable. The glyph is drawn in a 512×512 design space with its bounding box at x 143–369, y 92–412.

## Files
| File | Use |
|------|-----|
| `allnotes-icon.svg` | Master icon, 512×512, full-bleed gray. Source of `ic_launcher-playstore.png` and the fastlane `icon.png`. |
| `ic_launcher_background.svg` | Adaptive icon background layer (108dp). |
| `ic_launcher_foreground.svg` | Adaptive icon foreground layer (108dp), green glyph 44dp tall. |
| `ic_launcher_monochrome.svg` | Themed (Android 13+) icon layer, white glyph; the system tints it. |
| `adaptive_icon.svg` | Both layers together, with guides for the 72dp visible area and the 66dp safe zone. |
| `feature-graphic.svg` | Play Store feature graphic (1024×500). The wordmark is Public Sans Bold, converted to outlines. |

The complete glyph must always fit inside the 66dp safe-zone circle of the 108dp adaptive icon, as shown in
`adaptive_icon.svg`. The vector drawables in `app/src/main/res/drawable-v24/` use the same path and the same
transform as the `ic_launcher_*` SVGs.
