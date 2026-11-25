package com.truehue.camera;

import java.util.Arrays;

/** Preserves Ultra HDR MPF secondary gainmap offsets when ExifInterface updates the primary JPEG. */
final class UltraHdrPatcher {
    private UltraHdrPatcher() {}

    static final class Split {
        final byte[] primary;
        final byte[] tail;

        Split(byte[] primary, byte[] tail) {
            this.primary = primary;
            this.tail = tail;
        }
    }

    private static final class MpfLocation {
        final int tiffBase;
        final boolean littleEndian;
        final int entry0Offset;
        final int entry1Offset;
        final int entryCount;

        MpfLocation(int tiffBase, boolean littleEndian, int entry0Offset, int entry1Offset, int entryCount) {
            this.tiffBase = tiffBase;
            this.littleEndian = littleEndian;
            this.entry0Offset = entry0Offset;
            this.entry1Offset = entry1Offset;
            this.entryCount = entryCount;
        }
    }

    static Split split(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 64) return null;
        MpfLocation loc = findMpf(jpeg);
        if (loc == null || loc.entryCount < 2) return null;
        int secRel = readInt(jpeg, loc.entry1Offset + 8, loc.littleEndian);
        int secStart = loc.tiffBase + secRel;
        if (secRel <= 0 || secStart <= 0 || secStart + 4 > jpeg.length) return null;
        if ((jpeg[secStart] & 0xff) != 0xff || (jpeg[secStart + 1] & 0xff) != 0xd8) return null;
        return new Split(Arrays.copyOfRange(jpeg, 0, secStart), Arrays.copyOfRange(jpeg, secStart, jpeg.length));
    }

    static byte[] reassemble(byte[] editedPrimary, byte[] tail) {
        if (editedPrimary == null || tail == null) return null;
        MpfLocation loc = findMpf(editedPrimary);
        if (loc == null || loc.entryCount < 2) return null;
        byte[] out = new byte[editedPrimary.length + tail.length];
        System.arraycopy(editedPrimary, 0, out, 0, editedPrimary.length);
        System.arraycopy(tail, 0, out, editedPrimary.length, tail.length);
        // Update primary entry size and secondary entry relative offset from new MPF TIFF base.
        writeInt(out, loc.entry0Offset + 4, editedPrimary.length, loc.littleEndian);
        writeInt(out, loc.entry1Offset + 8, editedPrimary.length - loc.tiffBase, loc.littleEndian);
        return out;
    }

    private static MpfLocation findMpf(byte[] jpeg) {
        if ((jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) return null;
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xff) != 0xff) return null;
            while (pos < jpeg.length && (jpeg[pos] & 0xff) == 0xff) pos++;
            if (pos >= jpeg.length) return null;
            int marker = jpeg[pos++] & 0xff;
            if (marker == 0xd9 || marker == 0xda) break; // EOI or SOS
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            if (pos + 2 > jpeg.length) break;
            int segLen = ((jpeg[pos] & 0xff) << 8) | (jpeg[pos + 1] & 0xff);
            if (segLen < 2 || pos + segLen > jpeg.length) break;
            if (marker == 0xe2 && segLen >= 16) {
                int p = pos + 2;
                if (jpeg[p] == 'M' && jpeg[p + 1] == 'P' && jpeg[p + 2] == 'F' && jpeg[p + 3] == 0) {
                    MpfLocation loc = parseMpfTiff(jpeg, p + 4, pos + segLen);
                    if (loc != null) return loc;
                }
            }
            pos += segLen;
        }
        return null;
    }

    private static MpfLocation parseMpfTiff(byte[] buf, int tiffBase, int segEnd) {
        if (tiffBase + 8 > segEnd) return null;
        boolean le;
        if (buf[tiffBase] == 'I' && buf[tiffBase + 1] == 'I') le = true;
        else if (buf[tiffBase] == 'M' && buf[tiffBase + 1] == 'M') le = false;
        else return null;
        if (readShort(buf, tiffBase + 2, le) != 42) return null;
        int ifdRel = readInt(buf, tiffBase + 4, le);
        int ifd = tiffBase + ifdRel;
        if (ifd < tiffBase + 8 || ifd + 2 > segEnd) return null;
        int count = readShort(buf, ifd, le);
        int pos = ifd + 2;
        for (int i = 0; i < count; i++, pos += 12) {
            if (pos + 12 > segEnd) break;
            int tag = readShort(buf, pos, le);
            if (tag == 0xb002) { // MPEntry
                int byteCount = readInt(buf, pos + 4, le);
                int valRel = readInt(buf, pos + 8, le);
                int entry0 = tiffBase + valRel;
                int entryCount = byteCount / 16;
                if (entryCount >= 2 && entry0 >= tiffBase && entry0 + 32 <= segEnd) {
                    return new MpfLocation(tiffBase, le, entry0, entry0 + 16, entryCount);
                }
            }
        }
        return null;
    }

    private static int readShort(byte[] b, int off, boolean le) {
        return le
                ? ((b[off] & 0xff) | ((b[off + 1] & 0xff) << 8))
                : (((b[off] & 0xff) << 8) | (b[off + 1] & 0xff));
    }

    private static int readInt(byte[] b, int off, boolean le) {
        return le
                ? ((b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24))
                : (((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff));
    }

    private static void writeInt(byte[] b, int off, int value, boolean le) {
        if (le) {
            b[off] = (byte) (value & 0xff);
            b[off + 1] = (byte) ((value >>> 8) & 0xff);
            b[off + 2] = (byte) ((value >>> 16) & 0xff);
            b[off + 3] = (byte) ((value >>> 24) & 0xff);
        } else {
            b[off] = (byte) ((value >>> 24) & 0xff);
            b[off + 1] = (byte) ((value >>> 16) & 0xff);
            b[off + 2] = (byte) ((value >>> 8) & 0xff);
            b[off + 3] = (byte) (value & 0xff);
        }
    }
}
