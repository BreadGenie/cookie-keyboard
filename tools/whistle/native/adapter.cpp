#include "adapter.h"
#include "cactus.h"
#include "model_hash.h"
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>

namespace {
constexpr size_t ModelBytes = 16919407;
constexpr int OutputBytes = 262144;
std::mutex worker;
std::atomic<uint64_t> active{0};
std::atomic<bool> invalid{false};
bool attempted = false, ready = false, finished = false;
std::vector<unsigned char> weights; // Retain: header does not promise load copies bytes.
char buffer[OutputBytes];

// Bounded structural JSON validation; strings are also checked as UTF-8 below.
// The future IPC consumer must validate the result schema before accumulating text.
struct Json {
    const char *p, *end;
    void ws() { while (p < end && (*p==' ' || *p=='\n' || *p=='\r' || *p=='\t')) ++p; }
    bool take(char c) { ws(); if (p == end || *p != c) return false; ++p; return true; }
    bool str() {
        if (!take('"')) return false;
        while (p < end) {
            unsigned char c = *p++;
            if (c == '"') return true;
            if (c < 32) return false;
            if (c == '\\') {
                if (p == end) return false;
                char e = *p++;
                if (e == 'u') { for (int i=0; i<4; ++i) {
                    if (p == end) return false;
                    char h=*p++; if (!((h>='0'&&h<='9')||(h>='a'&&h<='f')||(h>='A'&&h<='F'))) return false;
                }} else if (!std::strchr("\"\\/bfnrt", e)) return false;
            }
        }
        return false;
    }
    bool value(int depth=0) {
        ws(); if (depth>32 || p==end) return false;
        if (*p=='"') return str();
        if (*p=='{' || *p=='[') {
            bool obj=*p++=='{'; char close=obj?'}':']';
            if (take(close)) return true;
            do { if (obj && (!str() || !take(':'))) return false;
                 if (!value(depth+1)) return false;
                 if (take(close)) return true;
            } while (take(','));
            return false;
        }
        for (auto literal : {"true", "false", "null"}) {
            size_t n=std::strlen(literal);
            if (size_t(end-p)>=n && !std::memcmp(p,literal,n)) { p+=n; return true; }
        }
        if (*p=='-') ++p;
        if (p==end) return false;
        if (*p=='0') ++p;
        else { if (*p<'1'||*p>'9') return false; while(p<end && *p>='0'&&*p<='9') ++p; }
        if (p<end && *p=='.') { ++p; auto start=p; while(p<end && *p>='0'&&*p<='9') ++p; if(p==start) return false; }
        if (p<end && (*p=='e'||*p=='E')) { ++p; if(p<end&&(*p=='+'||*p=='-')) ++p;
            auto start=p; while(p<end && *p>='0'&&*p<='9') ++p; if(p==start) return false; }
        return true;
    }
};
bool utf8(const char* s, size_t n) {
    size_t i=0;
    while(i<n) {
        unsigned char c=s[i++]; if(c<128) continue;
        unsigned cp; int extra;
        if(c>=0xc2 && c<=0xdf) { cp=c&31; extra=1; }
        else if(c>=0xe0 && c<=0xef) { cp=c&15; extra=2; }
        else if(c>=0xf0 && c<=0xf4) { cp=c&7; extra=3; }
        else return false;
        if(i+extra>n) return false;
        for(int k=0;k<extra;++k) { unsigned char d=s[i++]; if((d&0xc0)!=0x80) return false; cp=(cp<<6)|(d&63); }
        if((extra==2&&cp<0x800)||(extra==3&&cp<0x10000)||cp>0x10ffff||(cp>=0xd800&&cp<=0xdfff)) return false;
    }
    return true;
}
int fail() { invalid.store(true); return -1; }
bool valid(uint64_t g) { return g && active.load()==g && !invalid.load(); }
int result(int code, uint64_t g, char* out, int capacity) {
    if (code < 0 || !valid(g)) return fail();
    const char* nul = static_cast<const char*>(std::memchr(buffer, 0, OutputBytes));
    if (!nul || nul==buffer || nul-buffer>=OutputBytes-1) return fail();
    int length = static_cast<int>(nul-buffer);
    // Runtime's nonnegative return convention is not documented as a byte count.
    // Do not trust it for sizing or retry an already-mutated stream on overflow.
    if (code >= OutputBytes || capacity <= length || !utf8(buffer,length)) return fail();
    Json json{buffer,nul}; json.ws();
    if (json.p==nul || *json.p!='{' || !json.value()) return fail();
    json.ws(); if (json.p!=nul) return fail();
    if (!valid(g)) return fail();
    std::memcpy(out, buffer, length+1);
    return length;
}
}
extern "C" int cookie_whistle_load(uint64_t g, const unsigned char* data, size_t size) {
    std::unique_lock<std::mutex> lock(worker,std::try_to_lock);
    if (!lock.owns_lock()) return fail();
    if (attempted) return fail();
    attempted=true;
    if (!g || !data || size!=ModelBytes || invalid.load()) return fail();
    active.store(g);
    try {
        weights.assign(data,data+size);
        if (!cookie_model_hash(weights.data(),weights.size())) return fail();
        if (cactus_load(weights.data(),weights.size()) < 0 || cactus_models() != CACTUS_SPEECH || !valid(g)) return fail();
        ready=true;
        return 0;
    } catch (...) { return fail(); }
}
extern "C" int cookie_whistle_feed(uint64_t g, const float* pcm, int samples, char* out, int capacity) {
    if(out && capacity>0) out[0]=0;
    std::unique_lock<std::mutex> lock(worker,std::try_to_lock);
    if (!lock.owns_lock()) return fail();
    if (!valid(g)||!ready||finished||!pcm||samples<1||samples>16000||!out||capacity<OutputBytes) return fail();
    for(int i=0;i<samples;++i) if(!std::isfinite(pcm[i])||pcm[i]<-1||pcm[i]>1) return fail();
    std::memset(buffer,0xff,sizeof buffer);
    try { return result(cactus_stream_transcribe_process(pcm,samples,"en",nullptr,buffer,OutputBytes),g,out,capacity); }
    catch (...) { return fail(); }
}
extern "C" int cookie_whistle_finish(uint64_t g, char* out, int capacity) {
    if(out && capacity>0) out[0]=0;
    std::unique_lock<std::mutex> lock(worker,std::try_to_lock);
    if (!lock.owns_lock()) return fail();
    if (!valid(g)||!ready||finished||!out||capacity<OutputBytes) return fail();
    finished=true;
    std::memset(buffer,0xff,sizeof buffer);
    try { return result(cactus_stream_transcribe_stop(buffer,OutputBytes),g,out,capacity); }
    catch (...) { return fail(); }
}
extern "C" void cookie_whistle_cancel(uint64_t g) {
    // Cancellation may arrive before load has published its generation.
    if (!active.load() || active.load()==g) invalid.store(true);
}
