"""Pinned production source: functional metadata fix, FUTO token callback and content-free total timings."""
import argparse, pathlib, hashlib, tarfile, subprocess, re, sys, os
sys.dont_write_bytecode = True
base=pathlib.Path(__file__).resolve().parent
_parser=argparse.ArgumentParser(description='Prepare the pinned whisper.cpp source used by the optional OpenCL engine.')
_parser.add_argument('output',help='build output directory (contains generated/ and shim.cpp)')
_args=_parser.parse_args()
out=pathlib.Path(_args.output); out.mkdir(parents=True,exist_ok=True)
source=out/'whisper'
# The pinned archive is fetched by fetch-deps.sh; WHISPER_ARCHIVE overrides the location.
_default_archive=base/'third_party/whisper-231638a.tar.gz'
if not _default_archive.is_file():
 _default_archive=base/'.deps/whisper-231638a.tar.gz'
archive=pathlib.Path(os.environ.get('WHISPER_ARCHIVE',str(_default_archive)))
assert hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()=='f69100f71ce6c815624994362cd8a8eaaa26d678e5ce518ed35900d4b679e721', f'pinned whisper archive hash mismatch: {archive}'
if source.exists():
 assert (out/'product-cleanup-v46').exists(), 'Use a fresh OPENCL_BUILD_ROOT for the cleaned product sources'
if not source.exists():
 with tarfile.open(archive) as t:
  root=t.getnames()[0].split('/')[0]; t.extractall(out,filter='data')
 (out/root).rename(source)
 subprocess.run(['patch','--batch','-p1','-i',str(base/'patches/opencl-buffer-metadata.patch')],cwd=source,check=True)
 (out/'integration-source').write_text('231638a0717b7822784297e5980965ffcec05810\n')
assert (out/'integration-source').exists()
p=source/'src/whisper.cpp'; s=p.read_text()
def replace(old,new):
 global s
 if new in s:return
 assert s.count(old)==1,(old[:80],s.count(old)); s=s.replace(old,new)
replace('#include "whisper.h"','#include "whisper.h"\nextern "C" void futo_token_partial(whisper_context *, whisper_state *, const whisper_token_data *, size_t);')
replace('    // ACCEL backends\n','    if (params.use_gpu && !backend_gpu) { throw std::runtime_error("requested OpenCL GPU unavailable"); }\n    // ACCEL backends\n')
# Match the stock sampling point and candidate choice, including its single-thread JNI constraint.
replace('\n                    const int n_threads = std::min(params.n_threads, n_decoders_cur);','\n                    const int n_threads = 1; // FUTO token callback must stay on the JNI caller thread')
replace('                                        decoder.sequence.sum_logprobs_all += decoder.sequence.tokens.back().plog;', '                                        decoder.sequence.sum_logprobs_all += decoder.sequence.tokens.back().plog;\n                                        futo_token_partial(ctx, state, decoder.sequence.tokens.data(), decoder.sequence.tokens.size());')
old='''                                            bc_per_dec[j].back().sequence.sum_logprobs_all += token.plog;
                                        }
                                    } break;'''
new='''                                            bc_per_dec[j].back().sequence.sum_logprobs_all += token.plog;
                                        }
                                        if (j == 0 && !bc_per_dec[j].empty()) {
                                            const auto & tokens = bc_per_dec[j].back().sequence.tokens;
                                            futo_token_partial(ctx, state, tokens.data(), tokens.size());
                                        }
                                    } break;'''
replace(old,new)
# Product timings expose actual encode wall and cheap token counts only.
# Worker-summed sampling/postprocessing totals were experiment instrumentation.
accessor = """extern "C" void futo_session_timings(struct whisper_context * ctx, double * v) {
    auto * state = ctx->state;
    v[0] = state->t_encode_us * 1e-3;
    v[9] = 0;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) v[9] += whisper_full_n_tokens(ctx, i);
}
"""
replace('void whisper_reset_timings(struct whisper_context * ctx) {', accessor + '\nvoid whisper_reset_timings(struct whisper_context * ctx) {')
p.write_text(s)
# Focused flash-attention GPU attribution (v12): count successful FA graph dispatches
# executed by the OpenCL backend. Incremented only after ggml_cl_flash_attn returns,
# so a supports_op decline (CPU fallback), a lazy compile rejection (error/abort) or a
# flash-off graph (no FA nodes) all record 0. Requested-vs-actual is therefore decided
# by this counter, never by the creation-time flag or generic enqueue deltas.
g=source/'ggml/src/ggml-opencl/ggml-opencl.cpp'; t=g.read_text()
def greplace(old,new):
 global t
 if new in t:return
 assert t.count(old)==1,(old[:80],t.count(old)); t=t.replace(old,new)
