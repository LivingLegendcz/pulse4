"""Překlad tréninku mezi modelem aplikace (JSON z Kotlinu) a formátem Garmin Connect.

Čistá funkce bez sítě: ID kroků, podmínek a cílů vycházejí z konstant `garminconnect.workout`
(`/workout-service/workout/types`). Správnost na konkrétních hodinkách musí ověřit přejímací brána
„Garmin zápis“; tento modul zaručuje jen strukturální shodu a bezeztrátový zpětný překlad.
"""
import json

SPORTS = {
    "RUNNING": {"sportTypeId": 1, "sportTypeKey": "running", "displayOrder": 1},
    "CYCLING": {"sportTypeId": 2, "sportTypeKey": "cycling", "displayOrder": 2},
}

STEP_TYPES = {
    "WARMUP": (1, "warmup", 1),
    "COOLDOWN": (2, "cooldown", 2),
    "WORK": (3, "interval", 3),
    "RECOVERY": (4, "recovery", 4),
    "REST": (5, "rest", 5),
}
STEP_BY_KEY = {v[1]: k for k, v in STEP_TYPES.items()}

NO_TARGET = {"workoutTargetTypeId": 1, "workoutTargetTypeKey": "no.target", "displayOrder": 1}
TARGETS = {  # typ v našem DTO -> (id, klíč)
    "power": (2, "power.zone"),
    "cadence": (3, "cadence"),
    "hr": (4, "heart.rate.zone"),
    "pace": (6, "pace.zone"),
}
TARGET_BY_KEY = {v[1]: k for k, v in TARGETS.items()}

REPEAT_STEP = {"stepTypeId": 6, "stepTypeKey": "repeat", "displayOrder": 6}
ITERATIONS = {"conditionTypeId": 7, "conditionTypeKey": "iterations", "displayOrder": 7, "displayable": False}


def _condition(duration):
    t = duration["type"]
    if t == "time":
        return {"conditionTypeId": 2, "conditionTypeKey": "time", "displayOrder": 2, "displayable": True}, float(duration["seconds"])
    if t == "distance":
        return {"conditionTypeId": 3, "conditionTypeKey": "distance", "displayOrder": 3, "displayable": True}, float(duration["meters"])
    if t == "lap":
        return {"conditionTypeId": 1, "conditionTypeKey": "lap.button", "displayOrder": 1, "displayable": True}, None
    raise ValueError("Neznámý typ trvání: %s" % t)


def _target(target):
    t = target.get("type", "none")
    if t == "none":
        return dict(NO_TARGET), None, None
    tid, key = TARGETS[t]
    ttype = {"workoutTargetTypeId": tid, "workoutTargetTypeKey": key, "displayOrder": 1}
    if t == "pace":
        # Garmin ukládá tempo jako rychlost v m/s; hodnota One je nižší rychlost (pomalejší tempo).
        slow, fast = target["maxSecPerKm"], target["minSecPerKm"]
        return ttype, 1000.0 / slow, 1000.0 / fast
    if t == "hr":
        return ttype, float(target["minBpm"]), float(target["maxBpm"])
    if t == "power":
        return ttype, float(target["minW"]), float(target["maxW"])
    if t == "cadence":
        return ttype, float(target["min"]), float(target["max"])
    raise ValueError("Neznámý cíl: %s" % t)


def _step(node, order):
    stype = STEP_TYPES[node["stepType"]]
    cond, cval = _condition(node["duration"])
    ttype, one, two = _target(node.get("target") or {"type": "none"})
    step = {
        "type": "ExecutableStepDTO",
        "stepOrder": order,
        "stepType": {"stepTypeId": stype[0], "stepTypeKey": stype[1], "displayOrder": stype[2]},
        "endCondition": cond,
        "targetType": ttype,
    }
    if cval is not None:
        step["endConditionValue"] = cval
    if one is not None:
        step["targetValueOne"] = one
        step["targetValueTwo"] = two
    return step


def _estimate_seconds(nodes):
    total = 0.0
    def step_s(s):
        d = s["duration"]
        if d["type"] == "time":
            return float(d["seconds"])
        if d["type"] == "distance":
            t = s.get("target") or {}
            pace = (t["minSecPerKm"] + t["maxSecPerKm"]) / 2.0 if t.get("type") == "pace" else 360.0
            return d["meters"] / 1000.0 * pace
        return 0.0
    for n in nodes:
        if n["type"] == "step":
            total += step_s(n)
        else:
            total += n["count"] * sum(step_s(s) for s in n["steps"])
    return int(total)


