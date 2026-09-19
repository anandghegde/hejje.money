"""Reference Hejje bot (plan M7.3, docs/bots.md). Standard library only.

Connects to /ws/bot, answers every decision point, and trades a simple opening-range rule: the high and low of the bars
closing up to 09:30 IST are the range; the first close above the range high is a long entry with the stop at the range
low and a target of twice the risk; once in a trade it tightens the stop to break-even after one R and otherwise holds.
Hejje sizes every order, runs it through risk and manages the exits (stop, target, force exit); the bot only decides.

    export HEJJE_URL=http://localhost:8080 HEJJE_API_KEY=hejje_...   # a key made with preset "bot"
    python3 research/bots/example_bot.py <bot-id>

The message loop: receive {"type": "decision_point", "pointId", "bars", "quotes", "positions", ...}; reply with
{"pointId", "decisions": [{"instrument", "action", "stop", "target", "confidence", "thesis"}]}; the server answers with
{"type": "decisions", "results": [...]} (the recorded outcomes). In SIM the replay waits for the reply (lockstep).
"""
import base64
import json
import os
import socket
import ssl
import struct
import sys
import urllib.parse
from datetime import datetime, time, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))
RANGE_END = time(9, 30)


class WebSocket:
    """A minimal RFC 6455 client: text frames, ping/pong and close; enough for the bot protocol."""

    def __init__(self, url: str):
        u = urllib.parse.urlparse(url)
        secure = u.scheme == "wss"
        port = u.port or (443 if secure else 80)
        raw = socket.create_connection((u.hostname, port), timeout=60)
        self.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=u.hostname) if secure else raw
        key = base64.b64encode(os.urandom(16)).decode()
        path = u.path + ("?" + u.query if u.query else "")
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: {u.hostname}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.sock.recv(1024)
            if not chunk:
                raise ConnectionError("connection closed during the handshake")
            response += chunk
        head, self.buffer = response.split(b"\r\n\r\n", 1)
        if b" 101 " not in head.split(b"\r\n", 1)[0]:
            raise ConnectionError(head.decode(errors="replace"))

    def _read(self, n: int) -> bytes:
        while len(self.buffer) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("connection closed")
            self.buffer += chunk
        data, self.buffer = self.buffer[:n], self.buffer[n:]
        return data

    def _send(self, opcode: int, payload: bytes) -> None:
        header = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack("!H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack("!Q", n)
        mask = os.urandom(4)
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def send(self, message: dict) -> None:
        self._send(0x1, json.dumps(message).encode())

    def receive(self) -> dict | None:
        """The next JSON text message, or None when the server closed the connection."""
        parts = []
        while True:
            b0, b1 = self._read(2)
            opcode, n = b0 & 0x0F, b1 & 0x7F
            if n == 126:
                n = struct.unpack("!H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack("!Q", self._read(8))[0]
            payload = self._read(n)
            if opcode == 0x8:
                return None
            if opcode == 0x9:
                self._send(0xA, payload)
                continue
            if opcode in (0x1, 0x0):
                parts.append(payload)
                if b0 & 0x80:
                    return json.loads(b"".join(parts))


class OpeningRangeBot:
    """The trading rule: pure, fed one decision point at a time."""

    def __init__(self):
        self.day = None
        self.high = {}
        self.low = {}
        self.entered = set()

    def decide(self, point: dict) -> list[dict]:
        decisions = []
        positions = {p["instrument"]: p for p in point.get("positions", [])}
        for bar in point.get("bars", []):
            symbol = bar["instrument"]
            opened = datetime.fromisoformat(bar["openTime"].replace("Z", "+00:00")).astimezone(IST)
            if opened.date() != self.day:
                self.day, self.high, self.low, self.entered = opened.date(), {}, {}, set()
            closes_at = (opened + timedelta(minutes=5)).time()
            high, low, close = float(bar["high"]), float(bar["low"]), float(bar["close"])
            if closes_at <= RANGE_END:
                self.high[symbol] = max(self.high.get(symbol, high), high)
                self.low[symbol] = min(self.low.get(symbol, low), low)
                decisions.append({"instrument": symbol, "action": "NONE", "stage": "range"})
                continue
            position = positions.get(symbol)
            if position is None and symbol not in self.entered and symbol in self.high and close > self.high[symbol]:
                stop = round(self.low[symbol], 2)
                risk = close - stop
                self.entered.add(symbol)
                decisions.append({"instrument": symbol, "action": "ENTER_LONG", "stop": stop, "target": round(close + 2 * risk, 2),
                                  "confidence": 0.6, "stage": "breakout", "thesis": f"close {close} above the opening range high {self.high[symbol]}",
                                  "scores": {"breakout_pct": round((close / self.high[symbol] - 1) * 100, 3)}})
            elif position is not None and position.get("status") == "OPEN":
                entry, stop = float(position["entry"]), float(position["stop"])
                if stop < entry and close - entry >= entry - stop:
                    decisions.append({"instrument": symbol, "action": "MOVE_STOP", "stop": round(entry, 2), "stage": "manage",
                                      "thesis": "one R in hand: stop to break-even"})
                else:
                    decisions.append({"instrument": symbol, "action": "HOLD", "stage": "manage"})
            else:
                decisions.append({"instrument": symbol, "action": "NONE"})
        return decisions


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: example_bot.py <bot-id>")
    base, key = os.environ.get("HEJJE_URL", "http://localhost:8080"), os.environ.get("HEJJE_API_KEY")
    if not key:
        sys.exit("set HEJJE_API_KEY (a client credential made with preset \"bot\")")
    url = base.replace("https://", "wss://").replace("http://", "ws://").rstrip("/") + "/ws/bot?" + urllib.parse.urlencode({"token": key, "bot": sys.argv[1]})
    ws, bot = WebSocket(url), OpeningRangeBot()
    while True:
        message = ws.receive()
        if message is None:
            print("server closed the connection")
            return
        kind = message.get("type")
        if kind == "decision_point":
            decisions = bot.decide(message)
            ws.send({"pointId": message["pointId"], "decisions": decisions})
            acted = [d for d in decisions if d["action"] not in ("NONE", "HOLD")]
            if acted:
                print(message["clock"], acted, flush=True)
        elif kind == "decisions":
            for r in message.get("results", []):
                if r["action"] not in ("NONE", "HOLD"):
                    print("  ->", r["instrument"], r["action"], r["outcome"], r.get("detail") or "", flush=True)
        elif kind == "error":
            print("error:", message.get("message"), flush=True)
        elif kind == "connected":
            print("connected as bot", message.get("botId"), flush=True)


if __name__ == "__main__":
    main()
