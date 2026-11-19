#pragma once
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

// Reject writable/nonregular/truncated imports before native load/hash verification.
inline bool cookie_read_model_fd(int fd, std::vector<unsigned char>& bytes) {
    struct stat st{};
    const int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || (flags & O_ACCMODE) != O_RDONLY || fstat(fd, &st) ||
        !S_ISREG(st.st_mode) || st.st_size != 16919407) return false;
    bytes.resize(st.st_size);
    size_t done = 0;
    while (done < bytes.size()) {
        ssize_t n = pread(fd, bytes.data()+done, bytes.size()-done, done);
        if (n <= 0) return false;
        done += static_cast<size_t>(n);
    }
    return true;
}
