// Canonical Q8_0 rows retained from the validated loader stream, never GPU readback.
// This header is copied only into the pinned experimental runtime by prepare.py.
#pragma once
#include <cstring>
#include <stdexcept>
#ifndef FUTO_EMBEDDING_MIRROR
#define FUTO_EMBEDDING_MIRROR 1
#endif
#ifdef FUTO_MIRROR_TEST
static int futo_mirror_live_buffers = 0;
#endif
struct futo_embedding_mirror {
    ggml_context * context = nullptr;
    ggml_backend_buffer_t buffer = nullptr;
    ggml_tensor * tensor = nullptr;
    futo_embedding_mirror() = default;
    futo_embedding_mirror(const futo_embedding_mirror &) = delete;
    futo_embedding_mirror & operator=(const futo_embedding_mirror &) = delete;
    ~futo_embedding_mirror() {
        if (buffer) {
            ggml_backend_buffer_free(buffer);
#ifdef FUTO_MIRROR_TEST
            --futo_mirror_live_buffers;
#endif
        }
        if (context) ggml_free(context);
    }
    void capture(const ggml_tensor * original, int file_type, int n_dims, const void * bytes) {
        // Tensors carry no runtime name (create_tensor never sets tensor->name);
        // selection already requires tensor == model.d_te at the call site.
        // Keep the exact static shape/type/layout gate.
        if (tensor || file_type != GGML_TYPE_Q8_0 || n_dims != 2 ||
            original->type != GGML_TYPE_Q8_0 ||
            original->ne[0] != 768 || original->ne[1] != 51864 ||
            original->ne[2] != 1 || original->ne[3] != 1 ||
            !ggml_is_contiguous(original) || original->nb[1] != 816 ||
            ggml_nbytes(original) != 42321024) {
            throw std::runtime_error("Unsupported canonical embedding mirror metadata");
        }
        context = ggml_init({ggml_tensor_overhead(), nullptr, true});
        if (!context) throw std::runtime_error("Embedding mirror context allocation failed");
        tensor = ggml_dup_tensor(context, original);
        ggml_set_name(tensor, "decoder.token_embedding.weight.cpu_mirror");
        buffer = ggml_backend_alloc_ctx_tensors_from_buft(context, ggml_backend_cpu_buffer_type());
        if (!buffer) throw std::runtime_error("Embedding mirror allocation failed");
#ifdef FUTO_MIRROR_TEST
        ++futo_mirror_live_buffers;
#endif
        if (memcmp(tensor->nb, original->nb, sizeof(tensor->nb))) {
            throw std::runtime_error("Embedding mirror stride mismatch");
        }
        ggml_backend_buffer_set_usage(buffer, GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
        ggml_backend_tensor_set(tensor, bytes, 0, ggml_nbytes(original));
    }
};
