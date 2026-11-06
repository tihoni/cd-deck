#!/usr/bin/env python3
"""
arm64_mpd_cd_toc_helper.py
--------------------------
Zero-dependency Linux (arm64 / x86_64) helper daemon for MPD + CD/Deck Android app.

Why this is needed:
MPD's `cdio_paranoia` (`cdda://`) input plugin only adds `cdda:///1 .. cdda:///N`
to the playlist without exposing the CD's Table of Contents (TOC sector offsets)
or track durations in `playlistinfo`. Without the TOC sector offsets, MusicBrainz
cannot compute the CD's DiscID.

What this script does (with ZERO optical drive head seeking):
1. Reads the CD's hardware TOC header directly from `/dev/sr0` (or `/dev/cdrom`)
   using Linux kernel `ioctl` (`CDROMREADTOCHDR` / `CDROMREADTOCENTRY`) in ~2ms.
2. Connects to local MPD (`127.0.0.1:6600`), subscribes to channel `cddeck`, and
   stamps the exact MusicBrainz TOC string (`1 <N> <leadout> <offset1> ...`) onto
   Track 1 (`cdda:///1`) via `addtagid <songid> Grouping "<toc>"`.
3. Automatically re-stamps whenever MPD's playlist changes or when the CD/Deck
   Android app taps Refresh (`sendmessage cddeck refresh_toc`).
"""

import fcntl
import os
import socket
import struct
import subprocess
import time

CDROM_DEVICES = ["/dev/sr0", "/dev/cdrom"]
MPD_HOST = os.environ.get("MPD_HOST", "127.0.0.1")
MPD_PORT = int(os.environ.get("MPD_PORT", "6600"))

# Linux <linux/cdrom.h> ioctl constants
CDROMREADTOCHDR = 0x5305
CDROMREADTOCENTRY = 0x5306
CDROM_LBA = 0x01
CDROM_LEADOUT = 0xAA


def read_cd_toc_ioctl(dev_path: str) -> str | None:
    """
    Reads the Redbook CD TOC directly from the optical drive controller cache
    using Linux ioctl (zero laser head seeking, zero external dependencies).
    Returns MusicBrainz TOC format: "1 <lastTrack> <leadoutSector> <offset1> ... <offsetN>"
    """
    fd = None
    try:
        fd = os.open(dev_path, os.O_RDONLY | os.O_NONBLOCK)
        # struct cdrom_tochdr { __u8 cdth_trk0; __u8 cdth_trk1; }
        hdr = fcntl.ioctl(fd, CDROMREADTOCHDR, struct.pack("BB", 0, 0))
        first_track, last_track = struct.unpack("BB", hdr)
        if first_track < 1 or last_track < first_track or last_track > 99:
            return None

        def read_track_lba(track_num: int) -> int:
            # struct cdrom_tocentry:
            # __u8 cdte_track, __u8 flags, __u8 cdte_format, [padding], int addr, __u8 datamode
            req = struct.pack("BBBxI4x", track_num, 0, CDROM_LBA, 0)
            res = fcntl.ioctl(fd, CDROMREADTOCENTRY, req)
            _, _, _, lba = struct.unpack("BBBxI", res[:8])
            # Redbook sector offset includes standard 150-frame (2-second) lead-in pregap
            return lba + 150

        offsets = [read_track_lba(t) for t in range(first_track, last_track + 1)]
        leadout = read_track_lba(CDROM_LEADOUT)
        parts = [1, last_track, leadout] + offsets
        return " ".join(str(x) for x in parts)
    except Exception:
        return None
    finally:
        if fd is not None:
            try:
                os.close(fd)
            except Exception:
                pass


def read_cd_toc() -> str | None:
    # 1. Try `cd-discid --musicbrainz` if installed
    for dev in CDROM_DEVICES:
        if not os.path.exists(dev):
            continue
        try:
            out = subprocess.check_output(
                ["cd-discid", "--musicbrainz", dev],
                stderr=subprocess.DEVNULL,
                text=True,
                timeout=2.0,
            ).strip()
            tokens = out.split()
            if len(tokens) >= 3:
                count = int(tokens[0])
                leadout = int(tokens[-1])
                offsets = tokens[1:-1]
                if len(offsets) == count:
                    return f"1 {count} {leadout} " + " ".join(offsets)
        except Exception:
            pass

        # 2. Fallback to built-in Linux ioctl
        toc = read_cd_toc_ioctl(dev)
        if toc:
            return toc
    return None


def mpd_cmd(sock_file, sock, cmd: str) -> list[str]:
    sock.sendall((cmd + "\n").encode("utf-8"))
    lines = []
    while True:
        line = sock_file.readline()
        if not line:
            raise ConnectionError("EOF from MPD")
        line = line.rstrip("\r\n")
        if line == "OK":
            return lines
        if line.startswith("ACK "):
            return []
        lines.append(line)


def stamp_toc_on_mpd(sock_file, sock):
    pl_lines = mpd_cmd(sock_file, sock, "playlistinfo 0")
    first_cdda_id = None
    current_file = None
    for line in pl_lines:
        if line.startswith("file:"):
            if first_cdda_id is not None:
                break
            current_file = line.split(":", 1)[1].strip()
        elif line.startswith("Id:") and current_file and current_file.startswith("cdda://"):
            first_cdda_id = line.split(":", 1)[1].strip()

    if not first_cdda_id:
        return

    toc = read_cd_toc()
    if toc:
        mpd_cmd(sock_file, sock, f"cleartagid {first_cdda_id} Grouping")
        mpd_cmd(sock_file, sock, f'addtagid {first_cdda_id} Grouping "{toc}"')
        print(f"[cddeck-toc] Stamped hardware TOC on songid {first_cdda_id}: {toc}", flush=True)


def run_daemon():
    while True:
        try:
            with socket.create_connection((MPD_HOST, MPD_PORT), timeout=10) as sock:
                sock_file = sock.makefile("r", encoding="utf-8", errors="replace")
                banner = sock_file.readline()
                if not banner.startswith("OK MPD"):
                    raise ConnectionError(f"Unexpected greeting: {banner}")

                mpd_cmd(sock_file, sock, "subscribe cddeck")
                stamp_toc_on_mpd(sock_file, sock)

                sock.settimeout(None)
                while True:
                    # Wait ONLY on `message` (never `playlist`, because `addtagid` itself
                    # triggers `changed: playlist` in MPD!). The CD/Deck app sends
                    # `sendmessage cddeck refresh_toc` on connect and on Refresh.
                    mpd_cmd(sock_file, sock, "idle message")
                    mpd_cmd(sock_file, sock, "readmessages")
                    stamp_toc_on_mpd(sock_file, sock)
        except Exception as exc:
            print(f"[cddeck-toc] Reconnecting in 3s ({exc})", flush=True)
            time.sleep(3)


if __name__ == "__main__":
    run_daemon()