greplace('#include <CL/cl.h>','#include <CL/cl.h>\n#include <atomic>\n// futo_fa_attribution_v12: successful OpenCL FA node executions in this process.\nstatic std::atomic<unsigned long long> futo_fa_nodes{0};\nextern "C" unsigned long long futo_fa_executed(){return futo_fa_nodes.load();}')
greplace('            ggml_cl_flash_attn(backend, tensor->src[0], tensor->src[1], tensor);\n            return true;','            ggml_cl_flash_attn(backend, tensor->src[0], tensor->src[1], tensor);\n            ++futo_fa_nodes; // returned => FA ran on GPU; abort/throw skips this line\n            return true;')
g.write_text(t)
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent/'readback'))
import importlib.util
_spec=importlib.util.spec_from_file_location('readback_instrument',pathlib.Path(__file__).resolve().parent/'readback/instrument.py')
_mod=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_mod);_mod.instrument(source)
_spec=importlib.util.spec_from_file_location('mirror_prepare',pathlib.Path(__file__).resolve().parent/'readback/mirror/prepare.py')
_mod=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_mod);_mod.apply(source)
texts='\n'.join(p.read_text() for p in (source/'ggml/src/ggml-opencl').glob('*.cpp'))
names=set(re.findall(r'\b(cl[A-Z]\w*)\s*\(',texts))
headers=(base/'third_party/CL/cl.h').read_text(); wrappers=[]
for name in sorted(names):
 m=re.search(r'extern CL_API_ENTRY\s+([\w *]+?)\s+CL_API_CALL\s+'+name+r'\((.*?)\)\s*CL_API_SUFFIX',headers,re.S); assert m,name
 ret,args=m.groups(); params=[];depth=0;start=0
 for i,c in enumerate(args):
  if c=='(':depth+=1
  elif c==')':depth-=1
  elif c==',' and depth==0:params.append(args[start:i].strip());start=i+1
 params.append(args[start:].strip()); forwarded=[]
 for arg in params:
  if arg=='void':continue
  cb=re.search(r'\*\s*(\w+)\s*\)',arg);forwarded.append(cb.group(1) if cb else re.findall(r'\w+',arg)[-1])
 post='if(result==CL_SUCCESS) ++successful_enqueues;' if name=='clEnqueueNDRangeKernel' else ''
 pre=''
 if name in ['clEnqueueReadBuffer','clWaitForEvents']:
  pre='auto started=transport_us();'
  kind='read' if name=='clEnqueueReadBuffer' else 'wait'
  post+=f'if(result==CL_SUCCESS) {{ {kind}_us+=transport_us()-started; ++{kind}_calls; }}'
  if name=='clEnqueueReadBuffer': post+='if(result==CL_SUCCESS) read_bytes+=size;'
 wrappers.append(f'extern "C" {ret} CL_API_CALL {name}({args}) {{ static auto fn=reinterpret_cast<decltype(&{name})>(resolve("{name}")); {pre} auto result=fn('+','.join(forwarded)+f'); {post} return result; }}')
(out/'shim.cpp').write_text('''#define CL_TARGET_OPENCL_VERSION 300
#define CL_USE_DEPRECATED_OPENCL_1_2_APIS
#include <CL/cl.h>
#include <dlfcn.h>
#include <cstdlib>
#include <atomic>
#include <chrono>
static double transport_us() {return std::chrono::duration<double,std::micro>(std::chrono::steady_clock::now().time_since_epoch()).count();}
static std::atomic<unsigned long long> read_us{0}, read_bytes{0}, read_calls{0}, wait_us{0}, wait_calls{0};
extern "C" void futo_transport_totals(double *v) {v[0]=read_us.load()*1e-3;v[1]=read_bytes.load();v[2]=read_calls.load();v[3]=wait_us.load()*1e-3;v[4]=wait_calls.load();}
// futo_readback_attribution_v8: counts at the production get_tensor boundary.
static std::atomic<unsigned long long> tensor_reads[10]{};
extern "C" void futo_tensor_read(unsigned long long bytes, int kind) {
 tensor_reads[0]+=bytes; ++tensor_reads[1];
 if(kind==1) {tensor_reads[2]+=bytes; ++tensor_reads[3];}
 if(kind==2) ++tensor_reads[4];
 if(bytes>8388608 && kind==0) {tensor_reads[5]+=bytes; ++tensor_reads[6];}
 ++tensor_reads[bytes<=4096?7:bytes<=1048576?8:9];
}
extern "C" void futo_tensor_read_totals(double *v) {for(int i=0;i<10;++i)v[i]=tensor_reads[i].load();}
static std::atomic<unsigned long long> successful_enqueues{0};
extern "C" unsigned long long futo_enqueues(){return successful_enqueues.load();}
static void *resolve(const char *name) {
 static void *handle=dlopen("libOpenCL.so",RTLD_NOW|RTLD_LOCAL);
 if(!handle) abort();
 void *p=dlsym(handle,name);if(!p) abort();return p;
}
'''+ '\n'.join(wrappers)+'\n')
# Verify the complete optional driver API before entering GGML; missing symbols
# are a managed startup failure instead of an abort in a forwarding wrapper.
# --- v13 encoder-only flash-attention split (DEBUG A/B only) ---
# Upstream carries one flag (flash_attn) for 3 FA sites + cross-KV layout +
# decoder KV padding. Encoder-only FA needs the encoder self-attention graph on
# FA while decoder self/cross graphs stay ordinary. The cross-KV STORE layout
# (whisper_build_graph_cross) MUST match the decoder cross READ layout (both
# follow the decoder flag): a padded flash store with an unpadded ordinary read
# would misalign every layer. KV caches stay GGML_PAD-sized in both modes
# (allocation is flag-independent), so both layouts fit. Everyday off
# (false,any) and global-on (true,true) graphs are bit-identical to v12.
import re as _re13
_hp=source/'include/whisper.h'; _hs=_hp.read_text()
def _hreplace(old,new):
  global _hs
  if new in _hs:return
  assert _hs.count(old)==1,(old[:80],_hs.count(old)); _hs=_hs.replace(old,new)
