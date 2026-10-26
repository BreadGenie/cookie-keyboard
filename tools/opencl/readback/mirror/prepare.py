"""Narrow pinned-runtime patch; stock Whisper sources are never edited."""
import pathlib

def apply(source):
    p = source / 'src/whisper.cpp'
    s, tail = p.read_text().split('static bool whisper_encode_external', 1)
    header = pathlib.Path(__file__).resolve().parents[4] / 'native/opencl/embedding-mirror.h'
    (p.parent / 'futo-embedding-mirror.h').write_bytes(header.read_bytes())
    if '// futo_embedding_mirror_v9' in s:
        return
    def replace(old, new):
        nonlocal s
        assert s.count(old) == 1, (old[:100], s.count(old))
        s = s.replace(old, new)
    replace('struct whisper_model {', '#include "futo-embedding-mirror.h"\n// futo_embedding_mirror_v9\nstruct whisper_model {\n    futo_embedding_mirror embedding_mirror;')
    # Production selection follows use_gpu. Host-only differential tests explicitly opt in.
    replace('            if (ggml_backend_buffer_is_host(tensor->buffer)) {', '''            bool mirror_embedding = FUTO_EMBEDDING_MIRROR && wctx.params.use_gpu && tensor == model.d_te;
#ifdef FUTO_MIRROR_TEST
            mirror_embedding = FUTO_EMBEDDING_MIRROR && tensor == model.d_te;
#endif
            if (ggml_backend_buffer_is_host(tensor->buffer)) {''')
    replace('                BYTESWAP_TENSOR(tensor);\n            } else {', '''                BYTESWAP_TENSOR(tensor);
                if (mirror_embedding) model.embedding_mirror.capture(tensor, ttype, n_dims, tensor->data);
            } else {''')
    replace('                ggml_backend_tensor_set(tensor, read_buf.data(), 0, ggml_nbytes(tensor));', '''                if (mirror_embedding) model.embedding_mirror.capture(tensor, ttype, n_dims, read_buf.data());
                ggml_backend_tensor_set(tensor, read_buf.data(), 0, ggml_nbytes(tensor));''')
    s += 'static bool whisper_encode_external' + tail
    replace('ggml_get_rows(ctx0, model.d_te, embd)', 'ggml_get_rows(ctx0, model.embedding_mirror.tensor ? model.embedding_mirror.tensor : model.d_te, embd)')
    # Per-context state, not global counters or a second model read/hash bypass.
    s += '''
extern "C" void futo_embedding_mirror_stats(whisper_context * ctx, double * v) {
    const auto * t = ctx ? ctx->model.embedding_mirror.tensor : nullptr;
    v[0] = t ? 1 : 0;
    v[1] = t ? ggml_nbytes(t) : 0;
}
'''
    p.write_text(s)
