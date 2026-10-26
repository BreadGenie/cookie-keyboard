# Debug-only comparison harness (app module)

This source set is compiled **only into debug builds**. It contains the
manual CPU/OpenCL/Fast comparison activity and model-mapping helpers used to
reproduce the public measurements in [`docs/benchmark.md`](../../docs/benchmark.md).

It is not part of the stable/release product path and does not run
automatically. The fixtures it reads live in
`voiceinput-shared/src/debug/assets/comparison/` and are public-domain JFK
speech plus generated silence. No personal recording is included.

The normal keyboard build does **not** enable OpenCL or Fast. Those controls
remain opt-in and gated at runtime.