_hreplace('''        bool  use_gpu;
        bool  flash_attn;
        int   gpu_device;  // CUDA device''','''        bool  use_gpu;
        bool  flash_attn;
        // v13 encoder-only split: decoder self/cross FA (cross-KV store+read
        // layout, decoder KV padding and DTW gate follow this). Encoder FA
        // follows flash_attn alone. Defaults preserve upstream global behavior.
        bool  flash_attn_decoder;
        int   gpu_device;  // CUDA device''')
_hp.write_text(_hs)
_p2=source/'src/whisper.cpp'; _s2=_p2.read_text()
def _vreplace(old,new):
  global _s2
  if new in _s2:return
  assert _s2.count(old)==1,(old[:80],_s2.count(old)); _s2=_s2.replace(old,new)
# Graph-build attribution counters (production seam, backend-independent): FA
# nodes selected in encoder vs decoder (self+cross) graphs. Off=0/0 by
# construction; encoder-only proves decoder graphs hold 0 FA nodes even where
# the GPU counter cannot run (host CPU). <atomic> is already included above.
_vreplace('#include <atomic>',
 '#include <atomic>\n// futo_fa_build_v13: FA graph nodes selected per section (encoder vs decoder incl cross).\nstatic std::atomic<unsigned long long> futo_fa_enc_built{0};\nstatic std::atomic<unsigned long long> futo_fa_dec_built{0};\nextern "C" unsigned long long futo_fa_enc_built_get(){return futo_fa_enc_built.load();}\nextern "C" unsigned long long futo_fa_dec_built_get(){return futo_fa_dec_built.load();}')
_vreplace('/*.flash_attn           =*/ true,',
 '/*.flash_attn           =*/ true,\n        /*.flash_attn_decoder   =*/ true, // v13 encoder-only split default (preserves upstream global behavior)')
_vreplace('if (!wctx.params.flash_attn || !wctx.params.use_gpu) {',
 'if (!wctx.params.flash_attn || !wctx.params.flash_attn_decoder || !wctx.params.use_gpu) { // v13: decoder KV padding follows decoder flag')
_vreplace('''                cur = ggml_flash_attn_ext(ctx0, Q, K, V, nullptr, KQscale, 0.0f, 0.0f);

                cur = ggml_reshape_2d(ctx0, cur, n_state, n_ctx);''',
 '''                cur = ggml_flash_attn_ext(ctx0, Q, K, V, nullptr, KQscale, 0.0f, 0.0f);
                ++futo_fa_enc_built; // v13 graph proof: encoder FA node selected

                cur = ggml_reshape_2d(ctx0, cur, n_state, n_ctx);''')
_vreplace('''        if (wctx.params.flash_attn) {
            k = ggml_view_1d(ctx0, wstate.kv_cross.k, n_state*n_ctx,''',
 '''        if (wctx.params.flash_attn && wctx.params.flash_attn_decoder) { // v13: cross store follows decoder
            k = ggml_view_1d(ctx0, wstate.kv_cross.k, n_state*n_ctx,''')
_vreplace('hp.n_text_head, wctx.params.flash_attn)) {',
 'hp.n_text_head, wctx.params.flash_attn && wctx.params.flash_attn_decoder)) { // v13: cross proj follows decoder')
_vreplace('''                if (wctx.params.flash_attn) {
                    k = ggml_view_1d(ctx0, kv_self.k, n_tokens*n_state,''',
 '''                if (wctx.params.flash_attn && wctx.params.flash_attn_decoder) { // v13: decoder self store follows decoder
                    k = ggml_view_1d(ctx0, kv_self.k, n_tokens*n_state,''')
_vreplace('''            if (wctx.params.flash_attn) {
                struct ggml_tensor * V =
                    ggml_view_3d(ctx0, kv_self.v,''',
 '''            if (wctx.params.flash_attn && wctx.params.flash_attn_decoder) { // v13: decoder self FA follows decoder
                struct ggml_tensor * V =
                    ggml_view_3d(ctx0, kv_self.v,''')
