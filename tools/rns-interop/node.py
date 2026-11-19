#!/usr/bin/env python3
"""
Reference RNS 1.6.0 transport + LXMF 1.3.0 propagation node + Python LXMF client
on 127.0.0.1 for Pulse4 interop testing (§11.3).

Usage: node.py <workdir> <port> <kotlin_seed_hex>
"""
import hashlib
import json
import os
import sys
import time

work, port, seed_hex = sys.argv[1], int(sys.argv[2]), sys.argv[3]
os.makedirs(work, exist_ok=True)
cfg = os.path.join(work, "rnscfg")
os.makedirs(cfg, exist_ok=True)
with open(os.path.join(cfg, "config"), "w") as f:
    f.write(f"""
[reticulum]
  enable_transport = Yes
  share_instance = No
  panic_on_interface_error = No
[logging]
  loglevel = 7
[interfaces]
  [[TCP Server]]
    type = TCPServerInterface
    enabled = yes
    listen_ip = 127.0.0.1
    listen_port = {port}
""")

import RNS
import LXMF
from LXMF.LXMessage import LXMessage

RNS.logdest = RNS.LOG_FILE
RNS.logfile = os.path.join(work, "rns.log")
r = RNS.Reticulum(configdir=cfg, loglevel=7, logdest=RNS.LOG_FILE)
RNS.logfile = os.path.join(work, "rns.log")

node_id = RNS.Identity()
router = LXMF.LXMRouter(identity=node_id, storagepath=os.path.join(work, "lxmf"), delivery_limit=2048)
router.enable_propagation()
prop_dest = router.propagation_destination

# Restricted PN (auth_required=True, empty allowed_list) for testing ERROR_NO_ACCESS (0xF1)
restricted_id = RNS.Identity()
restricted_router = LXMF.LXMRouter(identity=restricted_id, storagepath=os.path.join(work, "lxmf_restricted"))
restricted_router.enable_propagation()
restricted_router.auth_required = True
restricted_router.allowed_list = []
restricted_prop_dest = restricted_router.propagation_destination

seed = bytes.fromhex(seed_hex)
kid = RNS.Identity.from_bytes(hashlib.sha256(seed).digest() + seed)
alice = RNS.Identity()
src_in = RNS.Destination(alice, RNS.Destination.IN, RNS.Destination.SINGLE, "lxmf", "delivery")

bob = RNS.Identity()
bob_in = router.register_delivery_identity(bob, display_name="bob")

direct_kt_to_py = []
delivery_links = []
orig_link_cb = bob_in.callbacks.link_established
def _on_delivery_link(link):
    delivery_links.append(link)
    if orig_link_cb:
        orig_link_cb(link)
bob_in.set_link_established_callback(_on_delivery_link)

def _on_direct_delivery(message):
    direct_kt_to_py.append({
        "title": message.title.decode("utf-8", errors="replace"),
        "content_len": len(message.content),
        "content_sha256": hashlib.sha256(message.content).hexdigest(),
        "sig_valid": bool(message.signature_validated),
    })
    RNS.log(f"[NODE] direct delivery received title={message.title!r} len={len(message.content)} sig={message.signature_validated}", RNS.LOG_CRITICAL)
    if delivery_links and message.signature_validated:
        link = delivery_links[-1]
        if link.status == RNS.Link.ACTIVE:
            if len(message.content) >= 1_200_000:
                py_1m2 = LXMessage(kd, bob_in, os.urandom(1_250_000), title="py-1m2-resource", desired_method=LXMessage.DIRECT)
                py_1m2.pack()
                RNS.Resource(py_1m2.packed, link)
            elif len(message.content) >= 16384:
                py_16k = LXMessage(kd, bob_in, os.urandom(16384), title="py-16k-resource", desired_method=LXMessage.DIRECT)
                py_16k.pack()
                RNS.Resource(py_16k.packed, link)

router.register_delivery_callback(_on_direct_delivery)

