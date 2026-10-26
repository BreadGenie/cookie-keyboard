package org.futo.voiceinput.shared.opencl

import androidx.annotation.Keep

/**
 * Exists only in :experimental_opencl. The keyboard process must never execute
 * this class initializer or load the vendor-backed library.
 */
@Keep
internal object NativeOpenClCapability {
    init { System.loadLibrary("futo_opencl") }

    /**
     * Metadata-only probe. No model, context, queue, kernel, image, or audio.
     * Returns the versioned content-free `key=value` contract consumed by
     * [OpenClProbeParser]. Native failures are returned as a status line where
     * possible; LinkageError is handled by the caller.
     */
    external fun query(): String
}