_vreplace('''                cur = ggml_flash_attn_ext(ctx0, Q, K, V, KQ_mask_f16, 1.0f, 0.0f, 0.0f);''',
 '''                cur = ggml_flash_attn_ext(ctx0, Q, K, V, KQ_mask_f16, 1.0f, 0.0f, 0.0f);
                ++futo_fa_dec_built; // v13 graph proof: decoder self FA node selected''')
_vreplace('''            if (wctx.params.flash_attn) {
                struct ggml_tensor * Kcross =''',
 '''            if (wctx.params.flash_attn && wctx.params.flash_attn_decoder) { // v13: decoder cross FA follows decoder
                struct ggml_tensor * Kcross =''')
_vreplace('''                cur = ggml_flash_attn_ext(ctx0, Q, Kcross, Vcross, nullptr, KQscale, 0.0f, 0.0f);''',
 '''                cur = ggml_flash_attn_ext(ctx0, Q, Kcross, Vcross, nullptr, KQscale, 0.0f, 0.0f);
                ++futo_fa_dec_built; // v13 graph proof: decoder cross FA node selected''')
_vreplace('if (params.flash_attn && params.dtw_token_timestamps) {',
 'if (params.flash_attn && params.flash_attn_decoder && params.dtw_token_timestamps) { // v13: DTW reads decoder cross attention')
_vreplace('''    WHISPER_LOG_INFO("%s: flash attn = %d\\n", __func__, params.flash_attn);''',
 '''    WHISPER_LOG_INFO("%s: flash attn = %d\\n", __func__, params.flash_attn);
    WHISPER_LOG_INFO("%s: flash attn decoder = %d\\n", __func__, params.flash_attn_decoder); // v13 split''')
_p2.write_text(_s2)
print('Prepared v13 encoder-only flash-attention split; cross-KV layout follows decoder flag')
p=out/'shim.cpp'
s=p.read_text()
s += '\nextern "C" bool futo_opencl_available() { void *h=dlopen("libOpenCL.so",RTLD_NOW|RTLD_LOCAL); if(!h)return false; bool ok=true;\n'
for name in sorted(names): s += f'if(!dlsym(h,"{name}")) ok=false;\n'
s += 'dlclose(h);return ok;}\n'
p.write_text(s)
print('Prepared production source; token compatibility and content-free session timing totals; no profiling or IO capture')
# --- v24-final persistent OpenCL kernel-cache diagnostics (measurement only) ---
# The pin already ships an on-disk program-binary cache
# (ggml/src/ggml-opencl/cl-program-cache.{h,cpp}, keyed on source/options/
# device/driver/platform, wired to GGML_OPENCL_KERNEL_CACHE_DIR). What is
# unknown is whether entries HIT across phone process restarts. This block
# adds always-on, process-wide, content-free counters -- no new cache, no
# behavior change, no transcript/audio/model bytes in any log:
#   totals[4] = [hits, misses, saves, rejects] via futo_cl_cache_totals()
#   times[2]  = [load_ms, compile_ms] via futo_cl_cache_time_totals()
#   src[2]    = [src_builds, src_wrapped] via futo_cl_src_totals()
#   srct[2]   = [src_ms, src_wrapped_ms] via futo_cl_src_time_totals()
# compile_ms is the TOTAL source-build wall across every
# build_program_from_source_ex call (cached-wrapper path AND direct lazy
# flash-attention calls). Direct/uncached builds = src_builds - src_wrapped;
# direct wall = src_ms - src_wrapped_ms. The single timer lives in _ex
# itself, so no source compile can bypass it; the wrapper only classifies
# its one call via the thread-local flag (no double count: the old
# wrapper-only timer is gone).
# Return-path contract (audited against cl-program-cache.cpp try_load, which
# has exactly 6 `return nullptr` paths plus the hit `return p`, and
# try_save, which is void with save-skips):
#   hits    = try_load returned a usable binary (source compile skipped).
#   misses  = try_load found nothing loadable (read_all failed: absent or
#             unreadable file). Exactly one source build follows per miss
#             when the wrapper path runs.
#   rejects = try_load found a file but refused it: bad magic/size (<16 B
#             or magic mismatch), format-version mismatch, binary creation
#             failure, or driver build refusal. Exactly one source build
#             follows per reject, same as a miss. All four present-but-
#             unusable paths increment rejects; no try_load failure path is
#             uncounted (the disabled-cache/null-source returns exit before
#             any counting by design: the cache was not consulted).
#   saves   = try_save persisted a fresh binary after a source compile.
#             try_save early exits (disabled cache, device/binary queries,
#             zero binary size, binary fetch failure, write failure) are
#             save-SKIPS, not misses or rejects: the source build already
#             happened; only the persist did not. Binary-size validation
#             (sizes[0]==0) lives on this save path by design.
#   load_ms    = cumulative binary load+build wall on the hit path only.
#   compile_ms = cumulative source-build wall across ALL _ex calls. Source
#             builds per span therefore equal src_builds exactly -- even
#             when the cache is disabled (misses+rejects stay 0 while
#             src_builds still counts every wrapper build).
#   src_builds  = every build_program_from_source_ex call (wrapper + direct).
#   src_wrapped = the wrapper's classified calls only.
# Live Fast dictation runs on the encoder-flash engine
# (NativeOpenClEncoderFlash, flashEnc=true via the OpenClEncoderFlashClient /
# FastDictationRunner seam), whose first encode lazily compiles
# flash-attention variant programs via DIRECT build_program_from_source_ex
# calls (ggml-opencl.cpp ensure_fa_* functions, fatal=false with retry
# queue); those calls bypass the binary cache entirely. The v24-initial
# comment claiming live dictation uses flash-off engines was wrong; the
# v24-initial wrapped-path-only timer therefore under-reported exactly the
# target first-encode cost. v24-final covers it.
# Direct _ex call sites in the pinned tree (all fatal=false): prepass
# (prog_pre_f16), prefill-512 (+k_img variant), the FA_MQ_ONLY/F32/F16/Q8/Q4
# variant matrix incl. MQ_GQA=8 and NSG_SPLIT=2 specializations, and the
# DK=64/128/256/512 ensure paths. build_program_from_binary (Adreno
# prebuilt kernels) builds from a binary, never from source, and is
# correctly excluded from src_builds.
# try_save runs synchronously on the calling thread inside
# build_program_from_source after the source build; no claim is made about
# it being off any critical path. Its wall feeds an unexported debug
# counter only, never load_ms/compile_ms/src_ms.
# Cost: a handful of atomic increments + two clock reads per distinct program
# per process (lazy per-program builds only). The existing
# GGML_OPENCL_KERNEL_CACHE_DEBUG=1 stderr trace is untouched.
import pathlib as _pl24
_c24 = source/'ggml/src/ggml-opencl/cl-program-cache.cpp'
_t24 = _c24.read_text()
def _c24replace(old, new):
    global _t24
    if new in _t24:
        return
    assert _t24.count(old) == 1, (old[:80], _t24.count(old))
    _t24 = _t24.replace(old, new)
