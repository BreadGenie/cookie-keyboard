# Benchmark notes

Selected public measurements for the experimental OpenCL/Fast paths. This is a
compact record, not a WER corpus, p95 study, energy measurement, or
stop-to-editor acceptance result.

## Provenance

- Device: OnePlus CPH2649, Android 16.
- Model: user-supplied FUTO English-244 (`small_en_acft_q8_0.bin`),
  264,477,561 bytes, SHA-256
  `58fbe949992dafed917590d58bc12ca577b08b9957f0b3e0d7ee71b64bed3aa8`.
- Runtime pin: whisper.cpp `231638a0717b7822784297e5980965ffcec05810`.
- Build lineage: `0.0.43-comparison-recovery-unstable` (code 43), same native
  libraries as the cleaned v37 build; later capability-gate changes (v44–v46)
  do not rebuild the native library.
- Source: a completed 27-sample phone report supplied by the user. The compact
  numbers below are a transcription of selected fields, not a byte-for-byte
  raw export. Reconstructed public transcript hashes matched all supplied
  per-mode hashes; no original-export hash is claimed.

## Selected medians

Warm adapter API wall, three counterbalanced rounds per workload. Preparation,
full-shape warmups, and model hash preflight are outside the measured request.

| Workload | CPU beam5 | OpenCL beam5 | Fast guarded beam1 / one beam5 retry | CPU / OpenCL | CPU / Fast |
|---|---:|---:|---:|---:|---:|
| 12 s public speech | 3283.850 ms | 1390.727 ms | 406.891 ms | 2.36× | 8.07× |
| 60 s repeated public speech | 36146.901 ms | 13174.888 ms | 3431.035 ms | 2.74× | 10.54× |
| 10 s digital silence | 2056.680 ms | 519.024 ms | 27.193 ms | 3.96× | 75.63× |

Time reductions versus CPU on speech: 57.65% / 63.55% for normal OpenCL and
87.61% / 90.51% for Fast (12 s / 60 s). The silence row is not a GPU compute
result: Fast detects exact digital zero and skips inference.

## Interpretation limits

- These are **warm adapter walls**, not editor stop-to-commit, p95, or
  first-use latency. First preparation was 1539.583 ms for normal OpenCL and
  1039.225 ms for Fast; first warmups were 1461.297 ms and 415.713 ms.
- The 12 s and 60 s fixtures reuse the public JFK inaugural excerpt; the 60 s
  fixture repeats it with silence. They are not independent utterances.
- Fast is a whole-configuration comparison (decode policy, encoder-only flash
  attention, resident verification), not a pure GPU speedup.
- CPU uses the stock FUTO runtime; normal OpenCL uses the modern pinned
  runtime. Backend and runtime revision both vary.
- All speech samples were `FAST_ACCEPT` with retry count 0 and resident hits.
  The low-confidence retry path was not measured here.
- No held-out WER, natural-speech accuracy, thermal, energy, peak-memory, or
  p95 result is established. The 60 s Fast samples ranged 3.288–4.596 s.
- Cleanup after the benchmark reported an unconfirmed quiescence
  (`cleanup_state=unconfirmed`, 0.877969 ms). Request walls completed; cleanup
  correctness is not established by this run.

## Fixtures

16 kHz mono signed little-endian PCM16.

| Fixture | Seconds | SHA-256 |
|---|---:|---|
| battery-12s | 12 | `d529c88c24f64dce6e59b42360341f5833f4ee3a6e57e894a13cd24b46753811` |
| battery-60s | 60 | `95673726ef1517091442ae7cc98177c98e75bda61726f0d1ac86d1ff1d0d77b2` |
| battery-silence-10s | 10 | `ac2878215ad33205d1732578c89f25ededaad1b676911c540825bcf4baa42d9b` |

The debug source set retains these small public fixtures and their notice. They
are not a benchmark download and contain no personal recording.

## Reproducing

The debug comparison harness lives under `voiceinput-shared/src/debug/` and
`src/debug/`; it uses the same public fixtures. A run needs a user-supplied
model file and an installed build. Host tests and APK audits validate source
and packaging, not phone speed. The repository does not ship the model, APK, or
raw phone logs.
