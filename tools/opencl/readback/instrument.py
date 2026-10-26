"""Bounded aggregate transfer attribution. No tensor contents or per-call logging."""
def instrument(source):
 p=source/'ggml/src/ggml-opencl/ggml-opencl.cpp'
 s=p.read_text()
 marker='static void ggml_backend_opencl_buffer_get_tensor(ggml_backend_buffer_t buffer, const ggml_tensor * tensor, void * data, size_t offset, size_t size) {'
 declaration='extern "C" void futo_tensor_read(unsigned long long, int);\n'
 if declaration not in s:
  assert s.count(marker)==1
  # Only static model metadata is classified; arbitrary tensor names are never exported.
  code='''
    // v8 attribution is aggregate API requests, not extra wall time or bandwidth.
    // Runtime tensors carry no ggml name (create_tensor never sets tensor->name),
    // so classify by the exact static shape/type/size only.
    const bool futo_embedding = tensor->type == GGML_TYPE_Q8_0 &&
        tensor->ne[0] == 768 && tensor->ne[1] == 51864 &&
        tensor->ne[2] == 1 && tensor->ne[3] == 1;
    const int futo_kind = futo_embedding ?
        ((offset == 0 && size == 42321024) ? 1 : 2) : 0;
    futo_tensor_read(size, futo_kind);
'''
  s=s.replace(marker,declaration+marker+code)
 p.write_text(s)