_c24replace('#include <vector>', '#include <vector>\n#include <atomic>\n#include <chrono>\n// futo_clcache_v24: always-on process-wide program-cache diagnostics.\n// Semantics describe actual calls/success, never filesystem existence.\n// Miss vs reject vs save-skip contract: see the v24 block in\n// tools/opencl/prepare.py. try_save runs synchronously on the caller thread;\n// its wall below is a debug counter only, with no critical-path claim.\n// v24-final: compile_ms is the TOTAL source-build wall across every\n// build_program_from_source_ex call (wrapper + direct lazy-FA calls);\n// src_builds/src_wrapped prove compilation even with the cache disabled.\nstatic std::atomic<unsigned long long> futo_cl_hits{0};\nstatic std::atomic<unsigned long long> futo_cl_misses{0};\nstatic std::atomic<unsigned long long> futo_cl_saves{0};\nstatic std::atomic<unsigned long long> futo_cl_rejects{0};\nstatic std::atomic<unsigned long long> futo_cl_load_us{0};\nstatic std::atomic<unsigned long long> futo_cl_save_us{0};\nstatic std::atomic<unsigned long long> futo_cl_src_builds{0};\nstatic std::atomic<unsigned long long> futo_cl_src_wrapped{0};\nstatic std::atomic<unsigned long long> futo_cl_src_us{0};\nstatic std::atomic<unsigned long long> futo_cl_src_wrapped_us{0};\n// v24-final: every build_program_from_source_ex call notes here (all source\n// compiles: cached-wrapper path AND direct lazy-FA path). wrapped=true only\n// for the wrapper call, classified via the ggml-opencl.cpp thread-local.\n// Direct builds = builds - wrapped; direct wall = src_us - wrapped_us.\nextern void futo_cl_src_note(unsigned long long us, bool wrapped) {\n    ++futo_cl_src_builds; futo_cl_src_us += us;\n    if (wrapped) { ++futo_cl_src_wrapped; futo_cl_src_wrapped_us += us; }\n}\nextern "C" void futo_cl_cache_totals(unsigned long long * v) {\n    v[0] = futo_cl_hits.load(); v[1] = futo_cl_misses.load();\n    v[2] = futo_cl_saves.load(); v[3] = futo_cl_rejects.load();\n}\nextern "C" void futo_cl_cache_time_totals(double * v) {\n    v[0] = (double) futo_cl_load_us.load() * 1e-3;\n    v[1] = (double) futo_cl_src_us.load() * 1e-3;\n}\nextern "C" void futo_cl_src_totals(unsigned long long * v) {\n    v[0] = futo_cl_src_builds.load(); v[1] = futo_cl_src_wrapped.load();\n}\nextern "C" void futo_cl_src_time_totals(double * v) {\n    v[0] = (double) futo_cl_src_us.load() * 1e-3;\n    v[1] = (double) futo_cl_src_wrapped_us.load() * 1e-3;\n}')
_c24replace('    if (state.dir.empty() || !source) { return nullptr; }\n\n    const std::string key  = compute_key(state.key_suffix, source, compile_opts);',
 '    if (state.dir.empty() || !source) { return nullptr; }\n    const auto futo_load_start = std::chrono::steady_clock::now(); // v24: hit-path timing only\n\n    const std::string key  = compute_key(state.key_suffix, source, compile_opts);')
