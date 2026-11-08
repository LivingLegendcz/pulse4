"""Neoficiální Garmin konektor (python-garminconnect): zápis a plánování tréninků.

  * Heslo se nikdy neukládá; přihlášení vrací JSON tokeny, které uloží Kotlin šifrovaně (Keystore).
    Knihovně se nikdy nepředává cesta (`tokenstore`) – nic se nezapisuje do souborů.
  * MFA: `login_password` vrátí `needs_mfa`, dokončí se `resume_mfa(kód)`.
  * Chyby se propagují jako výjimky; Kotlin je mapuje (Auth → 401, TooManyRequests → 429, Connection → nejistý/síťový).
  * Tvar odpovědí kalendáře není oficiálně zdokumentován: parser je tolerantní, ale při neznámém tvaru
    selže hlasitě (ValueError), aby se z nejasného stavu nestal slepý duplicitní zápis.
"""
import json
import re

from . import workout_codec as codec

MARKER_RE = r"\s*\[p4-[0-9a-f]+\]"

_client = None
_pending = None


def _garmin(*args, **kwargs):
    from garminconnect import Garmin  # import až při použití: modul jde importovat i bez knihovny (testy)
    return Garmin(*args, **kwargs)


def _token_holder(g):
    return getattr(g, "garth", None) or getattr(g, "client", None)


def _dump_tokens(g):
    holder = _token_holder(g)
    if holder is None:
        raise RuntimeError("Garmin klient neobsahuje garth/client objekt")
    return holder.dumps()


def _extract_expires_ms(g):
    try:
        holder = _token_holder(g)
        oauth2 = getattr(holder, "oauth2_token", None)
        if oauth2 is not None:
            exp = getattr(oauth2, "refresh_token_expires_at", None) or getattr(oauth2, "expires_at", None)
            if exp:
                return int(float(exp) * 1000)
    except Exception:
        pass
    return None


def login_password(email, password):
    """Přihlášení jménem a heslem. Vrací JSON `{status: ok|needs_mfa, tokens?, expiresAtMs?}`."""
    global _client, _pending
    g = _garmin(email, password, return_on_mfa=True)
    res = g.login()  # bez tokenstore → žádný soubor
    if isinstance(res, tuple) and len(res) == 2:
        status, state = res
    else:
        status, state = res, None
    if status == "needs_mfa":
        _pending = (g, state)
        return json.dumps({"status": "needs_mfa"})
    _client, _pending = g, None
    out = {"status": "ok", "tokens": _dump_tokens(g)}
    exp_ms = _extract_expires_ms(g)
    if exp_ms:
        out["expiresAtMs"] = exp_ms
    return json.dumps(out)


def resume_mfa(code):
    global _client, _pending
    if _pending is None:
        raise RuntimeError("Není rozpracované přihlášení s MFA")
    g, state = _pending
    g.resume_login(state, code)
    _client, _pending = g, None
    out = {"status": "ok", "tokens": _dump_tokens(g)}
    exp_ms = _extract_expires_ms(g)
    if exp_ms:
        out["expiresAtMs"] = exp_ms
    return json.dumps(out)


def login_tokens(tokens_json):
    """Obnova relace z uložených tokenů (bez hesla). Vrací případně obnovené tokeny."""
    global _client
    g = _garmin()
    if hasattr(g, "garth") and hasattr(g.garth, "loads"):
        g.garth.loads(tokens_json)
    else:
        g.login(tokenstore=tokens_json)
    _client = g
    return _dump_tokens(g)


def connectapi_json(path):
    """Zavolá Garmin Connect API cestu přes přihlášenou relaci garth a vrátí JSON řetězec."""
    g = _g()
    holder = _token_holder(g)
    if hasattr(holder, "connectapi"):
        data = holder.connectapi(path)
    else:
        data = g.connectapi(path)
    return json.dumps(data if data is not None else [])


def logout():
    global _client, _pending
    _client = _pending = None


def _g():
    if _client is None:
        raise RuntimeError("Garmin relace neběží")
    return _client


def download_activity_fit(activity_id):
    """Stáhne originální FIT soubor (nebo zip) pro danou aktivitu a vrátí Base64 string."""
    import base64
    import logging
    g = _g()
    try:
        raw = g.download_activity(str(activity_id), dl_fmt=g.ActivityDownloadFormat.ORIGINAL)
        if raw and len(raw) > 0:
            return base64.b64encode(raw).decode('ascii')
    except Exception as e:
        logging.getLogger("pulse4_garmin").warning(f"Failed to download original FIT for {activity_id}: {e}")
    return ""


def get_activity_details(activity_id):
    """Vrátí detaily aktivity včetně GPS polyline / bodů v JSON."""
    import logging
    g = _g()
    try:
        data = g.get_activity_details(str(activity_id))
        return json.dumps(data if data is not None else {})
    except Exception as e:
        logging.getLogger("pulse4_garmin").warning(f"Failed to get activity details for {activity_id}: {e}")
        return "{}"


# ---------------------------------------------------------------------------------------------
def list_workouts():
    """Seznam tréninků (id, název). Podpis kroků se zde nepočítá – jen `get_workout`."""
    out = []
    start = 0
    while True:
        page = _g().get_workouts(start=start, limit=100)
        if not page:
            break
        out += [{"id": str(w["workoutId"]), "name": w.get("workoutName", "")} for w in page]
        if len(page) < 100:
            break
        start += 100
    return json.dumps(out)


def get_workout(workout_id):
    """Trénink v našem DTO, nebo `workout: null`, pokud má tvar, který editor nepodporuje."""
    raw = _g().get_workout_by_id(workout_id)
    try:
        dto = codec.from_garmin(raw, str(workout_id), "", MARKER_RE)
    except (ValueError, KeyError, TypeError):
        dto = None
    return json.dumps({"id": str(workout_id), "name": raw.get("workoutName", ""), "workout": dto})


def upsert_workout(workout_json, marker, existing_id):
    payload = codec.to_garmin(json.loads(workout_json), marker)
    g = _g()
    if existing_id:
        g.update_workout(existing_id, payload)
        return str(existing_id)
    res = g.upload_workout(payload)
    return str(res["workoutId"])


def normalize_calendar(entries):
    """Sjednotí položky kalendáře na `{calendarId, workoutId, date}`; neznámý tvar = ValueError."""
    out = []
    for e in entries:
        cal = e.get("workoutScheduleId") or e.get("scheduleId") or e.get("id")
        wk = e.get("workoutId") or (e.get("workout") or {}).get("workoutId")
        dt = e.get("date") or e.get("calendarDate") or e.get("scheduledDate")
        if cal is None or wk is None or dt is None:
            raise ValueError("Neznámý tvar položky kalendáře: klíče %s" % sorted(e.keys()))
        out.append({"calendarId": str(cal), "workoutId": str(wk), "date": str(dt)[:10]})
    return out


def list_calendar(date):
    return json.dumps(normalize_calendar(_g().get_scheduled_workouts_by_period(date, date)))


def schedule(workout_id, date):
    res = _g().schedule_workout(workout_id, date)
    cal = (res or {}).get("workoutScheduleId")
    if cal is None:
        raise ValueError("Odpověď plánování neobsahuje workoutScheduleId; ověřit v kalendáři")
    return str(cal)


def request_device_sync():
    """Požadavek na odeslání naplánovaných tréninků do zařízení. Není to důkaz, že je hodinky přijaly."""
    _g().send_scheduled_workouts_to_devices()
