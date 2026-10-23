#!/usr/bin/env python3
"""
Minimal UDIF (.dmg) writer, so a macOS-mountable disk image can be built on any OS.

macOS's DiskImageMounter mounts only Apple UDIF images, not raw ISO/HFS files (that is why a
plain ISO renamed .dmg does nothing). A UDIF file is simply:

    [ data fork: the filesystem image in zlib/raw chunks ]
    [ XML plist: the block map ("blkx" resource) ]
    [ koly trailer: 512 bytes describing all of the above ]

We wrap an ISO9660+Rock-Ridge image (which carries the .app with correct +x bits and an
"Applications" drop-link). Checksums are written as type 0 (none), which is legal and makes macOS
mount without strict verification — this avoids the one part of the format that is easy to get
subtly wrong and impossible to test without a Mac.

Reference: the "Apple Disk Image" / UDIF layout used by hdiutil (koly trailer + mish block table).
"""
import base64
import io
import os
import struct
import zlib

SECTOR = 512
CHUNK_SECTORS = 2048            # 1 MiB chunks
ENTRY_ZLIB = 0x80000005
ENTRY_RAW = 0x00000001
ENTRY_TERM = 0xFFFFFFFF
NAME = "whole disk (unknown partition : 0)"


def _checksum_none():
    # UDIFChecksum: uint32 type=0 (none), uint32 size=0, 128 bytes data.
    return struct.pack(">II", 0, 0) + b"\x00" * 128


def _build_blkx(image):
    """Returns (data_fork_bytes, mish_bytes) for the whole image as one blkx table."""
    total_sectors = len(image) // SECTOR
    data_fork = io.BytesIO()
    chunks = []  # (entry_type, sector_number, sector_count, comp_offset, comp_length)
    sector = 0
    pos = 0
    while sector < total_sectors:
        count = min(CHUNK_SECTORS, total_sectors - sector)
        raw = image[sector * SECTOR:(sector + count) * SECTOR]
        comp = zlib.compress(raw, 9)
        if len(comp) < len(raw):
            etype, payload = ENTRY_ZLIB, comp
        else:
            etype, payload = ENTRY_RAW, raw
        data_fork.write(payload)
        chunks.append((etype, sector, count, pos, len(payload)))
        pos += len(payload)
        sector += count
    # Terminator chunk.
    chunks.append((ENTRY_TERM, total_sectors, 0, pos, 0))

    mish = io.BytesIO()
    mish.write(struct.pack(">4sI", b"mish", 1))          # signature, version
    mish.write(struct.pack(">QQQ", 0, total_sectors, 0))  # SectorNumber, SectorCount, DataOffset
    mish.write(struct.pack(">II", 0x00000000, 0xFFFFFFFF))  # BuffersNeeded, BlockDescriptors
    mish.write(b"\x00" * 24)                               # reserved[6]
    mish.write(_checksum_none())                           # checksum
    mish.write(struct.pack(">I", len(chunks)))            # NumberOfBlockChunks
    for etype, snum, scount, coff, clen in chunks:
        mish.write(struct.pack(">IIQQQQ", etype, 0, snum, scount, coff, clen))
    return data_fork.getvalue(), mish.getvalue()


def _xml(mish):
    data_b64 = base64.b64encode(mish).decode("ascii")
    # Wrap base64 at 68 chars, as hdiutil does.
    wrapped = "\n".join(data_b64[i:i + 68] for i in range(0, len(data_b64), 68))
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        '<plist version="1.0">\n<dict>\n'
        '\t<key>resource-fork</key>\n\t<dict>\n'
        '\t\t<key>blkx</key>\n\t\t<array>\n'
        '\t\t\t<dict>\n'
        '\t\t\t\t<key>Attributes</key>\n\t\t\t\t<string>0x0050</string>\n'
        '\t\t\t\t<key>CFName</key>\n\t\t\t\t<string>' + NAME + '</string>\n'
        '\t\t\t\t<key>Data</key>\n\t\t\t\t<data>\n' + wrapped + '\n\t\t\t\t</data>\n'
        '\t\t\t\t<key>ID</key>\n\t\t\t\t<string>-1</string>\n'
        '\t\t\t\t<key>Name</key>\n\t\t\t\t<string>' + NAME + '</string>\n'
        '\t\t\t</dict>\n\t\t</array>\n\t</dict>\n</dict>\n</plist>\n'
    ).encode("utf-8")


def _koly(data_len, xml_offset, xml_len, total_sectors):
    t = bytearray(512)
    struct.pack_into(">4sIII", t, 0, b"koly", 4, 512, 1)       # sig, version, headersize, flags
    struct.pack_into(">Q", t, 16, 0)                            # RunningDataForkOffset
    struct.pack_into(">Q", t, 24, 0)                            # DataForkOffset
    struct.pack_into(">Q", t, 32, data_len)                    # DataForkLength
    struct.pack_into(">Q", t, 40, 0)                            # RsrcForkOffset
    struct.pack_into(">Q", t, 48, 0)                            # RsrcForkLength
    struct.pack_into(">II", t, 56, 1, 1)                        # SegmentNumber, SegmentCount
    t[64:80] = os.urandom(16)                                   # SegmentID
    t[80:216] = _checksum_none()                               # DataForkChecksum
    struct.pack_into(">Q", t, 216, xml_offset)                 # XMLOffset
    struct.pack_into(">Q", t, 224, xml_len)                    # XMLLength
    # 232..352 reserved (zero)
    t[352:488] = _checksum_none()                              # MasterChecksum
    struct.pack_into(">I", t, 488, 1)                          # ImageVariant
    struct.pack_into(">Q", t, 492, total_sectors)             # SectorCount
    return bytes(t)


def write_dmg(image_bytes, out_path):
    """Wraps a raw filesystem image (e.g. ISO9660) as a UDIF .dmg at out_path."""
    if len(image_bytes) % SECTOR:
        image_bytes = image_bytes + b"\x00" * (SECTOR - len(image_bytes) % SECTOR)
    total_sectors = len(image_bytes) // SECTOR
    data_fork, mish = _build_blkx(image_bytes)
    xml = _xml(mish)
    koly = _koly(len(data_fork), len(data_fork), len(xml), total_sectors)
    with open(out_path, "wb") as f:
        f.write(data_fork)
        f.write(xml)
        f.write(koly)
    return out_path
