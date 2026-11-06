#!/usr/bin/env python3
"""
Mock MPD (Music Player Daemon) TCP Server on port 6600 for testing CD/Deck.
Simulates a loaded physical Redbook Audio CD (Daft Punk - Discovery, 14 tracks)
so MusicBrainz & Cover Art Archive resolve real track titles, multiple album
editions, and high-res cover art immediately.
"""

import socket
import threading
import time

HOST = "0.0.0.0"
PORT = 6600

# Real Redbook CD track durations (in seconds) for Daft Punk - Discovery (14 tracks)
# MusicBrainz will match these durations to multiple Official releases with cover art.
DISCOVERY_CD_TRACKS = [
    ("cdda:///1", 320.266),
    ("cdda:///2", 212.600),
    ("cdda:///3", 301.493),
    ("cdda:///4", 224.466),
    ("cdda:///5", 293.666),
    ("cdda:///6", 103.533),
    ("cdda:///7", 237.733),
    ("cdda:///8", 222.200),
    ("cdda:///9", 219.093),
    ("cdda:///10", 291.773),
    ("cdda:///11", 279.693),
    ("cdda:///12", 198.266),
    ("cdda:///13", 344.933),
    ("cdda:///14", 600.293),
]

state_lock = threading.Lock()
player_state = {
    "state": "stop",      # play | pause | stop
    "song": 0,
    "elapsed": 0.0,
    "repeat": 0,
    "single": 0,
    "random": 0,
    "playlist_version": 2,
    "last_tick": time.time(),
}

idle_waiters: list[socket.socket] = []


def update_elapsed_locked():
    now = time.time()
    if player_state["state"] == "play":
        dt = now - player_state["last_tick"]
        player_state["elapsed"] += dt
        dur = DISCOVERY_CD_TRACKS[player_state["song"]][1]
        if player_state["elapsed"] >= dur:
            player_state["song"] = (player_state["song"] + 1) % len(DISCOVERY_CD_TRACKS)
            player_state["elapsed"] = 0.0
    player_state["last_tick"] = now


def notify_idle(subsystem: str = "player"):
    with state_lock:
        targets = list(idle_waiters)
        idle_waiters.clear()
    for sock in targets:
        try:
            sock.sendall(f"changed: {subsystem}\nOK\n".encode("utf-8"))
        except OSError:
            pass


def format_status() -> str:
    with state_lock:
        update_elapsed_locked()
        song = player_state["song"]
        dur = DISCOVERY_CD_TRACKS[song][1]
        el = min(player_state["elapsed"], dur)
        lines = [
            f"repeat: {player_state['repeat']}",
            f"random: {player_state['random']}",
            f"single: {player_state['single']}",
            f"playlist: {player_state['playlist_version']}",
            f"playlistlength: {len(DISCOVERY_CD_TRACKS)}",
            f"state: {player_state['state']}",
            f"song: {song}",
            f"songid: {song + 1}",
            f"elapsed: {el:.3f}",
            f"duration: {dur:.3f}",
            "bitrate: 1411",
            "audio: 44100:16:2",
            "OK\n",
        ]
        return "\n".join(lines)


def format_track(pos: int) -> str:
    uri, dur = DISCOVERY_CD_TRACKS[pos]
    return "\n".join([
        f"file: {uri}",
        f"Time: {int(round(dur))}",
        f"duration: {dur:.3f}",
        f"Track: {pos + 1}",
        f"Pos: {pos}",
        f"Id: {pos + 1}",
    ])


