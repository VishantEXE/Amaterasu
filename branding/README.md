# Amaterasu app artwork

The user supplied `Amaterasu Beneath a Crimson Sun.png` on 2026-11-08 for the app icon. `amaterasu-icon-original.png` preserves its original bytes (SHA256 `4d2aa25213b00f77c4e2ed8cdb0e8807e583adb9b1dce276ced2ecbb27d6a801`). On 2026-11-08 the project owner confirmed authorization to distribute this artwork with Amaterasu under GPL-3.0-only. No separate artist attribution was supplied. The artwork is a project asset, separate from the MIT/BSD library assets.

Run `tools/host-discovery/prepare-icon.ps1` to regenerate the 512×512 Android drawable. It resamples the supplied image without changing the composition. The adaptive launcher foreground adds 24% padding on white so the illustration remains inside Android launcher masks. The legacy launcher uses the full image. The app header uses the same drawable; the existing monochrome sun remains the notification glyph.