_c24replace('        ++g_cache_misses;', '        ++g_cache_misses;\n        ++futo_cl_misses; // v24: nothing loadable, source compile follows')
_c24replace('    if (file.size() < 16 || std::memcmp(file.data(), MAGIC, 8) != 0) { return nullptr; }',
 '    if (file.size() < 16 || std::memcmp(file.data(), MAGIC, 8) != 0) { ++futo_cl_rejects; return nullptr; } // v24: present but unusable')
_c24replace('    if (fmt != CL_PROGRAM_CACHE_FORMAT_VERSION) { return nullptr; }',
 '    if (fmt != CL_PROGRAM_CACHE_FORMAT_VERSION) { ++futo_cl_rejects; return nullptr; } // v24: present but unusable')
_c24replace('    if (err != CL_SUCCESS || bin_err != CL_SUCCESS || p == nullptr) {\n        if (p) { clReleaseProgram(p); }\n        return nullptr;\n    }',
 '    if (err != CL_SUCCESS || bin_err != CL_SUCCESS || p == nullptr) {\n        if (p) { clReleaseProgram(p); }\n        ++futo_cl_rejects; // v24: driver refused the cached binary\n        return nullptr;\n    }')
_c24replace('    if (err != CL_SUCCESS) {\n        clReleaseProgram(p);\n        return nullptr;\n    }\n    ++g_cache_hits;',
 '    if (err != CL_SUCCESS) {\n        clReleaseProgram(p);\n        ++futo_cl_rejects; // v24: driver refused the cached binary on build\n        return nullptr;\n    }\n    ++g_cache_hits;\n    ++futo_cl_hits; // v24: usable binary, source compile skipped\n    futo_cl_load_us += (unsigned long long) std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - futo_load_start).count();')
_c24replace('    if (!write_atomic(path, file.data(), file.size())) {',
 '    const auto futo_save_start = std::chrono::steady_clock::now(); // v24: synchronous persist wall, debug counter only (no critical-path claim)\n    if (!write_atomic(path, file.data(), file.size())) {')
_c24replace('        ++g_cache_saves;', '        ++g_cache_saves;\n        ++futo_cl_saves; // v24: fresh binary persisted after source compile\n        futo_cl_save_us += (unsigned long long) std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - futo_save_start).count();')
_c24.write_text(_t24)
_g24 = source/'ggml/src/ggml-opencl/ggml-opencl.cpp'
_u24 = _g24.read_text()
def _g24replace(old, new):
    global _u24
    if new in _u24:
        return
    assert _u24.count(old) == 1, (old[:80], _u24.count(old))
    _u24 = _u24.replace(old, new)
_g24replace('#include "cl-program-cache.h"', '#include "cl-program-cache.h"\n// v24-final kernel-cache diagnostic: the single source-build timer lives in\n// build_program_from_source_ex below, so EVERY source compile is timed --\n// the cached-wrapper path and the direct lazy flash-attention _ex calls\n// alike (live Fast dictation runs encoder-flash, whose first encode takes\n// the direct path). The wrapper marks its one call via the thread-local\n// flag; all other _ex callers count as direct/uncached. No double count:\n// the wall is noted once, in _ex.\nextern void futo_cl_src_note(unsigned long long us, bool wrapped);\nstatic thread_local bool futo_cl_src_wrapped_call = false;\nstruct FutoClSrcSpan {\n    int64_t start_us; bool wrapped;\n    explicit FutoClSrcSpan(bool w) : start_us(ggml_time_us()), wrapped(w) {}\n    ~FutoClSrcSpan() { futo_cl_src_note((unsigned long long) (ggml_time_us() - start_us), wrapped); }\n};')
_g24replace('static cl_program build_program_from_source_ex(cl_context ctx, cl_device_id dev, const char* program_buffer, const std::string &compile_opts, bool fatal, const char *tag = nullptr, cl_command_queue retry_queue = nullptr) {\n    if (tag) { GGML_LOG_INFO("ggml_opencl: compiling %s\\n", tag); }',
 'static cl_program build_program_from_source_ex(cl_context ctx, cl_device_id dev, const char* program_buffer, const std::string &compile_opts, bool fatal, const char *tag = nullptr, cl_command_queue retry_queue = nullptr) {\n    const FutoClSrcSpan futo_src_span(futo_cl_src_wrapped_call); // v24-final: time ALL source builds incl direct lazy-FA calls\n    if (tag) { GGML_LOG_INFO("ggml_opencl: compiling %s\\n", tag); }')
