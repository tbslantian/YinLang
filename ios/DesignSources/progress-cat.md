# Walking cat progress artwork

Source: user-supplied `codex-clipboard-5dbd9987-3a97-4580-9c47-16d71d41416a.png`,
provided on 2026-11-20. The user explicitly selected local background removal
and animation authoring after the built-in image generation service returned
`403: Image generation is not enabled for this group`.

The original reference is preserved as `progress-cat-reference.png`. The local
authoring script `../scripts/prepare-progress-cat.py` extracts the first kitten,
removes the connected exterior white matte while retaining opaque white fur,
neutralizes edge color fringing, and deforms the four lower legs through eight
registered gait poses. No generated AI imagery or API fallback is used.

Run `python ios/scripts/prepare-progress-cat.py` with Pillow to regenerate the
eight transparent `progress-cat-0.png` through `progress-cat-7.png` assets and
the previews. Xcode/CI consume the committed PNGs and do not run this script.

Local visual brief: preserve the reference's headphones, closed happy eyes,
raised head, fluffy gray tabby fur and right-facing silhouette; transparent
surroundings, opaque fur; alternating paws; stable headphones and body; usable
on both light and dark progress tracks. There was no final imagegen prompt;
the built-in generation attempt failed before producing an image.

`DesignPreviews/walking-cat-progress.gif` is a design illustration, not an app
screenshot or evidence of device testing. The app plays the PNG sequence on
one Core Animation layer, with no per-frame SwiftUI state changes.
