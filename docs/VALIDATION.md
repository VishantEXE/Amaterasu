# Validation and limitations — 0.6.7

## Measured device behavior

Live picture and device audio have been exercised with stock Moonlight XR on Quest 3 and stock Moonlight on a Lenovo Android tablet, using an Odin2 Portal host. Local Wi-Fi and mobile hotspots work without internet access. Lower-resolution/bitrate gameplay was reported smooth and playable. These are observed configurations, not guarantees for every client or Android device.

FSR 1080p to 1440p and 4K have been exercised. On the Odin system hotspot at 5 GHz, one 4K HEVC sample sustained 38–39 sent FPS over 26 seconds with no new video queue drops. An earlier 2.4 GHz run showed repeated send stalls and video queue resets. The runs were observed during gameplay and do not isolate the effect of the band. Android also shut down the system hotspot on a timeout during one session, which disconnected the client.

Android recorded LOW_MEMORY termination during heavy-game HEVC sessions at 1440p and 4K. The v0.6.7 4K retest still ended this way after the new buffer trim ran. The smaller 4K FSR scratch texture saves about 3.75 MiB and produced identical pixels in the Odin image probe. That saving applies to the intermediate texture. Total process savings and gameplay performance can differ.

Experimental AV1 requires a hardware AV1 Main 8-bit encoder and an AV1-capable client. The Odin2 Portal exposed AV1 decoding only. AV1 frame handling passed an encoded-sample decode check. Live AV1 playback still needs hardware validation.

Automatic dimming and normal brightness restoration after stopping were verified on the host. Device-side audio mute is not reliable on every path; set host media volume to zero as a fallback. Apps can prohibit Android playback capture.

## Build checks

The build invokes Java checks for settings, frame/audio pacing, bounded buffers, protocol fragmentation, congestion decisions, session epochs and saved-state ownership. These verify selected logic, not sustained gameplay performance. Layout-renderer images are interface previews, not gameplay screenshots.

## Testing

Begin at 1080p and 60 FPS, matching the client settings. Increase bitrate and resolution gradually. Keep 120 FPS disabled for the baseline. Stop sharing before changing settings. In Direct Wi-Fi Hotspot mode, enable the hotspot in the host device’s Android settings. Use 5 GHz if available and disable automatic hotspot shutdown for long sessions. Select LAN when both devices share an external hotspot.

Do not install replacement APKs during gameplay tests. Retain the signing key and package identity for updates preserving pairing/settings. Debug-signed builds are for development. No signing keys or captured device logs are published here.
