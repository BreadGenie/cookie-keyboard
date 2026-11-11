#include "model_fd.h"
#include "model_hash.h"
#include <cassert>
#include <cstdio>
int main(int argc, char** argv) {
    assert(argc == 2);
    std::vector<unsigned char> bytes;
    assert(!cookie_read_model_fd(-1, bytes));
    int fd = open(argv[1], O_RDWR); assert(fd >= 0);
    assert(!cookie_read_model_fd(fd, bytes)); close(fd);
    fd = open(argv[1], O_RDONLY); assert(fd >= 0);
    assert(cookie_read_model_fd(fd, bytes)); close(fd);
    assert(cookie_model_hash(bytes.data(), bytes.size()));
    int ends[2]; assert(pipe(ends) == 0);
    assert(!cookie_read_model_fd(ends[0], bytes)); close(ends[0]); close(ends[1]);
    FILE* temp = tmpfile(); assert(temp);
    assert(!cookie_read_model_fd(fileno(temp), bytes));
    char path[64]; std::snprintf(path, sizeof path, "/proc/self/fd/%d", fileno(temp));
    int readonly = open(path, O_RDONLY); assert(readonly >= 0);
    assert(!cookie_read_model_fd(readonly, bytes)); close(readonly); fclose(temp);
    puts("PASS: read-only regular model FD + hash; writable, invalid, pipe, wrong-size rejected");
}
