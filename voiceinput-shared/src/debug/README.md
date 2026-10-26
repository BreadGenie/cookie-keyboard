# Debug-only comparison fixtures and code

This source set is compiled **only into debug builds**. It contains the
public-fixture comparison runner and its small PCM assets.

- `assets/comparison/battery-12s.pcm16`, `battery-60s.pcm16`, and
  `battery-silence-10s.pcm16` are public JFK speech (repeated/padded) and
  digital silence. Provenance and SHA-256 pins are in `NOTICE.txt` and
  [`docs/benchmark.md`](../../../docs/benchmark.md).
- The comparison code is not part of the release path and is not started by
  normal dictation.
- No model file is bundled. A user-supplied model is verified against its
  pinned SHA-256 before use.

The OpenCL/Fast production controls remain opt-in and capability-gated; see
the repository README.