_g24replace('    cl_program p = build_program_from_source_ex(ctx, dev, program_buffer, compile_opts, /*fatal=*/true);',
 '    futo_cl_src_wrapped_call = true; // v24-final: classify the single _ex timer below as wrapped (cached path)\n    cl_program p = build_program_from_source_ex(ctx, dev, program_buffer, compile_opts, /*fatal=*/true);\n    futo_cl_src_wrapped_call = false;')
_g24.write_text(_u24)
print('Prepared v24-final kernel-cache diagnostics; counters only, no behavior change')
# --- lazy-fa-cache: route lazy nonfatal FA builds through the existing robust cache ---
# v24 phone evidence: the wrapped (fatal) path hits persistently, but lazy
# flash-attention variants call build_program_from_source_ex directly with
# fatal=false, bypassing cl-program-cache entirely (6 direct builds,
# ~1.4 s per cold process). This block adds a NONFATAL cached-build entry
# point and swaps every lazy fatal=false site onto it. The fatal=true
# wrapper, all compile options/sources, the cache key, ownership, and the
# null-on-failure fallback contract are unchanged. Requires the v24 block
# above (thread-local wrapped classification); no v24 counter semantics
# change (post-fix, direct counts only truly cache-bypassing builds).
_h25 = source/'ggml/src/ggml-opencl/cl-program-cache.h'
_ht25 = _h25.read_text()
_h25_old = '''void cl_program_cache_try_save(
    const cl_program_cache_state & state,
    cl_program                     program,
    cl_device_id                   device,
    const char *                   source,
    const std::string &            compile_opts);'''
_h25_new = _h25_old + '''

// Lazy flash-attention builds use non-fatal compilation (fatal=false): a
// compiler rejection must fall back to the non-FA path, never abort. This
// routes those builds through the same robust on-disk cache: the key covers
// the exact source bytes, compile options, device/driver/platform identity
// and format version, so a hit rebuilds the identical program. A null build
// result is never saved and propagates to the caller, whose existing
// fallback applies unchanged.
typedef cl_program (*cl_program_build_fn)(
    cl_context         context,
    cl_device_id       device,
    const char *       source,
    const std::string & compile_opts,
    const char *       tag,
    void *             userdata);

cl_program cl_program_cache_get_or_build_nonfatal(
    const cl_program_cache_state & state,
    cl_context                     context,
    cl_device_id                   device,
    const char *                   source,
    const std::string &            compile_opts,
    const char *                   tag,
    cl_program_build_fn            build,
    void *                         userdata);'''
if 'cl_program_cache_get_or_build_nonfatal' not in _ht25:
    assert _ht25.count(_h25_old) == 1, ('lazy-fa-cache header anchor', _ht25.count(_h25_old))
    _ht25 = _ht25.replace(_h25_old, _h25_new)
    _h25.write_text(_ht25)
_c25 = source/'ggml/src/ggml-opencl/cl-program-cache.cpp'
_ct25 = _c25.read_text()
assert 'futo_cl_src_wrapped_call' in _ct25 or 'futo_cl_saves' in _ct25, 'lazy-fa-cache requires the v24 block above'
_c25_old = '''        futo_cl_save_us += (unsigned long long) std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - futo_save_start).count();
        cache_debug_line("SAVE", key, source, compile_opts);
    }
}'''
_c25_new = _c25_old + '''

// Lazy-FA nonfatal cached build (see header contract). Deliberately
// timer-free: source-build timing stays inside build_program_from_source_ex
// in ggml-opencl.cpp (v24 span), which the caller classifies as cached-path.
// Absent/corrupt entries fall through to build(); a null build result is
// never saved.
cl_program cl_program_cache_get_or_build_nonfatal(
    const cl_program_cache_state & state,
    cl_context                     context,
    cl_device_id                   device,
    const char *                   source,
    const std::string &            compile_opts,
    const char *                   tag,
    cl_program_build_fn            build,
    void *                         userdata) {
    if (source) {
        cl_program p_cached = cl_program_cache_try_load(state, context, device, source, compile_opts);
        if (p_cached != nullptr) {
            return p_cached;
        }
    }
    cl_program p = build(context, device, source, compile_opts, tag, userdata);
    if (p != nullptr) {
        cl_program_cache_try_save(state, p, device, source, compile_opts);
    }
    return p;
}'''
if 'cl_program_cache_get_or_build_nonfatal' not in _ct25:
    assert _ct25.count(_c25_old) == 1, ('lazy-fa-cache try_save anchor', _ct25.count(_c25_old))
    _ct25 = _ct25.replace(_c25_old, _c25_new)
    _c25.write_text(_ct25)
