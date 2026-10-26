#include <jni.h>
#include <chrono>
static double nowMs() { return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <memory>
#include <stdexcept>
#include <string>
#include <map>
#include <mutex>
#include <vector>
#include <unistd.h>
#include <limits>
#include "whisper.h"

#if DIAG_GPU
extern "C" void futo_cl_cache_totals(unsigned long long *); // [hits,misses,saves,rejects], defined in generated cl-program-cache.cpp
extern "C" void futo_cl_cache_time_totals(double *);        // [load_ms,compile_ms(total source wall)]
extern "C" void futo_cl_src_totals(unsigned long long *);   // [src_builds,src_wrapped], defined in generated cl-program-cache.cpp
extern "C" void futo_cl_src_time_totals(double *);          // [src_ms,src_wrapped_ms]
#endif
// v24-final kernel-cache diagnostic: per-call deltas of the process-wide counters.
// Each top-level JNI entry owns one span covering its full native work:
// prepare() spans the (possibly cold) context load including kernel builds;
// run()/runGuardedBeam*() spans the implicit prepare() plus decode. The
// service reads the just-filled deltas after each call -- the prepare-call
// delta rides prewarm rows, prepare+run summed ride recognize rows -- so the
// per-call spans are disjoint and never double counted. Entries start as NaN
// (unknown); only successful calls commit measured deltas, so cancel and
// exception paths (via the RAII destructor) render explicit unknown instead
// of stale or success-zero values. A committed zero is a measured zero (the
// span observed no counter movement). On CPU builds (DIAG_GPU=0) there is no
// OpenCL cache: deltas stay unknown and no generated-cache symbol is
// referenced (link-safe; the host CPU build proves it).
// The four getter pairs are snapshotted back-to-back on the service's serial
// worker, so no concurrent inference can interleave; residual skew is bounded
// by nanoseconds of counter reads on an otherwise idle engine.
// v24-final layout (9 doubles): [0..3]=hits/misses/saves/rejects,
// [4]=load_ms, [5]=compile_ms (TOTAL source wall across every
// build_program_from_source_ex call: wrapped + direct lazy-FA),
// [6]=src_builds (every _ex call: compilation proof even with the cache
// disabled), [7]=src_direct (direct/uncached _ex calls: wrapped = [6]-[7]),
// [8]=direct_ms (direct wall: wrapped wall = [5]-[8]). First 6 positions keep
// their v24 order; [5] semantics corrected from wrapped-only to total (the
// v24-initial coverage gap). Never reuses metrics[] indices.
static void clCacheMarkUnknown(double * out) {
    for (int i = 0; i < 9; ++i) out[i] = std::numeric_limits<double>::quiet_NaN();
}
struct ClCacheSpan {
    double * out;
    bool committed = false;
#if DIAG_GPU
    unsigned long long before[4]{};
    double tBefore[2]{};
    unsigned long long sBefore[2]{};
    double stBefore[2]{};
#endif
    explicit ClCacheSpan(double * o) : out(o) {
        clCacheMarkUnknown(out);
#if DIAG_GPU
        futo_cl_cache_totals(before);
        futo_cl_cache_time_totals(tBefore);
        futo_cl_src_totals(sBefore);
        futo_cl_src_time_totals(stBefore);
#endif
    }
    void commit() {
#if DIAG_GPU
        if (committed) return;
        unsigned long long after[4]{};
        double tAfter[2]{};
        unsigned long long sAfter[2]{};
        double stAfter[2]{};
        futo_cl_cache_totals(after);
        futo_cl_cache_time_totals(tAfter);
        futo_cl_src_totals(sAfter);
        futo_cl_src_time_totals(stAfter);
        for (int i = 0; i < 4; ++i) out[i] = (double)(after[i] - before[i]);
        out[4] = tAfter[0] - tBefore[0];
        out[5] = tAfter[1] - tBefore[1];
        out[6] = (double)(sAfter[0] - sBefore[0]);
        out[7] = (double)(sAfter[0] - sBefore[0]) - (double)(sAfter[1] - sBefore[1]);
        out[8] = (stAfter[0] - stBefore[0]) - (stAfter[1] - stBefore[1]);
        committed = true;
#endif
    }
    ~ClCacheSpan() { if (!committed) clCacheMarkUnknown(out); }
};

// Context access stays on its service's serial worker. Cancellation only touches an atomic.
// Each engine pins its flash-attention modes at creation; they can never change,
// so an off context can never be reused for a flash request and vice versa.
// v13 encoder-only split: flashEnc selects encoder self-attention FA graphs,
// flashDec selects decoder self/cross FA graphs AND the cross-KV store layout
// (which must match the decoder cross read layout). Modes: off (false,false),
// global flash-on (true,true, v12 behavior), encoder-only (true,false).
// Live off-mode dictation uses (false,false) engines via NativeOpenCl; live
// FAST dictation uses the encoder-only (true,false) engine via
// NativeOpenClEncoderFlash (FastDictationRunner -> OpenClEncoderFlashClient
// runGuardedBeamVerified seam), whose first encode lazily compiles
// flash-attention variant programs through direct
// build_program_from_source_ex calls -- the path v24-final measures.
struct Engine {
    std::atomic<bool> cancelled{false};
    const bool flashEnc;
    const bool flashDec;
    explicit Engine(bool flashEncoder, bool flashDecoder) : flashEnc(flashEncoder), flashDec(flashDecoder) {
        clCacheMarkUnknown(clCacheDelta);
        clCacheMarkUnknown(clCachePrepareDelta);
    }
    double metrics[40]{};
    // v24-final kernel-cache diagnostic: per-call deltas [hits, misses,
    // saves, rejects, load_ms, compile_ms(total), src_builds, src_direct,
    // direct_ms] of the process-wide OpenCL counters (see the layout note
    // above ClCacheSpan). clCacheDelta is filled by run()/runGuardedBeam*()
    // spans (implicit prepare + decode); clCachePrepareDelta by prepare()
    // spans (cold context load included). Entries start as NaN (unknown);
    // only successful calls commit measured values. NaN renders as explicit
    // unknown, never as success-zero. Never reuses metrics[] indices.
    double clCacheDelta[9]{};
    double clCachePrepareDelta[9]{};
    std::unique_ptr<whisper_context,decltype(&whisper_free)> ctx{nullptr,whisper_free};
};
static std::mutex enginesMutex;
static std::map<jlong,std::shared_ptr<Engine>> engines;
static jlong nextEngine=0;
static std::shared_ptr<Engine> engine(JNIEnv *env,jobject self) {
    auto cls=env->GetObjectClass(self);
    auto id=env->GetLongField(self,env->GetFieldID(cls,"nativeHandle","J"));
    env->DeleteLocalRef(cls);
    std::lock_guard<std::mutex> lock(enginesMutex);
    auto found=engines.find(id);
    if(found==engines.end()) throw std::runtime_error("Closed OpenCL engine");
    return found->second;
}
struct Call { JNIEnv *env; jobject self; jmethodID partial; Engine *engine; std::map<int,std::string> segments; };
static thread_local Call *active = nullptr;
static jstring utf8(JNIEnv *e, const std::string &s) {
    auto bytes=e->NewByteArray(s.size());
    e->SetByteArrayRegion(bytes,0,s.size(),reinterpret_cast<const jbyte *>(s.data()));
    auto cls=e->FindClass("java/lang/String");
    auto charset=e->NewStringUTF("UTF-8");
    auto result=static_cast<jstring>(e->NewObject(cls,e->GetMethodID(cls,"<init>","([BLjava/lang/String;)V"),bytes,charset));
    e->DeleteLocalRef(bytes); e->DeleteLocalRef(charset); e->DeleteLocalRef(cls);return result;
}
static std::string fromJava(JNIEnv *e,jstring s) {
    auto cls=e->FindClass("java/lang/String");auto charset=e->NewStringUTF("UTF-8");
    auto bytes=static_cast<jbyteArray>(e->CallObjectMethod(s,e->GetMethodID(cls,"getBytes","(Ljava/lang/String;)[B"),charset));
    std::string result(e->GetArrayLength(bytes),'\0');
    e->GetByteArrayRegion(bytes,0,result.size(),reinterpret_cast<jbyte *>(result.data()));
    e->DeleteLocalRef(bytes);e->DeleteLocalRef(charset);e->DeleteLocalRef(cls);return result;
}
extern "C" void futo_token_partial(whisper_context *ctx, whisper_state *state, const whisper_token_data *tokens, size_t n) {
    if (!active || active->engine->cancelled.load()) return;
    const auto started=nowMs();
    std::string partial;
    for(size_t i=0;i<n;++i) {
        const auto id=tokens[i].id;
        if(id >= whisper_token_eot(ctx)) continue; // all control, language and timestamp tokens
        partial += whisper_token_to_str(ctx,id);
    }
    const int count=whisper_full_n_segments_from_state(state);
    active->segments[count]=partial;
    std::string output;
    for(int i=0;i<count;++i) if(active->segments.count(i)) output+=active->segments[i];
    output+=partial;
    auto text=utf8(active->env,output);
    active->env->CallVoidMethod(active->self,active->partial,text);
    active->env->DeleteLocalRef(text);
    active->engine->metrics[7]+=nowMs()-started;
    active->engine->metrics[8]++;
    if(active->env->ExceptionCheck()) active->engine->cancelled.store(true);
}
extern "C" JNIEXPORT jlong JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_create(JNIEnv *,jobject) {
    std::lock_guard<std::mutex> lock(enginesMutex);
    auto id=++nextEngine;engines[id]=std::make_shared<Engine>(false,false);return id;
}
extern "C" JNIEXPORT void JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_reset(JNIEnv *e,jobject self) { try { engine(e,self)->cancelled.store(false); } catch(...) {} }
extern "C" JNIEXPORT void JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_cancel(JNIEnv *e,jobject self) { try { engine(e,self)->cancelled.store(true); } catch(...) {} }
extern "C" JNIEXPORT void JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_close(JNIEnv *e,jobject self) {
    // Only the serial worker closes. The registry makes a simultaneous atomic cancel safe.
    try {
        auto state=engine(e,self);state->ctx.reset();
        std::lock_guard<std::mutex> lock(enginesMutex);
        for(auto it=engines.begin();it!=engines.end();++it) if(it->second==state) {engines.erase(it);break;}
    } catch(...) {}
}
extern "C" unsigned long long futo_enqueues();
#if DIAG_GPU
// Successful OpenCL flash-attention node executions in this process (patched
// ggml-opencl.cpp). CPU-only builds have no OpenCL backend, so FA GPU execution
// is definitionally zero there; the stub keeps the per-run delta logic identical.
extern "C" unsigned long long futo_fa_executed();
#else
static unsigned long long futo_fa_executed(){return 0;}
#endif
extern "C" unsigned long long futo_fa_enc_built_get();
extern "C" unsigned long long futo_fa_dec_built_get();
extern "C" bool futo_opencl_available();
extern "C" void futo_transport_totals(double *);
extern "C" void futo_tensor_read_totals(double *);
static bool prepare(Engine &state,int modelFd,const std::string &cachePath) {
    if(state.ctx) return true;
#if DIAG_GPU
    if(!futo_opencl_available()) throw std::runtime_error("Optional OpenCL driver unavailable");
#endif
    setenv("GGML_OPENCL_KERNEL_CACHE_DIR",cachePath.c_str(),1);
    whisper_log_set([](enum ggml_log_level,const char *,void *) {},nullptr);
    auto cp=whisper_context_default_params();cp.use_gpu=DIAG_GPU;cp.flash_attn=state.flashEnc;cp.flash_attn_decoder=state.flashDec;
    const std::string path="/proc/self/fd/"+std::to_string(modelFd);
    state.ctx.reset(whisper_init_from_file_with_params(path.c_str(),cp));
    if(!state.ctx) throw std::runtime_error("OpenCL model load failed");
    return false;
}
extern "C" JNIEXPORT jboolean JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_prepare(JNIEnv *e,jobject self,jint fd,jstring cache) {
    try {
        auto state = engine(e,self);
        ClCacheSpan span(state->clCachePrepareDelta); // v24-final: prepare-call span incl cold load
        bool reused = prepare(*state,fd,fromJava(e,cache));
        span.commit();
        return reused;
    }
    catch(...) {e->ThrowNew(e->FindClass("java/io/IOException"),"Experimental OpenCL model load failed");return false;}
}
extern "C" void futo_session_timings(whisper_context *,double *);
extern "C" void futo_embedding_mirror_stats(whisper_context *,double *);
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_timings(JNIEnv *e,jobject self) {
    auto state=engine(e,self);
    futo_session_timings(state->ctx.get(),state->metrics);
    futo_embedding_mirror_stats(state->ctx.get(),state->metrics+33);
    auto result=e->NewDoubleArray(38);e->SetDoubleArrayRegion(result,0,38,state->metrics);return result;
}
// v24-final kernel-cache diagnostic: run-call [hits,misses,saves,rejects,load_ms,
// compile_ms(total),src_builds,src_direct,direct_ms] deltas (implicit prepare
// + decode). NaN entries mean unknown (cancel/exception/CPU).
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_clCacheStats(JNIEnv *e,jobject self) {
    auto state=engine(e,self);
    auto result=e->NewDoubleArray(9);e->SetDoubleArrayRegion(result,0,9,state->clCacheDelta);return result;
}
// v24-final: prepare-call deltas (cold context load included). NaN entries mean unknown.
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_clCachePrepareStats(JNIEnv *e,jobject self) {
    auto state=engine(e,self);
    auto result=e->NewDoubleArray(9);e->SetDoubleArrayRegion(result,0,9,state->clCachePrepareDelta);return result;
}
static jstring runOnEngine(JNIEnv *env,jobject self,std::shared_ptr<Engine> state,jint modelFd,jfloatArray audio,jstring prompt,jboolean suppress,jstring cache) {
    try {
        // Ordinary dictation uses beam search with width 5.
        ClCacheSpan clSpan(state->clCacheDelta); // v24-final: run-call span incl implicit prepare
        prepare(*state,modelFd,fromJava(env,cache));
        auto &ctx=state->ctx;
        if(state->cancelled.load()) return env->NewStringUTF("<>CANCELLED<> flag");
        auto cls=env->GetObjectClass(self);
        env->CallVoidMethod(self,env->GetMethodID(cls,"loaded","()V"));
        Call call{env,self,env->GetMethodID(cls,"partial","(Ljava/lang/String;)V"),state.get(),{}};
        env->DeleteLocalRef(cls);
        if(env->ExceptionCheck()) return nullptr;
        const int count=env->GetArrayLength(audio);
        std::vector<float> samples(count);
        env->GetFloatArrayRegion(audio,0,count,samples.data());
        if(env->ExceptionCheck()) return nullptr;
        auto p=whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
        long threads=sysconf(_SC_NPROCESSORS_ONLN);if(threads<2 || threads>16) threads=6;
        p.n_threads=threads;
        // v14: allocation (max(best_of, beam_size)) == active width
        // (n_decoders_cur = beam_size at t=0) == requested width. Leaving
        // best_of at 5 while narrowing beam_size would keep a 5-decoder
        // allocation behind fewer active decoders.
        p.beam_search.beam_size=5;p.greedy.best_of=5;
        p.max_tokens=256;p.audio_ctx=std::max(160,std::min(1500,int(std::ceil(count/320.0))+32));
        p.temperature_inc=0;p.suppress_blank=false;p.suppress_nst=suppress;
        p.no_timestamps=count<16000*25;p.language="en";
        p.print_progress=false;p.print_realtime=false;p.print_special=false;p.print_timestamps=false;
        auto glossary=fromJava(env,prompt);p.initial_prompt=glossary.c_str();
        p.no_context=true;
        p.abort_callback=[](void *data) {return static_cast<Engine *>(data)->cancelled.load();};
        p.abort_callback_user_data=state.get();
        active=&call;
#if DIAG_GPU
        auto enqueues=futo_enqueues();
        double transportBefore[5];futo_transport_totals(transportBefore);
        double readsBefore[10];futo_tensor_read_totals(readsBefore);
#endif
        // Per-run FA attribution: successful FA GPU executions during this run only.
        // Off engines build graphs with zero FA nodes, so this delta is 0 there by
        // construction; a flash-on run that falls back to CPU also records 0 here.
        auto faBefore=futo_fa_executed();
        auto encBuiltBefore=futo_fa_enc_built_get();
        auto decBuiltBefore=futo_fa_dec_built_get();
        std::fill(std::begin(state->metrics),std::end(state->metrics),0.0);
        state->metrics[14]=p.n_threads;state->metrics[15]=p.audio_ctx;state->metrics[16]=count;
        // v14: actual resolved beam in metrics (validated request consumed by
        // whisper_full on the single BEAM_SEARCH path), not a Kotlin label.
        state->metrics[38]=5;state->metrics[39]=double(p.beam_search.beam_size);
        whisper_reset_timings(ctx.get());
        auto fullStart=nowMs();
        int result=whisper_full(ctx.get(),p,samples.data(),count);
        state->metrics[17]=nowMs()-fullStart;
#if DIAG_GPU
        double transportAfter[5];futo_transport_totals(transportAfter);
        for(int i=0;i<5;++i)state->metrics[18+i]=transportAfter[i]-transportBefore[i];
        double readsAfter[10];futo_tensor_read_totals(readsAfter);
        for(int i=0;i<10;++i)state->metrics[23+i]=readsAfter[i]-readsBefore[i];
#endif
        // metrics[33..34] are filled by timings(); metrics[35] is the FA GPU
        // delta, metrics[36..37] the per-run FA graph nodes built (encoder vs
        // decoder incl cross). Off=0/0/0 by construction (no FA nodes); an
        // encoder-only run proves decoder graphs hold 0 FA nodes with
        // dec_built=0 even where the GPU counter cannot run.
        // A generic enqueue delta alone never proves FA ran: other matmuls enqueue
        // while FA nodes may sit on CPU. Only this counter decides FA execution.
        state->metrics[35]=double(futo_fa_executed()-faBefore);
        state->metrics[36]=double(futo_fa_enc_built_get()-encBuiltBefore);
        state->metrics[37]=double(futo_fa_dec_built_get()-decBuiltBefore);
        active=nullptr;
        if(env->ExceptionCheck()) return nullptr;
        if(state->cancelled.load()) return env->NewStringUTF("<>CANCELLED<> flag");
        if(result!=0) throw std::runtime_error("OpenCL decode failed");
#if DIAG_GPU
        if(futo_enqueues()==enqueues) throw std::runtime_error("OpenCL decode dispatched no GPU kernels");
#endif
        clSpan.commit(); // v24-final: only successful runs commit; cancel/exception stays unknown
        std::string output;int n=whisper_full_n_segments(ctx.get());
        for(int i=0;i<n;++i) {std::string segment=whisper_full_get_segment_text(ctx.get(),i);if(i==n-1 && segment==" you") continue;output+=segment;}
        return utf8(env,output);
    } catch(const std::exception &) {
        active=nullptr;
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),"Experimental OpenCL recognition failed");
        return nullptr;
    }
}
// Fast dictation: one beam-1 attempt on the single
// BEAM_SEARCH path with the exact runOnEngine parameters, then AT MOST one
// beam-5 retry on the same audio/model/prompt. Token-confidence signals come
// from the PUBLIC whisper segment/token API read immediately after each
// whisper_full, before any later decode on this engine: n_segments /
// n_tokens / token p averaged as mean(log(p)) over tokens with finite p > 0.
// CORRECTION vs the v15 comment that stood here (archived in
// integration/guarded-beam/v15/SPEC-v15.md): the pinned modern runtime
// 231638a DOES expose whisper_full_get_segment_no_speech_prob (public header
// generated/whisper/include/whisper.h; upstream include/whisper.h). The v15
// "no getter" claim came from grepping the wrong source — the old stock FUTO
// fork (third_party/futo-stock/whisper.h, which indeed has no getter and a
// TODO) — instead of the actually-compiled modern pin. v16 reads the real
// per-segment no-speech probability via that public getter, immediately
// post-decode, as a DIAGNOSTIC ONLY alongside avg_logprob; it is never a gate
// input and the v15 guard policy/threshold is unchanged. The ONLY skip path
// is byte-exact all-zero float PCM (deterministic digital no-signal): no
// amplitude/RMS gate, no stop-on-silence, and NO word/phrase filter of any
// kind. Threshold provenance: kGuardLogprobThold reuses whisper.cpp's
// compiled-in default logprob_thold (-1.0) as an EXPERIMENTAL debug-only
// diagnostic.
namespace {
struct GuardAttempt {
    std::string text;
    int segments = 0;
    long tokens = 0;     // tokens with finite p > 0
    long missingLp = 0;  // tokens with nonfinite/missing p
    double avgLogprob = std::numeric_limits<double>::quiet_NaN();
    // v16 per-segment no-speech diagnostics (DIAGNOSTIC ONLY, never gated):
    // one entry per STORED segment via the public
    // whisper_full_get_segment_no_speech_prob getter, read immediately
    // post-decode before this engine decodes again (the retry attempt has
    // its own struct, so fast signals are never overwritten). A stored
    // segment whose probability is nonfinite or outside [0,1] — including a
    // getter failure — counts as unknown, never as prob 0. Zero stored
    // segments (e.g. empty output, or the runtime's internal is_no_speech
    // segment drop at whisper.cpp whisper_full:7786 which stores nothing)
    // yields empty csv with NaN min/max: explicit unknown. The internal
    // decoder.sequence.avg_logprobs the runtime compares against
    // logprob_thold covers the full decoder sequence INCLUDING special
    // tokens; fast_avg_logprob above covers only stored segment tokens via
    // the public token API — same computation for fast and retry
    // (apples-to-apples) but NOT identical to the runtime's internal value.
    double nsMin = std::numeric_limits<double>::quiet_NaN();
    double nsMax = std::numeric_limits<double>::quiet_NaN();
    long nsValid = 0;    // stored segments with a valid [0,1] probability
    long nsUnknown = 0;  // stored segments without one (never prob 0)
    std::string nsCsv;   // per-segment probs in order, "NaN" when unknown
    double wallMs = 0.0;
    unsigned long long faExecuted = 0, encBuilt = 0, decBuilt = 0;

};
static constexpr float kGuardLogprobThold = -1.0f; // whisper default logprob_thold
static constexpr int kGuardFastBeam = 1;
static constexpr int kGuardRetryBeam = 5;
static std::string decodeOnce(Engine &eng, JNIEnv *env, jobject self,
    const std::vector<float> &samples, const std::string &glossary,
    bool suppress, int beamWidth, GuardAttempt &out) {
    auto &ctx = eng.ctx;
    auto cls = env->GetObjectClass(self);
    env->CallVoidMethod(self, env->GetMethodID(cls, "loaded", "()V"));
    Call call{env, self, env->GetMethodID(cls, "partial", "(Ljava/lang/String;)V"), &eng, {}};
    env->DeleteLocalRef(cls);
    if (env->ExceptionCheck()) throw std::runtime_error("callback missing");
    auto p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    long threads = sysconf(_SC_NPROCESSORS_ONLN); if (threads < 2 || threads > 16) threads = 6;
    p.n_threads = threads;
    // Same width==allocation rule as v14: allocation (max(best_of, beam))
    // == active width (beam_size at t=0) == requested width.
    p.beam_search.beam_size = beamWidth; p.greedy.best_of = beamWidth;
    p.max_tokens = 256;
    p.audio_ctx = std::max(160, std::min(1500, int(std::ceil(samples.size() / 320.0)) + 32));
    p.temperature_inc = 0; p.suppress_blank = false; p.suppress_nst = suppress;
    p.no_timestamps = (int)samples.size() < 16000 * 25; p.language = "en";
    p.print_progress = false; p.print_realtime = false; p.print_special = false; p.print_timestamps = false;
    p.initial_prompt = glossary.c_str();
    p.no_context = true;
    p.abort_callback = [](void *data) { return static_cast<Engine *>(data)->cancelled.load(); };
    p.abort_callback_user_data = &eng;
    eng.metrics[14] = p.n_threads; eng.metrics[15] = p.audio_ctx; eng.metrics[16] = (double)samples.size();
    active = &call;
#if DIAG_GPU
    auto enqueues = futo_enqueues();
#endif
    auto faBefore = futo_fa_executed();
    auto encBefore = futo_fa_enc_built_get();
    auto decBefore = futo_fa_dec_built_get();
    whisper_reset_timings(ctx.get());
    auto start = nowMs();
    int result = whisper_full(ctx.get(), p, samples.data(), (int)samples.size());
    out.wallMs = nowMs() - start;
    out.faExecuted = futo_fa_executed() - faBefore;
    out.encBuilt = futo_fa_enc_built_get() - encBefore;
    out.decBuilt = futo_fa_dec_built_get() - decBefore;
    active = nullptr;
    if (env->ExceptionCheck()) throw std::runtime_error("callback failed");
    if (eng.cancelled.load()) return std::string("<>CANCELLED<> flag");
    if (result != 0) throw std::runtime_error("OpenCL decode failed");
#if DIAG_GPU
    if (futo_enqueues() == enqueues) throw std::runtime_error("OpenCL decode dispatched no GPU kernels");
#endif
    // Text assembly identical to runOnEngine (incl. the pre-existing trailing
    // " you" trim, preserved unchanged and out of scope for this experiment).
    // avg_logprob covers ALL stored segment tokens (no special-token
    // exclusion): identical computation for fast and retry attempts, so the
    // comparison is apples-to-apples; stated limitation vs any
    // production-grade confidence (see GuardAttempt: NOT the runtime's
    // internal decoder.sequence.avg_logprobs, which includes special
    // tokens). Retry policy keys on counted TOKENS (tokens==0), not on
    // empty text: a stored-but-empty text with tokens>0 does NOT retry
    // (avoids pointless retries on model-legitimate blanks); a fully empty
    // fast output (0 tokens, e.g. the internal is_no_speech segment drop)
    // retries once with beam5, same as v15.
    std::string output;
    int n = whisper_full_n_segments(ctx.get());
    out.segments = n;
    double sum = 0.0;
    for (int i = 0; i < n; ++i) {
        std::string segment = whisper_full_get_segment_text(ctx.get(), i);
        if (i == n - 1 && segment == " you") continue;
        output += segment;
        int ntok = whisper_full_n_tokens(ctx.get(), i);
        for (int j = 0; j < ntok; ++j) {
            float pr = whisper_full_get_token_p(ctx.get(), i, j);
            if (!(pr > 0.0f) || !std::isfinite(pr)) { out.missingLp++; continue; }
            out.tokens++;
            sum += std::log((double)pr);
        }
    }
    if (out.tokens > 0) out.avgLogprob = sum / (double)out.tokens;
    // Per-segment no-speech probabilities: collected for EVERY stored
    // segment (including a " you"-trimmed one, whose probability is still a
    // genuine model signal) in the same immediate post-decode window.
    for (int i = 0; i < n; ++i) {
        float p = whisper_full_get_segment_no_speech_prob(ctx.get(), i);
        char b[32];
        if (std::isfinite(p) && p >= 0.0f && p <= 1.0f) {
            if (out.nsValid == 0) out.nsMin = out.nsMax = (double)p;
            else {
                if ((double)p < out.nsMin) out.nsMin = (double)p;
                if ((double)p > out.nsMax) out.nsMax = (double)p;
            }
            out.nsValid++;
            snprintf(b, sizeof(b), "%.6g", (double)p);
        } else {
            out.nsUnknown++;
            snprintf(b, sizeof(b), "%s", "NaN");
        }
        if (i > 0) out.nsCsv += ",";
        out.nsCsv += b;
    }
    out.text = output;
    return output;
}
static void formatLp(double v, char *b) {
    if (std::isfinite(v)) snprintf(b, 32, "%.6g", v);
    else snprintf(b, 32, "%s", "NaN");
}
static jstring runGuarded(JNIEnv *env, jobject self, std::shared_ptr<Engine> state,
    jint modelFd, jfloatArray audio, jstring prompt, jboolean suppress, jstring cache) {
    try {
        ClCacheSpan clSpan(state->clCacheDelta); // v24-final: run-call span incl implicit prepare
        prepare(*state, modelFd, fromJava(env, cache));
        auto &eng = *state;
        if (eng.cancelled.load()) return env->NewStringUTF("<>CANCELLED<> flag");
        const int count = env->GetArrayLength(audio);
        std::vector<float> samples(count);
        env->GetFloatArrayRegion(audio, 0, count, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        // Deterministic digital no-signal ONLY: byte-exact all-zero PCM.
        // Near-zero nonzero audio (quiet voice, room noise) never skips.
        bool exactZero = true;
        for (float v : samples) if (v != 0.0f) { exactZero = false; break; }
        std::fill(std::begin(eng.metrics), std::end(eng.metrics), 0.0);
        eng.metrics[38] = (double)kGuardFastBeam;
        auto totalStart = nowMs();
        std::string rationale;
        GuardAttempt fast, retry;
        bool didRetry = false;
        if (exactZero) {
            rationale = "NO_SIGNAL_DIGITAL_ZERO";
            // No decode ran: reset session timings so the timings() row shows
            // 0s instead of stale values from a previous run on this engine.
            // Authoritative skip evidence is fast_ms=0/retry=0 in the header.
            whisper_reset_timings(eng.ctx.get());
            eng.metrics[39] = 0.0;
        } else {
            auto glossary = fromJava(env, prompt);
#if DIAG_GPU
            double transportBefore[5]; futo_transport_totals(transportBefore);
            double readsBefore[10]; futo_tensor_read_totals(readsBefore);
#endif
            std::string f = decodeOnce(eng, env, self, samples, glossary, suppress, kGuardFastBeam, fast);
            if (f == "<>CANCELLED<> flag") return env->NewStringUTF("<>CANCELLED<> flag");
            // Per-run FA attribution for the fast attempt rides the shared
            // 40-wide metrics (fast attempt is the beam-1 evidence that
            // matters); the retry attempt's counters travel in the header.
            eng.metrics[35] = (double)fast.faExecuted;
            eng.metrics[36] = (double)fast.encBuilt;
            eng.metrics[37] = (double)fast.decBuilt;
            if (fast.tokens == 0 && fast.missingLp == 0) rationale = "RETRY_EMPTY_FAST";
            else if (fast.missingLp > 0) rationale = "RETRY_SIGNAL_MISSING";
            else if (!(fast.avgLogprob >= kGuardLogprobThold)) rationale = "RETRY_LOW_CONFIDENCE";
            else rationale = "FAST_ACCEPT";
            didRetry = rationale != "FAST_ACCEPT";
            if (didRetry) {
                std::string r = decodeOnce(eng, env, self, samples, glossary, suppress, kGuardRetryBeam, retry);
                if (r == "<>CANCELLED<> flag") return env->NewStringUTF("<>CANCELLED<> flag");
            }
            // metrics[39] = final accepted width (1 or 5): two decodes share
            // this call, so "resolved" here means the width whose output is
            // returned, documented in SPEC.md.
            eng.metrics[39] = (double)(didRetry ? kGuardRetryBeam : kGuardFastBeam);
#if DIAG_GPU
            double transportAfter[5]; futo_transport_totals(transportAfter);
            for (int i = 0; i < 5; ++i) eng.metrics[18 + i] = transportAfter[i] - transportBefore[i];
            double readsAfter[10]; futo_tensor_read_totals(readsAfter);
            for (int i = 0; i < 10; ++i) eng.metrics[23 + i] = readsAfter[i] - readsBefore[i];
#endif
        }
        double totalMs = nowMs() - totalStart;
        eng.metrics[17] = totalMs;
        clSpan.commit(); // v24-final: measured deltas (exact-zero skip commits prepare-only work); cancel paths above skip this
        char fastLp[32], retryLp[32], fastNsMin[32], fastNsMax[32], retryNsMin[32], retryNsMax[32];
        formatLp(fast.avgLogprob, fastLp);
        formatLp(retry.avgLogprob, retryLp);
        formatLp(fast.nsMin, fastNsMin);
        formatLp(fast.nsMax, fastNsMax);
        formatLp(retry.nsMin, retryNsMin);
        formatLp(retry.nsMax, retryNsMax);
        char header[4096];
        snprintf(header, sizeof(header),
            "guard=%s fast_beam=1 fast_chars=%zu fast_segments=%d fast_tokens=%ld fast_avg_logprob=%s fast_missing_lp=%ld fast_nospeech_min=%s fast_nospeech_max=%s fast_nospeech_valid=%ld fast_nospeech_unknown=%ld fast_nospeech=%s retry=%d retry_beam=%d retry_chars=%zu retry_segments=%d retry_tokens=%ld retry_avg_logprob=%s retry_missing_lp=%ld retry_nospeech_min=%s retry_nospeech_max=%s retry_nospeech_valid=%ld retry_nospeech_unknown=%ld retry_nospeech=%s fast_ms=%.3g retry_ms=%.3g total_ms=%.3g thold_logprob=-1 fast_fa_executed=%llu fast_enc_built=%llu fast_dec_built=%llu retry_fa_executed=%llu retry_enc_built=%llu retry_dec_built=%llu",
            rationale.c_str(),
            fast.text.size(), fast.segments, fast.tokens, fastLp, fast.missingLp,
            fastNsMin, fastNsMax, fast.nsValid, fast.nsUnknown, fast.nsCsv.c_str(),
            didRetry ? 1 : 0, didRetry ? kGuardRetryBeam : 0,
            retry.text.size(), retry.segments, retry.tokens, retryLp, retry.missingLp,
            retryNsMin, retryNsMax, retry.nsValid, retry.nsUnknown, retry.nsCsv.c_str(),
            fast.wallMs, retry.wallMs, totalMs,
            fast.faExecuted, fast.encBuilt, fast.decBuilt,
            retry.faExecuted, retry.encBuilt, retry.decBuilt);
        std::string finalText = exactZero ? std::string() : (didRetry ? retry.text : fast.text);
        std::string fastText = exactZero ? std::string() : fast.text;
        // Layout: header '\n' fastText (exactly fast_chars bytes) '\n'
        // finalText. Byte-exact framing survives embedded newlines and
        // multibyte UTF-8 in either text.
        std::string packed = std::string(header) + "\n" + fastText + "\n" + finalText;
        return utf8(env, packed);
    } catch (const std::exception &) {
        active = nullptr;
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), "Experimental OpenCL recognition failed");
        return nullptr;
    }
}
} // namespace
// Thin JNI entry points. Ordinary and encoder-only engines share
// every line of runOnEngine/prepare; only the creation-time flag differs, so the
// Ordinary OpenCL dictation entry.
extern "C" JNIEXPORT jstring JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenCl_run(JNIEnv *env,jobject self,jint modelFd,jfloatArray audio,jstring prompt,jboolean suppress,jstring cache) {
    try { return runOnEngine(env,self,engine(env,self),modelFd,audio,prompt,suppress,cache); }
    catch(const std::exception &) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),"Experimental OpenCL recognition failed");
        return nullptr;
    }
}


