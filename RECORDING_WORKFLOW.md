# 88-Key Recording Workflow

This app includes a lightweight workflow inspired by Entropy Piano Tuner:

1. Open the top-left menu.
2. Tap `Record 88 Keys`.
3. Tap `Start / Resume Recording`.
4. Play the requested key, starting from `A0`.
5. Play the key until 3 stable samples are collected.
6. The app saves the median frequency for that key and advances automatically.
7. Continue until all 88 keys are recorded.
8. Tap `Compute Tuning`.

Current implementation notes:

- The workflow stores the median of 3 stable detected frequency samples and cent offsets for each MIDI key from `A0` to `C8`.
- The recording dialog shows quality states such as `Too quiet`, `Unstable pitch`, `Wrong key`, `Sample 1/3`, and `Recorded OK`.
- After all 88 keys are recorded, `Compute Tuning` enables a lightweight stretch curve: lower bass targets are slightly flat and upper treble targets are slightly sharp relative to A=440 equal temperament.
- During 88-key recording, target-key changes are silent so the phone does not record its own preview sound.
- A future version can replace the lightweight stretch curve with full spectral/inharmonicity analysis like Entropy Piano Tuner.
- Unstable noise and short transients are ignored by the same pitch-stability filter used by the main tuner.
