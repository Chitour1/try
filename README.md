# SWF to MP4 Android APK

Source for offline Android Flash conversion with Ruffle playback, MediaProjection screen capture and best-effort internal audio capture. This is a separate experiment branch, leaving the original main branch untouched.

Build is automatic on push. Open Actions -> Build SWF-to-MP4 Android APK, then download its SWF-to-MP4-APK artifact. The downloadable app-debug.apk is an installable (debug-signed) Android APK.

App flow: pick SWF; estimated duration is read from SWF frame count and FPS; tap Convert; approve audio / display permissions; the app previews and records the animation, then saves to Movies/SWFtoMP4. WebAssembly and JS Ruffle binaries are embedded at build time, and runtime is offline.

Limitations: does not guarantee precise rendering for every SWF due to ActionScript/player compatibility. Video captures the phone display instead of exporting at the exact SWF stage dimensions; recorded frames are real-time, not deterministic timeline render. Audio works when Android's AudioPlaybackCapture permits capturing in-app playback; otherwise a silent movie is saved. Testing on hardware is required before treating rendering as bit-perfect.


### v1.3 manual SWF picker
Main button opens a manual directory browser (not automatic search). It lists real directory entries, prioritizing .swf files but showing all filename extensions. On Android 11+ it prompts for the optional all-files access permission because Android's default SAF recent screen can hide unknown SWF MIME types. Secondary standard Android picker and existing folder tree method remain as fallback. No online scanning or uploading. Important: an SWF must actually be saved in accessible phone storage to be shown; downloaded MP4 is not the original SWF.