# Inject small + large (> link.mdu) Python -> Kotlin messages into the PN via lxmf.delivery
kd = RNS.Destination(kid, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
m = LXMessage(kd, src_in, "hello from python lxmf 1.3.0", title="py-title", desired_method=LXMessage.PROPAGATED)
m.pack()
lxmf_data = m.packed[:16] + kd.encrypt(m.packed[16:])
router.lxmf_propagation(lxmf_data, stamp_value=20, stamp_data=os.urandom(32))
py_to_kt_tid = RNS.Identity.full_hash(lxmf_data).hex()

m_large = LXMessage(kd, src_in, os.urandom(100_000), title="py-large-get", desired_method=LXMessage.PROPAGATED)
m_large.pack()
lxmf_large_data = m_large.packed[:16] + kd.encrypt(m_large.packed[16:])
router.lxmf_propagation(lxmf_large_data, stamp_value=20, stamp_data=os.urandom(32))
py_large_kt_tid = RNS.Identity.full_hash(lxmf_large_data).hex()

announces_seen = []

class AnnounceHandler:
    aspect_filter = None
    def received_announce(self, destination_hash, announced_identity, app_data):
        announces_seen.append(destination_hash.hex())
        RNS.log(f"[NODE] announce received for {destination_hash.hex()} app_data={app_data!r}", RNS.LOG_CRITICAL)

RNS.Transport.register_announce_handler(AnnounceHandler())

info = {
    "rns": RNS._version.__version__,
    "lxmf": LXMF._version.__version__,
    "prop_dest": prop_dest.hash.hex(),
    "restricted_prop_dest": restricted_prop_dest.hash.hex(),
    "node_transport_id": RNS.Transport.identity.hash.hex(),
    "alice_pub": alice.get_public_key().hex(),
    "alice_lxmf_dest": src_in.hash.hex(),
    "bob_pub": bob.get_public_key().hex(),
    "bob_lxmf_dest": bob_in.hash.hex(),
    "kotlin_identity_hash": kid.hash.hex(),
    "kotlin_lxmf_dest": kd.hash.hex(),
    "py_to_kt_tid": py_to_kt_tid,
    "py_large_kt_tid": py_large_kt_tid,
    "stamp_cost": router.propagation_stamp_cost,
    "stamp_flex": router.propagation_stamp_cost_flexibility,
}
with open(os.path.join(work, "info.json"), "w") as f:
    json.dump(info, f)
router.announce_propagation_node()
restricted_router.announce_propagation_node()
bob_in.announce()
print("READY", json.dumps(info), flush=True)

# Periodically write propagation_entries + verify any Kotlin->Python deposited message
RNS.Identity.remember(os.urandom(32), kd.hash, kid.get_public_key())
while True:
    time.sleep(0.5)
    entries = {}
    verified_kt_msgs = []
    for k, v in list(router.propagation_entries.items()):
        entries[k.hex()] = v[0].hex()
        if v[0] == src_in.hash:
            try:
                raw = open(v[1], "rb").read()
                lxm_bytes = raw[:-32]  # strip 32B PN stamp
                dec = src_in.decrypt(lxm_bytes[16:])
                if dec is not None:
                    unpacked = LXMessage.unpack_from_bytes(lxm_bytes[:16] + dec)
                    verified_kt_msgs.append({
                        "tid": k.hex(),
                        "title": unpacked.title.decode("utf-8", errors="replace"),
                        "content": unpacked.content.decode("utf-8", errors="replace"),
                        "sig_valid": bool(unpacked.signature_validated),
                    })
            except Exception as e:
                verified_kt_msgs.append({"tid": k.hex(), "error": str(e)})
    with open(os.path.join(work, "entries.json"), "w") as f:
        json.dump({
            "entries": entries,
            "kt_to_py": verified_kt_msgs,
            "direct_kt_to_py": direct_kt_to_py,
            "announces": announces_seen,
        }, f)