def handle_single_command(cmd_line: str, conn: socket.socket) -> tuple[str, str | None]:
    parts = cmd_line.strip().split()
    if not parts:
        return "", None
    cmd = parts[0].lower()

    if cmd == "status":
        return format_status(), None
    elif cmd == "currentsong":
        with state_lock:
            song = player_state["song"]
        return format_track(song) + "\nOK\n", None
    elif cmd == "playlistinfo":
        body = "\n".join(format_track(i) for i in range(len(DISCOVERY_CD_TRACKS)))
        return body + "\nOK\n", None
    elif cmd == "play":
        with state_lock:
            update_elapsed_locked()
            if len(parts) > 1 and parts[1].isdigit():
                new_pos = int(parts[1]) % len(DISCOVERY_CD_TRACKS)
                if new_pos != player_state["song"] or player_state["state"] == "stop":
                    player_state["elapsed"] = 0.0
                player_state["song"] = new_pos
            player_state["state"] = "play"
        return "OK\n", "player"
    elif cmd == "pause":
        with state_lock:
            update_elapsed_locked()
            if len(parts) > 1:
                player_state["state"] = "pause" if parts[1] == "1" else "play"
            else:
                player_state["state"] = "pause" if player_state["state"] == "play" else "play"
        return "OK\n", "player"
    elif cmd == "stop":
        with state_lock:
            player_state["state"] = "stop"
            player_state["elapsed"] = 0.0
        return "OK\n", "player"
    elif cmd == "next":
        with state_lock:
            player_state["song"] = (player_state["song"] + 1) % len(DISCOVERY_CD_TRACKS)
            player_state["elapsed"] = 0.0
            player_state["last_tick"] = time.time()
        return "OK\n", "player"
    elif cmd == "previous":
        with state_lock:
            player_state["song"] = (player_state["song"] - 1) % len(DISCOVERY_CD_TRACKS)
            player_state["elapsed"] = 0.0
            player_state["last_tick"] = time.time()
        return "OK\n", "player"
    elif cmd == "seekcur":
        with state_lock:
            if len(parts) > 1:
                player_state["elapsed"] = max(0.0, float(parts[1]))
                player_state["last_tick"] = time.time()
        return "OK\n", "player"
    elif cmd == "repeat":
        with state_lock:
            if len(parts) > 1:
                player_state["repeat"] = 1 if parts[1] == "1" else 0
        return "OK\n", "options"
    elif cmd == "single":
        with state_lock:
            if len(parts) > 1:
                player_state["single"] = 1 if parts[1] == "1" else 0
        return "OK\n", "options"
    elif cmd == "random":
        with state_lock:
            if len(parts) > 1:
                player_state["random"] = 1 if parts[1] == "1" else 0
        return "OK\n", "options"
    elif cmd == "idle":
        with state_lock:
            idle_waiters.append(conn)
        return "", None
    elif cmd == "noidle":
        with state_lock:
            if conn in idle_waiters:
                idle_waiters.remove(conn)
        return "OK\n", None
    else:
        return "OK\n", None


def client_thread(conn: socket.socket, addr):
    print(f"[MPD] Client connected from {addr}")
    try:
        conn.sendall(b"OK MPD 0.23.5\n")
        buffer = ""
        in_batch = False
        batch_cmds: list[str] = []

        while True:
            data = conn.recv(1024)
            if not data:
                break
            buffer += data.decode("utf-8", errors="replace")
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                line = line.strip()
                if not line:
                    continue
                if line == "command_list_begin":
                    in_batch = True
                    batch_cmds.clear()
                elif line == "command_list_end":
                    in_batch = False
                    changed_sub = None
                    for bcmd in batch_cmds:
                        _, sub = handle_single_command(bcmd, conn)
                        if sub:
                            changed_sub = sub
                    conn.sendall(b"OK\n")
                    if changed_sub:
                        notify_idle(changed_sub)
                elif in_batch:
                    batch_cmds.append(line)
                else:
                    resp, changed_sub = handle_single_command(line, conn)
                    if resp:
                        conn.sendall(resp.encode("utf-8"))
                    if changed_sub:
                        notify_idle(changed_sub)
    except OSError:
        pass
    finally:
        with state_lock:
            if conn in idle_waiters:
                idle_waiters.remove(conn)
        conn.close()
        print(f"[MPD] Client disconnected: {addr}")


def main():
    print(f"Starting Mock MPD CD Deck Server on {HOST}:{PORT} ...")
    print("Simulating Redbook Audio CD: Daft Punk - Discovery (14 tracks)")
    print("-> From Android Emulator, connect to host: 10.0.2.2, port: 6600")
    print("-> From physical Android phone on Wi-Fi, connect to your PC's LAN IP, port: 6600")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((HOST, PORT))
        srv.listen(8)
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=client_thread, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