_g25 = source/'ggml/src/ggml-opencl/ggml-opencl.cpp'
_ut25 = _g25.read_text()
assert 'static thread_local bool futo_cl_src_wrapped_call' in _ut25, 'lazy-fa-cache requires the v24 thread-local flag'
if 'build_program_from_source_cached_nonfatal(' in _ut25:
    assert _ut25.count('build_program_from_source_cached_nonfatal(') == 15, _ut25.count('build_program_from_source_cached_nonfatal(')
    assert _ut25.count('/*fatal=*/false') == 1, _ut25.count('/*fatal=*/false')
    assert _ut25.count('build_program_from_source_ex(') == 3, _ut25.count('build_program_from_source_ex(')
else:
    _ut25, _n25_head = re.subn(
        r'build_program_from_source_ex\(\n( *)backend_ctx->context, backend_ctx->device,',
        r'build_program_from_source_cached_nonfatal(\n\1backend_ctx,', _ut25)
    assert _n25_head == 14, ('lazy-fa-cache call-site heads', _n25_head)
    _ut25, _n25_tail = re.subn(
        r',\s*/\*fatal=\*/false,\s*(.*?),\s*backend_ctx->queue\)',
        r', \1)', _ut25, flags=re.S)
    assert _n25_tail == 14, ('lazy-fa-cache call-site tails', _n25_tail)
    _g25_old = '''    // Best-effort save of the freshly-built binary (no-op if cache disabled).
    if (p != nullptr) {
        cl_program_cache_try_save(backend_ctx->program_cache, p, dev, program_buffer, compile_opts);
    }
    return p;
}

static cl_program build_program_from_binary(cl_context ctx, cl_device_id dev, const char* program_buffer, const std::string &compile_opts, size_t bin_size = 0) {'''
    _g25_new = '''    // Best-effort save of the freshly-built binary (no-op if cache disabled).
    if (p != nullptr) {
        cl_program_cache_try_save(backend_ctx->program_cache, p, dev, program_buffer, compile_opts);
    }
    return p;
}

// Lazy flash-attention nonfatal builds routed through the existing on-disk
// cache (lazy-fa-cache). Forwards the exact source/options/queue/tag to the
// unchanged fatal=false _ex path, so numerics and retry behavior are
// identical; only persistence changes. The v24 source timer classifies this
// call as cached-path (wrapped) via the thread-local flag, so post-fix
// src_direct counts only truly cache-bypassing builds (expected 0 on FA
// spans). A null result propagates to the caller's existing fallback and is
// never saved (enforced in get_or_build_nonfatal). The wrapped flag is set
// via an RAII guard so a C++ exception inside _ex (only std::bad_alloc from
// string/file paths can throw here; OpenCL C calls return codes and
// fatal=false never calls exit) cannot leak a stale true classification to
// later builds. No numerical change: guard touches only the diagnostic flag.
struct FutoClSrcWrappedGuard {
    bool saved;
    explicit FutoClSrcWrappedGuard(bool v) : saved(futo_cl_src_wrapped_call) { futo_cl_src_wrapped_call = v; }
    ~FutoClSrcWrappedGuard() { futo_cl_src_wrapped_call = saved; }
};

static cl_program futo_fa_build_from_source_nonfatal(
    cl_context ctx, cl_device_id dev, const char * program_buffer,
    const std::string & compile_opts, const char * tag, void * userdata) {
    auto * backend_ctx = (ggml_backend_opencl_context *) userdata;
    const FutoClSrcWrappedGuard futo_wrapped_guard(true); // classify the _ex timer below as cached-path
    cl_program p = build_program_from_source_ex(
        ctx, dev, program_buffer, compile_opts, /*fatal=*/false, tag, backend_ctx->queue);
    return p;
}

static cl_program build_program_from_source_cached_nonfatal(
    ggml_backend_opencl_context * backend_ctx, const char * program_buffer,
    const std::string & compile_opts, const char * tag = nullptr) {
    return cl_program_cache_get_or_build_nonfatal(
        backend_ctx->program_cache, backend_ctx->context, backend_ctx->device,
        program_buffer, compile_opts, tag, futo_fa_build_from_source_nonfatal, backend_ctx);
}

static cl_program build_program_from_binary(cl_context ctx, cl_device_id dev, const char* program_buffer, const std::string &compile_opts, size_t bin_size = 0) {'''
    assert _ut25.count(_g25_old) == 1, ('lazy-fa-cache helper anchor', _ut25.count(_g25_old))
    _ut25 = _ut25.replace(_g25_old, _g25_new)
    assert _ut25.count('build_program_from_source_cached_nonfatal(') == 15, _ut25.count('build_program_from_source_cached_nonfatal(')
    assert _ut25.count('/*fatal=*/false') == 1, _ut25.count('/*fatal=*/false')
    assert _ut25.count('build_program_from_source_ex(') == 3, _ut25.count('build_program_from_source_ex(')
    _g25.write_text(_ut25)
print('Prepared lazy-fa-cache nonfatal cached FA builds (14 sites)')

(out/'product-cleanup-v46').write_text('product sources without experiment injections\n')
