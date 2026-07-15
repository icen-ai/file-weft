package ai.icen.fw.spi.storage

/**
 * Optional storage capability for provider-enforced HTTP byte ranges.
 *
 * It is intentionally separate from [StorageAdapter]: adding an abstract
 * method to the released interface would break existing third-party adapter
 * binaries. Implementations must never fetch bytes outside the requested
 * range merely to discard them locally. A range that reaches beyond the end
 * may return the valid clipped suffix; an offset beyond the object must fail.
 * Implementations must reject negative offsets, non-positive lengths and end
 * arithmetic overflow before issuing provider I/O. The location is opaque
 * authority loaded from tenant-authorized server state, never a client path.
 */
interface RangedStorageAdapter {
    fun downloadRange(
        location: StorageObjectLocation,
        offset: Long,
        length: Long,
    ): StorageDownload
}
