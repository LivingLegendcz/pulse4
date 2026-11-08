"""Most mezi aplikací (Kotlin) a referenčním Reticulum/LXMF.

Zásady:
  * Kotlin a Python si předávají jen malé verzované DTO jako JSON řetězce (`PROTOCOL`).
  * Soukromý klíč identity se na disk nezapisuje: předává se z Kotlinu (zašifrovaný Keystorem) jako
    bajty a při vytvoření se vrací zpět k uložení.
  * Příjem je pull: události čte Kotlin přes `drain_events()`; žádné zpětné volání z Pythonu.
  * Aplikační ACK a ověření oprávnění řeší Kotlinová vrstva; zde se hlásí jen to, co lze doložit:
    `HANDED` = LXMF doručení přímo příjemci prokázáno, `STORED` = uloženo v propagation uzlu.
    Žádný z těchto stavů neznamená, že příjemcova aplikace změnu zpracovala.
  * RNS je v procesu singleton: po `stop()` se znovu startuje až novým procesem.
"""
import base64
import json
import os
import threading
import time

import LXMF
import RNS

PROTOCOL = 1

_lock = threading.RLock()
_state = None


class _State:
    def __init__(self):
        self.reticulum = None
        self.router = None
        self.identity = None
        self.delivery = None
        self.contacts = {}
        self.pending = {}
        self.events = []
        self.propagation_node = None


def _b64(b):
    return base64.b64encode(b).decode("ascii")


def _push(ev):
    ev["v"] = PROTOCOL
    ev["at"] = int(time.time() * 1000)
    with _lock:
        _state.events.append(ev)


def _write_config(config_dir, interfaces, enable_transport):
    os.makedirs(config_dir, exist_ok=True)
    lines = [
        "[reticulum]",
        "  enable_transport = %s" % ("yes" if enable_transport else "no"),
        "  share_instance = no",
        "  panic_on_interface_error = no",
        "[logging]",
        "  loglevel = 2",
        "[interfaces]",
    ]
    for i, itf in enumerate(interfaces):
        kind = itf["type"]
        name = itf.get("name") or ("iface%d" % i)
        lines.append("  [[%s]]" % name)
        lines.append("    enabled = yes")
        if kind == "auto":
            lines.append("    type = AutoInterface")
            if itf.get("group_id"):
                lines.append("    group_id = %s" % itf["group_id"])
        elif kind == "tcp_client":
            lines.append("    type = TCPClientInterface")
            lines.append("    target_host = %s" % itf["host"])
            lines.append("    target_port = %d" % int(itf["port"]))
        elif kind == "tcp_server":
            lines.append("    type = TCPServerInterface")
            lines.append("    listen_ip = %s" % itf.get("host", "127.0.0.1"))
            lines.append("    listen_port = %d" % int(itf["port"]))
        else:
            raise ValueError("Neznámý typ rozhraní: %s" % kind)
    with open(os.path.join(config_dir, "config"), "w") as f:
        f.write("\n".join(lines) + "\n")


def start(config_dir, identity_private_b64, display_name, interfaces_json,
          enable_propagation=False, enable_transport=False):
    """Spustí RNS a LXMF. Vrací JSON s veřejnou identitou a (při vytvoření) soukromým klíčem k uložení."""
    global _state
    with _lock:
        if _state is not None:
            raise RuntimeError("Reticulum už běží; restart vyžaduje nový proces")
        st = _State()
        _write_config(config_dir, json.loads(interfaces_json), enable_transport)
        st.reticulum = RNS.Reticulum(configdir=config_dir)

        created = not identity_private_b64
        if created:
            st.identity = RNS.Identity()
        else:
            st.identity = RNS.Identity(create_keys=False)
            if not st.identity.load_private_key(base64.b64decode(identity_private_b64)):
                raise ValueError("Neplatný soukromý klíč identity")

        storage = os.path.join(config_dir, "lxmf")
        os.makedirs(storage, exist_ok=True)
        st.router = LXMF.LXMRouter(identity=st.identity, storagepath=storage)
        st.delivery = st.router.register_delivery_identity(st.identity, display_name=display_name)
        st.router.register_delivery_callback(_on_message)
        if enable_propagation:
            st.router.enable_propagation()
        _state = st
        out = {
            "v": PROTOCOL,
            "identityHash": st.identity.hash.hex(),
            "deliveryHash": st.delivery.hash.hex(),
            "publicKey": _b64(st.identity.get_public_key()),
            "createdIdentity": created,
        }
        if created:
            out["privateKey"] = _b64(st.identity.get_private_key())
        if enable_propagation:
            out["propagationHash"] = st.router.propagation_destination.hash.hex()
        return json.dumps(out)


def stop():
    global _state
    with _lock:
        if _state is None:
            return
        try:
            _state.router.exit_handler()
        finally:
            RNS.Reticulum.exit_handler()
            _state = None


