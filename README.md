# Echo 1 ManBand Pro (v0.1 foundation)
Push to GitHub -> Actions tab -> "Build APK" -> download the `Echo1ManBandPro-debug-apk` artifact.
Or open in Android Studio and run. Min SDK 24.

Working: .emb projects + auto project folders, atomic autosave with backup rotation, 7-row 16-step sequencer,
tap row name to import a 16-bit WAV (copied into the project's Samples/), play/stop, BPM, relative sample refs.
Piano roll: tap to add/delete notes (Len = note length in 16ths, Oct -/+ scrolls pitch, Bars = loop length 1-18). Notes play using the Melody row sample, pitch-shifted (C4 = original pitch).
Not yet built: sampler pads, sample editor, project manager screen, native audio engine.