def to_garmin(workout, marker=""):
    """`workout` je dict v podobě Kotlin `Workout` (kotlinx-serialization). Vrací dict pro `upload_workout`."""
    sport = SPORTS[workout["sport"]]
    order = 0
    steps = []
    for node in workout["nodes"]:
        if node["type"] == "step":
            order += 1
            steps.append(_step(node, order))
        elif node["type"] == "repeat":
            order += 1
            group_order = order
            children = []
            for s in node["steps"]:
                order += 1
                children.append(_step(s, order))
            steps.append({
                "type": "RepeatGroupDTO",
                "stepOrder": group_order,
                "stepType": dict(REPEAT_STEP),
                "numberOfIterations": int(node["count"]),
                "workoutSteps": children,
                "endCondition": dict(ITERATIONS),
                "endConditionValue": float(node["count"]),
                "smartRepeat": False,
            })
        else:
            raise ValueError("Neznámý uzel: %s" % node["type"])
    name = (workout["name"] + (" " + marker if marker else "")).strip()
    return {
        "workoutName": name,
        "sportType": dict(sport),
        "estimatedDurationInSecs": _estimate_seconds(workout["nodes"]),
        "workoutSegments": [{"segmentOrder": 1, "sportType": dict(sport), "workoutSteps": steps}],
    }


def _duration_back(step):
    key = step["endCondition"]["conditionTypeKey"]
    if key == "time":
        return {"type": "time", "seconds": int(round(step["endConditionValue"]))}
    if key == "distance":
        return {"type": "distance", "meters": int(round(step["endConditionValue"]))}
    if key == "lap.button":
        return {"type": "lap"}
    raise ValueError("Nepodporovaná podmínka ukončení: %s" % key)


def _target_back(step):
    tt = (step.get("targetType") or {}).get("workoutTargetTypeKey", "no.target")
    if tt == "no.target":
        return {"type": "none"}
    kind = TARGET_BY_KEY.get(tt)
    one, two = step.get("targetValueOne"), step.get("targetValueTwo")
    if kind is None or one is None or two is None:
        raise ValueError("Nepodporovaný cíl: %s" % tt)
    if kind == "pace":
        return {"type": "pace", "minSecPerKm": int(round(1000.0 / two)), "maxSecPerKm": int(round(1000.0 / one))}
    if kind == "hr":
        return {"type": "hr", "minBpm": int(round(one)), "maxBpm": int(round(two))}
    if kind == "power":
        return {"type": "power", "minW": int(round(one)), "maxW": int(round(two))}
    return {"type": "cadence", "min": int(round(one)), "max": int(round(two))}


def _step_back(s):
    key = s["stepType"]["stepTypeKey"]
    if key not in STEP_BY_KEY:
        raise ValueError("Nepodporovaný typ kroku: %s" % key)
    return {"type": "step", "stepType": STEP_BY_KEY[key], "duration": _duration_back(s), "target": _target_back(s)}


def from_garmin(garmin, workout_id="", owner_id="", marker_pattern=None):
    """Zpětný překlad do našeho DTO. Nepodporovaný tvar vyvolá ValueError (aplikace to zobrazí jako cizí/neznámý trénink)."""
    sport_key = garmin["sportType"]["sportTypeKey"]
    sport = {"running": "RUNNING", "cycling": "CYCLING"}.get(sport_key)
    if sport is None:
        raise ValueError("Nepodporovaný sport: %s" % sport_key)
    segs = garmin["workoutSegments"]
    if len(segs) != 1:
        raise ValueError("Podporován jediný segment")
    nodes = []
    for s in segs[0]["workoutSteps"]:
        if s.get("type") == "RepeatGroupDTO":
            children = []
            for c in s["workoutSteps"]:
                if c.get("type") == "RepeatGroupDTO":
                    raise ValueError("Vnořená opakování nejsou podporována")
                children.append(_step_back(c))
            nodes.append({"type": "repeat", "count": int(s["numberOfIterations"]), "steps": children})
        else:
            nodes.append(_step_back(s))
    name = garmin["workoutName"]
    if marker_pattern:
        import re
        name = re.sub(marker_pattern, "", name).strip()
    return {"id": workout_id, "name": name, "sport": sport, "nodes": nodes, "ownerId": owner_id}


def dumps(d):
    return json.dumps(d, ensure_ascii=False)