def announce():
    _require().router.announce(_state.delivery.hash)


def add_contact(public_key_b64):
    """Zaregistruje ověřený kontakt z QR kódu (veřejný klíč). Vrací hash jeho LXMF doručovací adresy."""
    st = _require()
    pub = base64.b64decode(public_key_b64)
    ident = RNS.Identity(create_keys=False)
    if not ident.load_public_key(pub):
        raise ValueError("Neplatný veřejný klíč")
    dest_hash = RNS.Destination.hash(ident, "lxmf", "delivery")
    RNS.Identity.remember(None, dest_hash, pub)
    st.contacts[dest_hash.hex()] = ident
    return dest_hash.hex()


def remove_contact(dest_hash_hex):
    _require().contacts.pop(dest_hash_hex, None)


def set_propagation_node(hash_hex):
    """Nastaví LXMF propagation uzel (jeho propagation destination, ne transport ID ani discovery hash)."""
    st = _require()
    st.propagation_node = bytes.fromhex(hash_hex) if hash_hex else None
    if st.propagation_node:
        st.router.set_outbound_propagation_node(st.propagation_node)


def send(dest_hash_hex, content, method, event_id):
    """Zařadí zprávu k odeslání. `method`: 'direct' | 'propagated'. Výsledek přijde jako událost `delivery`."""
    st = _require()
    ident = st.contacts.get(dest_hash_hex)
    if ident is None:
        raise KeyError("Neznámý kontakt")
    dest = RNS.Destination(ident, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
    wanted = LXMF.LXMessage.PROPAGATED if method == "propagated" else LXMF.LXMessage.DIRECT
    if wanted == LXMF.LXMessage.PROPAGATED and st.propagation_node is None:
        raise RuntimeError("Propagation uzel není nastaven")
    msg = LXMF.LXMessage(dest, st.delivery, content, "", desired_method=wanted)
    msg.register_delivery_callback(_on_state)
    msg.register_failed_callback(_on_state)
    with _lock:
        st.pending[id(msg)] = (event_id, msg)
    print(f"[Pulse4Bridge] SEND to {dest_hash_hex}, method={method}, id={id(msg)}")
    st.router.handle_outbound(msg)


def sync_inbox(max_messages=0):
    """Vyzvedne zprávy čekající v propagation uzlu."""
    st = _require()
    if st.propagation_node is None:
        raise RuntimeError("Propagation uzel není nastaven")
    st.router.request_messages_from_propagation_node(st.identity, max_messages=max_messages)


def drain_events():
    """Vrátí a vyprázdní frontu událostí jako JSON pole."""
    st = _require()
    with _lock:
        evs, st.events = st.events, []
    return json.dumps(evs)


def status():
    st = _require()
    pending_details = [{"id": k, "ev": v[0], "state": v[1].state, "progress": getattr(v[1], "progress", None)} for k, v in list(st.pending.items())]
    return json.dumps({
        "v": PROTOCOL,
        "interfaces": [{"name": str(i), "online": bool(getattr(i, "online", False))} for i in RNS.Transport.interfaces],
        "propagationNodeSet": st.propagation_node is not None,
        "pendingOutbound": len(st.pending),
        "pendingDetails": pending_details,
    })


# --------------------------------------------------------------------------------------------
def _require():
    if _state is None:
        raise RuntimeError("Reticulum neběží")
    return _state


def _on_message(message):
    """Příchozí zpráva. `source` je jen tvrzení do ověření podpisu: Kotlin zahazuje `validated == false`."""
    print(f"[Pulse4Bridge] INCOMING_LXMF from {message.source_hash.hex()} validated={message.signature_validated}")
    _push({
        "type": "message",
        "source": message.source_hash.hex(),
        "validated": bool(message.signature_validated),
        "content": message.content_as_string(),
    })


def _on_state(message):
    entry = _state.pending.get(id(message)) if _state else None
    if entry is None:
        return
    event_id = entry[0]
    s = message.state
    print(f"[Pulse4Bridge] STATE_CHANGE event_id={event_id} state={s}")
    if s == LXMF.LXMessage.DELIVERED:
        res = {"state": "HANDED"}
    elif s == LXMF.LXMessage.SENT and message.desired_method == LXMF.LXMessage.PROPAGATED:
        res = {"state": "STORED"}
    elif s in (LXMF.LXMessage.FAILED, LXMF.LXMessage.REJECTED, LXMF.LXMessage.CANCELLED):
        res = {"state": "FAILED", "reason": {LXMF.LXMessage.FAILED: "failed", LXMF.LXMessage.REJECTED: "rejected",
                                             LXMF.LXMessage.CANCELLED: "cancelled"}[s]}
    else:
        return
    with _lock:
        if res["state"] in ("HANDED", "FAILED"):
            _state.pending.pop(id(message), None)
    res.update({"type": "delivery", "eventId": event_id})
    _push(res)
