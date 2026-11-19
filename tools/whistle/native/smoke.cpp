#include "adapter.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
// Developer harness only: input is little-endian native float32 16 kHz mono.
// Parent script verifies fixture and provisioning. Each invocation is one process.
int main(int argc, char** argv) {
    if(argc!=3) return 2;
    std::ifstream model_file(argv[1],std::ios::binary);
    std::vector<unsigned char> model{std::istreambuf_iterator<char>(model_file),{}};
    if(cookie_whistle_load(1,model.data(),model.size())<0) return 3;
    std::ifstream pcm_file(argv[2],std::ios::binary);
    if(!pcm_file) return 4;
    float pcm[16000];std::vector<char> out(262144);
    while(pcm_file.read(reinterpret_cast<char*>(pcm),sizeof pcm) || pcm_file.gcount()) {
        auto bytes=pcm_file.gcount();
        if(bytes%sizeof(float)) return 5;
        if(cookie_whistle_feed(1,pcm,bytes/sizeof(float),out.data(),out.size())<0) return 6;
        std::cout<<out.data()<<'\n';
    }
    if(!pcm_file.eof()) return 7;
    if(cookie_whistle_finish(1,out.data(),out.size())<0) return 8;
    std::cout<<out.data()<<'\n';
}
