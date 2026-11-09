# Echo 1 ManBand Pro (v0.1 foundation)
Push to GitHub -> Actions tab -> "Build APK" -> download the `Echo1ManBandPro-debug-apk` artifact.
Or open in Android Studio and run. Min SDK 24.

Working: .emb projects + auto project folders, atomic autosave with backup rotation, 7-row 16-step sequencer,
tap row name to import a 16-bit WAV (copied into the project's Samples/), play/stop, BPM, relative sample refs.
Piano roll: tap to add/delete notes (Len = note length in 16ths, Oct -/+ scrolls pitch, Bars = loop length 1-18). Notes play using the Melody row sample, pitch-shifted (C4 = original pitch).
Timeline: 4+ tracks (add more with + Track), tap a track name to import a WAV region at the green marker (tap the ruler to move it), drag regions to move (snaps to beats), Split at the marker, Del, M/S per track. Mixer: volume + pan faders per channel (Sequencer, Piano Roll, each track), mute/solo, master fader with stereo meter. Not yet built: recording, effects, native audio engine.
Recording: in Timeline tap R on a track header (turns red) to arm it, put the green marker where you want, press Rec. It plays the project while recording (use headphones). Press Stop Rec to finish; the take is saved in Samples/ as a region.
Metronome: Metro On/Off clicks on every beat (accent on bar 1). Count-in (0/1/2 bars) plays clicks before recording starts when the transport is stopped.
Undo/Redo (50 steps) covers sequencer steps, piano roll notes, row samples, tracks and regions. Timeline: drag a region's left/right edge to trim (selected regions show white handles); drag the middle to move.
UI: restyled to the dark mockup (top bar, tab row, sequencer with S/M per row, bottom transport with swing + LED meter). Settings gear = metronome / count-in. Loop switch + bars. Sampler, Sample Editor and Projects screens not built yet.
Projects tab: New, Open, Rename, Duplicate, Recover (latest autosave), Delete (with confirmation). The last opened project reopens on launch.
Sample Editor tab: choose a sample, drag on the waveform to select, Trim/Cut/Reverse/Normalize/Fade/Gain (knob), Play, Undo, Save (to Edited Samples/, original untouched) then assign to a sequencer row.
UI theme: Logic-style grey. Timeline is the home tab: coloured track headers (number, name, M/S/R, volume slider, pan knob) and regions with waveforms.
