#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define COOKIE_API __attribute__((visibility("default")))
// One generation and one model load per OS process. All methods except cancel
// must run on ONE serial worker. Concurrent callers fail immediately (no queue).
// Caller owns all pointers through return; no buffers are retained except model.
// Returned JSON is private recognition data, NEVER an editor delivery callback.
// Positive return = JSON bytes excluding NUL; negative = terminal failure.
// Caller must recheck its own generation at delivery even after success.
COOKIE_API int cookie_whistle_load(uint64_t generation, const unsigned char* model, size_t size);
COOKIE_API int cookie_whistle_feed(uint64_t generation, const float* pcm, int samples, char* out, int capacity);
COOKIE_API int cookie_whistle_finish(uint64_t generation, char* out, int capacity);
// Only invalidates results. Does not interrupt native work or reclaim resources.
COOKIE_API void cookie_whistle_cancel(uint64_t generation);
#ifdef __cplusplus
}
#endif
