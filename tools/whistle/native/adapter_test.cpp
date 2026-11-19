#include "adapter.h"
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <string>
#include <thread>
#include <vector>

static std::string mode;
static int stops=0, loads=0;
static std::atomic<bool> entered{false}, release_call{false};
extern "C" int cactus_load(const unsigned char*, unsigned long long) { ++loads; return mode=="load-error"?-1:0; }
extern "C" int cactus_models() { return mode=="wrong-family"?1:2; }
static int output(char* out, int cap) {
    if(mode=="native-error") return -1;
    if(mode=="unterminated") { std::memset(out,'x',cap); return 0; }
    if(mode=="reported-overflow") return cap;
    if(mode=="malformed") { std::strcpy(out,"{\"text\":]"); return 0; }
    if(mode=="invalid-utf8") { std::strcpy(out,"{\"text\":\"\xff\"}"); return 0; }
    std::strcpy(out,"{\"text\":\"hello\",\"pending\":\"\",\"words\":[],\"received\":1}"); return 0;
}
extern "C" int cactus_stream_transcribe_process(const float*,int,const char* lang,const char*,char* out,int cap) {
    assert(std::string(lang)=="en");
    entered=true;
    if(mode=="cancel-blocked"||mode=="concurrent") while(!release_call.load()) std::this_thread::yield();
    return output(out,cap);
}
extern "C" int cactus_stream_transcribe_stop(char* out,int cap) { ++stops; return output(out,cap); }
int main(int argc,char** argv) {
    assert(argc==3);mode=argv[1];
    std::ifstream in(argv[2],std::ios::binary);
    std::vector<unsigned char> model{std::istreambuf_iterator<char>(in),{}};
    assert(model.size()==16919407);
    if(mode=="bad-model") model[99]^=1;
    if(mode=="cancel-before-load") cookie_whistle_cancel(1);
    int init=mode=="missing-model" ? cookie_whistle_load(1,nullptr,0) : cookie_whistle_load(1,model.data(),model.size());
    if(mode=="missing-model"||mode=="bad-model"||mode=="cancel-before-load"||mode=="load-error"||mode=="wrong-family") {
        assert(init<0); if(mode=="bad-model") assert(loads==0); std::cout<<mode<<" PASS\n";return 0;
    }
    assert(init==0);
    std::vector<char> out(262144);std::vector<float> pcm(16000,0);
    if(mode=="cancel-blocked"||mode=="concurrent") {
        int status=0;
        std::thread t([&]{status=cookie_whistle_feed(1,pcm.data(),16000,out.data(),out.size());});
        while(!entered.load()) std::this_thread::yield();
        if(mode=="concurrent") { std::vector<char> other(262144);assert(cookie_whistle_finish(1,other.data(),other.size())<0); }
        else cookie_whistle_cancel(1);
        release_call=true;t.join();assert(status<0);assert(out[0]==0);assert(stops==0);
    } else if(mode=="good") {
        assert(cookie_whistle_feed(1,pcm.data(),16000,out.data(),out.size())>0);
        assert(cookie_whistle_finish(1,out.data(),out.size())>0);
        assert(cookie_whistle_finish(1,out.data(),out.size())<0);assert(stops==1);
        assert(cookie_whistle_load(2,model.data(),model.size())<0);
    } else {
        if(mode=="nan") pcm[0]=std::numeric_limits<float>::quiet_NaN();
        int count=mode=="oversized-input"?16001:16000;
        int capacity=mode=="small-output"?10:int(out.size());
        assert(cookie_whistle_feed(mode=="stale-generation"?2:1,pcm.data(),count,out.data(),capacity)<0);
        assert(out[0]==0);assert(cookie_whistle_finish(1,out.data(),out.size())<0);assert(stops==0);
    }
    std::cout<<mode<<" PASS\n";
}