// v13 encoder-only engine (true,false): encoder FA graphs, ordinary decoder
// graphs with matching ordinary cross-KV layout. Guarded decoding uses this engine.
extern "C" JNIEXPORT jlong JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_create(JNIEnv *,jobject) {
    std::lock_guard<std::mutex> lock(enginesMutex);
    auto id=++nextEngine;engines[id]=std::make_shared<Engine>(true,false);return id;
}
extern "C" JNIEXPORT void JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_reset(JNIEnv *e,jobject self) { try { engine(e,self)->cancelled.store(false); } catch(...) {} }
extern "C" JNIEXPORT void JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_cancel(JNIEnv *e,jobject self) { try { engine(e,self)->cancelled.store(true); } catch(...) {} }
extern "C" JNIEXPORT void JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_close(JNIEnv *e,jobject self) {
    try {
        auto state=engine(e,self);state->ctx.reset();
        std::lock_guard<std::mutex> lock(enginesMutex);
        for(auto it=engines.begin();it!=engines.end();++it) if(it->second==state) {engines.erase(it);break;}
    } catch(...) {}
}
extern "C" JNIEXPORT jboolean JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_prepare(JNIEnv *e,jobject self,jint fd,jstring cache) {
    try {
        auto state = engine(e,self);
        ClCacheSpan span(state->clCachePrepareDelta); // v24-final: prepare-call span incl cold load
        bool reused = prepare(*state,fd,fromJava(e,cache));
        span.commit();
        return reused;
    }
    catch(...) {e->ThrowNew(e->FindClass("java/io/IOException"),"Experimental OpenCL model load failed");return false;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_timings(JNIEnv *e,jobject self) {
    auto state=engine(e,self);
    futo_session_timings(state->ctx.get(),state->metrics);
    futo_embedding_mirror_stats(state->ctx.get(),state->metrics+33);
    auto result=e->NewDoubleArray(40);e->SetDoubleArrayRegion(result,0,40,state->metrics);return result;
}
// v24-final kernel-cache diagnostic: run-call [hits,misses,saves,rejects,load_ms,
// compile_ms(total),src_builds,src_direct,direct_ms] deltas (implicit prepare
// + decode). NaN entries mean unknown (cancel/exception/CPU).
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_clCacheStats(JNIEnv *e,jobject self) {
    auto state=engine(e,self);
    auto result=e->NewDoubleArray(9);e->SetDoubleArrayRegion(result,0,9,state->clCacheDelta);return result;
}
// v24-final: prepare-call deltas (cold context load included). NaN entries mean unknown.
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_clCachePrepareStats(JNIEnv *e,jobject self) {
    auto state=engine(e,self);
    auto result=e->NewDoubleArray(9);e->SetDoubleArrayRegion(result,0,9,state->clCachePrepareDelta);return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_org_futo_voiceinput_shared_opencl_NativeOpenClEncoderFlash_runGuardedBeam(JNIEnv *env,jobject self,jint modelFd,jfloatArray audio,jstring prompt,jboolean suppress,jstring cache) {
    try { return runGuarded(env,self,engine(env,self),modelFd,audio,prompt,suppress,cache); }
    catch(const std::exception &) {
        if(!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"),"Experimental OpenCL recognition failed");
        return nullptr;
    }
}


