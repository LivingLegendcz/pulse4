#!/usr/bin/env python3
"""
Pulse4 Local Reticulum TCP Mesh Hub
Broadcasts HDLC-framed Reticulum packets between connected clients (POCO Phone & Emulator).
"""
import socket
import select
import sys
import time

PORT = 4242
HOST = "0.0.0.0"

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(10)
    server.setblocking(False)

    print(f"[RNS Hub] Listening on {HOST}:{PORT}")
    clients = []

    try:
        while True:
            readable, _, exceptional = select.select([server] + clients, [], clients, 0.5)

            for s in readable:
                if s is server:
                    client_sock, client_addr = server.accept()
                    client_sock.setblocking(False)
                    client_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    clients.append(client_sock)
                    print(f"[RNS Hub] New client connected: {client_addr} (total: {len(clients)})")
                else:
                    try:
                        data = s.recv(65536)
                        if data:
                            # Broadcast to all other clients
                            for other in clients:
                                if other is not s:
                                    try:
                                        other.sendall(data)
                                    except Exception as e:
                                        print(f"[RNS Hub] Send error: {e}")
                        else:
                            # Disconnected
                            print(f"[RNS Hub] Client disconnected (total: {len(clients) - 1})")
                            clients.remove(s)
                            s.close()
                    except Exception as e:
                        print(f"[RNS Hub] Recv error: {e}")
                        if s in clients:
                            clients.remove(s)
                        s.close()

            for s in exceptional:
                if s in clients:
                    clients.remove(s)
                s.close()

    except KeyboardInterrupt:
        print("\n[RNS Hub] Stopping...")
    finally:
        for c in clients:
            c.close()
        server.close()

if __name__ == "__main__":
    main()
