"""Lokální test mostu: tři procesy (uzel se schránkou + dva „telefony“) přes TCP na 127.0.0.1.

Spuštění: python harness.py   (vyžaduje `pip install rns lxmf`)
Ověřuje: přímé doručení, podpis odesílatele, uložení do propagation uzlu a pozdější vyzvednutí.
"""
import json, os, socket, subprocess, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "app", "src", "main", "python")


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


def role(name, work, port, mode):
    """Podproces: spustí most a čeká na příkazy ze souboru `<name>.cmd` (JSON řádky), výsledky píše do `<name>.out`."""
    code = f"""
import sys, json, time, os
sys.path.insert(0, {SRC!r})
from pulse4_rns import bridge
name={name!r}; work={work!r}
ifaces = {mode!r}
info = json.loads(bridge.start(os.path.join(work, name), None, name, ifaces, enable_propagation=(name=='node'), enable_transport=(name=='node')))
out = open(os.path.join(work, name + '.out'), 'a', buffering=1)
out.write(json.dumps({{'t': 'info', **info}}) + chr(10))
cmdf = os.path.join(work, name + '.cmd'); pos = 0
while True:
    time.sleep(0.2)
    for ev in json.loads(bridge.drain_events()):
        out.write(json.dumps({{'t': 'event', **ev}}) + chr(10))
    if os.path.exists(cmdf):
        lines = open(cmdf).read().splitlines()
        for ln in lines[pos:]:
            c = json.loads(ln)
            try:
                r = getattr(bridge, c['fn'])(*c.get('args', []))
                out.write(json.dumps({{'t': 'ret', 'fn': c['fn'], 'r': r if isinstance(r, (str, int, type(None))) else None}}) + chr(10))
            except Exception as e:
                out.write(json.dumps({{'t': 'err', 'fn': c['fn'], 'e': repr(e)}}) + chr(10))
        pos = len(lines)
"""
    return subprocess.Popen([sys.executable, "-c", code], stdout=subprocess.DEVNULL, stderr=open(os.path.join(work, name + ".log"), "w"))


def cmd(work, name, fn, *args):
    with open(os.path.join(work, name + ".cmd"), "a") as f:
        f.write(json.dumps({"fn": fn, "args": list(args)}) + "\n")


def lines(work, name):
    p = os.path.join(work, name + ".out")
    return [json.loads(l) for l in open(p).read().splitlines()] if os.path.exists(p) else []


def wait(pred, timeout=60, what=""):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(0.3)
    raise TimeoutError(what)


def info(work, name):
    return wait(lambda: next((l for l in lines(work, name) if l["t"] == "info"), None), 40, f"start {name}")


def main():
    work = tempfile.mkdtemp(prefix="p4rns-")
    port = free_port()
    procs = []
    try:
        node = role("node", work, port, json.dumps([{"type": "tcp_server", "host": "127.0.0.1", "port": port}]))
        procs.append(node)
        ni = info(work, "node")
        client = json.dumps([{"type": "tcp_client", "host": "127.0.0.1", "port": port}])
        alice = role("alice", work, port, client); procs.append(alice)
        ai = info(work, "alice")
        bob = role("bob", work, port, client); procs.append(bob)
        bi = info(work, "bob")
        print("start ok: node propagation hash", ni["propagationHash"][:12])

        # párování (QR): vyměnit veřejné klíče
        cmd(work, "alice", "add_contact", bi["publicKey"]); cmd(work, "bob", "add_contact", ai["publicKey"])
        cmd(work, "alice", "announce"); cmd(work, "bob", "announce")
        time.sleep(6)

        # 1) přímé doručení + podpis
        cmd(work, "alice", "send", bi["deliveryHash"], '{"hello":"bobe"}', "direct", "evt-1")
        m = wait(lambda: next((l for l in lines(work, "bob") if l["t"] == "event" and l["type"] == "message"), None), 60, "přímá zpráva")
        assert m["content"] == '{"hello":"bobe"}', m
        assert m["validated"] is True, m
        assert m["source"] == ai["deliveryHash"], (m["source"], ai["deliveryHash"])
        d = wait(lambda: next((l for l in lines(work, "alice") if l["t"] == "event" and l["type"] == "delivery" and l["eventId"] == "evt-1"), None), 60, "stav doručení")
        assert d["state"] == "HANDED", d
        print("1) přímé doručení OK: validated=True, source=alice, stav HANDED")

        # 2) schránka: příjemce je „offline“ (nesynchronizuje), odesílatel ukládá do uzlu
        cmd(work, "alice", "set_propagation_node", ni["propagationHash"])
        cmd(work, "bob", "set_propagation_node", ni["propagationHash"])
        cmd(work, "alice", "send", bi["deliveryHash"], "z-schranky", "propagated", "evt-2")
        s = wait(lambda: next((l for l in lines(work, "alice") if l["t"] == "event" and l["type"] == "delivery" and l["eventId"] == "evt-2"), None), 90, "uložení do schránky")
        assert s["state"] == "STORED", s
        print("2) uloženo v propagation uzlu: stav STORED (zatím NE potvrzeno příjemcem)")

        before = len([l for l in lines(work, "bob") if l["t"] == "event" and l["type"] == "message"])
        cmd(work, "bob", "sync_inbox")
        got = wait(lambda: next((l for l in lines(work, "bob") if l["t"] == "event" and l["type"] == "message" and l["content"] == "z-schranky"), None), 120, "vyzvednutí ze schránky")
        assert got["validated"] is True and got["source"] == ai["deliveryHash"], got
        print("3) pozdější vyzvednutí ze schránky OK: validated=True, source=alice")

        errs = [l for p in ("alice", "bob", "node") for l in lines(work, p) if l["t"] == "err"]
        assert not errs, errs
        print("VŠE OK")
    finally:
        for p in procs:
            p.terminate()


if __name__ == "__main__":
    main()
