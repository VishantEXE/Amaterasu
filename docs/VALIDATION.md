# Validation and limitations — 0.6.1

## Measured device behavior

Live picture and device audio have been exercised with stock Moonlight XR on Quest 3 and stock Moonlight on a Lenovo Android tablet, using an Odin2 Portal host. Local Wi-Fi and mobile hotspots work without internet access. Lower-resolution/bitrate gameplay was reported smooth and playable. These are observed configurations, not guarantees for every client or Android device.

FSR 1080p to 1440p and 4K have been exercised. Heavy-game 4K HEVC sessions also ended with Android recording LOW_MEMORY termination of the host; receiver errors included -1. A successful ten-minute interval did not prevent a later termination. Host graphics memory remained bounded in those captures. Some samples showed substantial available system memory shortly before a kill, so the evidence does not establish a universal 8 GB RAM limit or prove a specific vendor/kernel cause.

Automatic dimming and normal brightness restoration after stopping were verified on the host. Device-side audio mute is not reliable on every path; set host media volume to zero as a fallback. Apps can prohibit Android playback capture.

## Build checks

The build invokes Java checks for settings, frame/audio pacing, bounded buffers, protocol fragmentation, congestion decisions, session epochs and saved-state ownership. These verify selected logic, not sustained gameplay performance. Layout-renderer images are interface previews, not gameplay screenshots.

## Testing

Begin at 1080p, 60 FPS, matching the client settings. Increase bitrate and resolution gradually. Keep 120 FPS disabled for the baseline. Stop sharing before changing settings. Direct Wi-Fi Hotspot creates a hotspot on the host; use LAN when both devices already share an external hotspot.

Do not install replacement APKs during gameplay tests. Retain the signing key and package identity for updates preserving pairing/settings. Debug-signed builds are for development. No signing keys or captured device logs are published here.
