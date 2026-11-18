#!/usr/bin/env python3
"""Anonymize Garmin sample JSON files with irreversible synthetic values (NEW-8)."""

from __future__ import annotations

import json
import re
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent
SAMPLES_DIR = REPO_ROOT / "docs" / "samples"
DEBUG_SAMPLE_ACTIVITIES = REPO_ROOT / "app" / "src" / "debug" / "assets" / "sample_activities.json"

TARGET_ANCHOR_DATE = date(2025, 1, 15)
EPOCH_MS_MIN = 1_500_000_000_000
EPOCH_MS_MAX = 2_000_000_000_000
MS_PER_DAY = 86_400_000

DATE_ONLY_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})$")
DATETIME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})([T ])(\d{2}:\d{2}:\d{2})(\.\d+)?$")


def _collect_dates(obj: Any, found: list[date]) -> None:
    if isinstance(obj, list):
        for item in obj:
            _collect_dates(item, found)
    elif isinstance(obj, dict):
        for v in obj.values():
            _collect_dates(v, found)
    elif isinstance(obj, str):
        m = DATE_ONLY_RE.match(obj) or DATETIME_RE.match(obj)
        if m:
            found.append(date.fromisoformat(m.group(1)))
    elif isinstance(obj, int) and not isinstance(obj, bool) and EPOCH_MS_MIN <= obj < EPOCH_MS_MAX:
        found.append(datetime.fromtimestamp(obj / 1000.0, tz=timezone.utc).date())


def _shift_string_date(val: str, shift_days: int) -> str:
    if shift_days == 0:
        return val
    m_date = DATE_ONLY_RE.match(val)
    if m_date:
        d = date.fromisoformat(m_date.group(1)) + timedelta(days=shift_days)
        return d.isoformat()
    m_dt = DATETIME_RE.match(val)
    if m_dt:
        d = date.fromisoformat(m_dt.group(1)) + timedelta(days=shift_days)
        sep, time_part, frac = m_dt.group(2), m_dt.group(3), m_dt.group(4) or ""
        return f"{d.isoformat()}{sep}{time_part}{frac}"
    return val


def _synthetic_coords(coord_idx: int) -> tuple[float, float, float, float]:
    base_lat = round(45.0 + (coord_idx % 20) * 0.015, 6)
    base_lon = round(15.0 + (coord_idx % 20) * 0.015, 6)
    return (base_lat, base_lon, round(base_lat + 0.004, 6), round(base_lon - 0.003, 6))


def _scrub(obj: Any, state: dict[str, int], shift_days: int) -> Any:
    if isinstance(obj, list):
        return [_scrub(item, state, shift_days) for item in obj]
    if isinstance(obj, dict):
        has_coords = any(k in obj for k in ("startLatitude", "startLongitude", "endLatitude", "endLongitude"))
        if has_coords:
            state["coord_idx"] += 1
            s_lat, s_lon, e_lat, e_lon = _synthetic_coords(state["coord_idx"])
        else:
            s_lat = s_lon = e_lat = e_lon = 0.0

        out: dict[str, Any] = {}
        for k, v in obj.items():
            if k in ("userProfileId", "userProfilePK", "userProfilePk", "ownerId"):
                out[k] = 10000001 if isinstance(v, int) else "10000001"
            elif k == "deviceId":
                out[k] = 1000000001 if isinstance(v, int) else "1000000001"
            elif k in ("uuid", "activityUUID"):
                state["seq"] += 1
                synth_uuid = f"00000000-0000-4000-8000-{state['seq']:012d}"
                out[k] = {"uuid": synth_uuid} if isinstance(v, dict) and "uuid" in v else synth_uuid
            elif k in ("ownerDisplayName", "ownerFullName"):
                out[k] = "Demo Athlete"
            elif k.startswith("ownerProfileImageUrl"):
                out[k] = ""
            elif k == "locationName" and isinstance(v, str):
                out[k] = "Sample City"
            elif k == "activityName" and isinstance(v, str):
                state["act_name_seq"] += 1
                out[k] = f"Sample Activity {state['act_name_seq']}"
            elif k == "activityId" and isinstance(v, (int, str)):
                state["act_id_seq"] += 1
                synth_id = 10000000000 + state["act_id_seq"]
                out[k] = synth_id if isinstance(v, int) else str(synth_id)
            elif k == "id" and isinstance(v, int) and not isinstance(v, bool):
                if EPOCH_MS_MIN <= v < EPOCH_MS_MAX:
                    out[k] = v + shift_days * MS_PER_DAY
                elif v >= 1_000_000:
                    state["seq"] += 1
                    out[k] = 1000000000 + state["seq"]
                else:
                    out[k] = v
            elif k == "startLatitude" and isinstance(v, (int, float)):
                out[k] = s_lat
            elif k == "startLongitude" and isinstance(v, (int, float)):
                out[k] = s_lon
            elif k == "endLatitude" and isinstance(v, (int, float)):
                out[k] = e_lat
            elif k == "endLongitude" and isinstance(v, (int, float)):
                out[k] = e_lon
            else:
                out[k] = _scrub(v, state, shift_days)
        return out
    if isinstance(obj, str):
        return _shift_string_date(obj, shift_days)
    if isinstance(obj, int) and not isinstance(obj, bool) and EPOCH_MS_MIN <= obj < EPOCH_MS_MAX:
        return obj + shift_days * MS_PER_DAY
    return obj


def _scrub_biometrics(filename: str, data: Any) -> Any:
    """Replace personal biometric values with clean synthetic round numbers (NEW-8)."""
    if filename == "fitnessage.json" and isinstance(data, dict):
        data["chronologicalAge"] = 30
        data["fitnessAge"] = 28.0
        data["achievableFitnessAge"] = 26.0
        data["previousFitnessAge"] = 28.5
        comps = data.get("components", {})
        if "vigorousDaysAvg" in comps:
            comps["vigorousDaysAvg"]["value"] = 2.0
            comps["vigorousDaysAvg"]["targetValue"] = 3
            comps["vigorousDaysAvg"]["potentialAge"] = 27.5
        if "rhr" in comps:
            comps["rhr"]["value"] = 60
        if "vigorousMinutesAvg" in comps:
            comps["vigorousMinutesAvg"]["value"] = 45.0
            comps["vigorousMinutesAvg"]["targetValue"] = 75
            comps["vigorousMinutesAvg"]["potentialAge"] = 27.0
        if "bmi" in comps:
            comps["bmi"]["value"] = 22.5
            comps["bmi"]["targetValue"] = 22.0
            comps["bmi"]["improvementValue"] = 0.5
            comps["bmi"]["potentialAge"] = 26.5
            comps["bmi"]["lastMeasurementDate"] = "2025-01-10"
        return data

    if filename == "usersummary.json" and isinstance(data, dict):
        data["totalSteps"] = 5000
        data["restingHeartRate"] = 60
        data["averageStressLevel"] = 25
        data["bodyBatteryMostRecentValue"] = 50
        return data

    if filename == "sleep.json" and isinstance(data, list):
        for item in data:
            if isinstance(item, dict):
                item["sleepTimeSeconds"] = 28800
                item["napTimeSeconds"] = 0
                scores = item.get("sleepScores")
                if isinstance(scores, dict) and isinstance(scores.get("overall"), dict):
                    scores["overall"]["value"] = 80
                    scores["overall"]["qualifierKey"] = "GOOD"
        return data

    if filename == "hrv.json" and isinstance(data, dict):
        for s in data.get("hrvSummaries", []):
            if isinstance(s, dict):
                s["weeklyAvg"] = 60
                s["lastNightAvg"] = 62
        return data

    if filename == "hrzones.json" and isinstance(data, list):
        for z in data:
            if isinstance(z, dict):
                z["restingHeartRateUsed"] = 60
                if z.get("lactateThresholdHeartRateUsed") not in (None, 0):
                    z["lactateThresholdHeartRateUsed"] = 165
                z["zone1Floor"] = 95
                z["zone2Floor"] = 115
                z["zone3Floor"] = 135
                z["zone4Floor"] = 155
                z["zone5Floor"] = 175
                z["maxHeartRateUsed"] = 190
        return data

    if filename == "spo2.json" and isinstance(data, dict):
        data["averageSpO2"] = 97.0
        data["lowestSpO2"] = 92
        data["lastSevenDaysAvgSpO2"] = 97.0
        data["latestSpO2"] = 98
        data["avgSleepSpO2"] = 97.0
        hourly = data.get("spO2HourlyAverages")
        if isinstance(hourly, list):
            for idx, entry in enumerate(hourly):
                if isinstance(entry, list) and len(entry) >= 3:
                    entry[1] = 96 + (idx % 3)
                    entry[2] = 250.0
        mon = data.get("monitoringEnvironmentValues")
        if isinstance(mon, list):
            for entry in mon:
                if isinstance(entry, list) and len(entry) >= 2:
                    entry[1] = 250.0
        return data

    if filename == "dailysummaries.json" and isinstance(data, dict):
        synth_steps = [8000, 5000, 7000, 4500]
        synth_bb_latest = [15, 16, 30, 47]  # Keep numeric sequence expected by GarminTest
        for idx, day in enumerate(data.get("data", [])):
            if not isinstance(day, dict):
                continue
            steps_val = synth_steps[idx % len(synth_steps)]
            mv = day.get("movement", {})
            if isinstance(mv.get("steps"), dict):
                mv["steps"]["goal"] = 10000
                mv["steps"]["value"] = steps_val
                mv["steps"]["distanceInMeters"] = float(int(steps_val * 0.75))
            if isinstance(mv.get("floorsAscended"), dict) and "value" in mv["floorsAscended"]:
                mv["floorsAscended"]["goal"] = 10
                mv["floorsAscended"]["value"] = 5
                mv["floorsAscended"]["distanceInMeters"] = 15.0
            if isinstance(mv.get("floorsDescended"), dict) and "value" in mv["floorsDescended"]:
                mv["floorsDescended"]["value"] = 5
                mv["floorsDescended"]["distanceInMeters"] = 15.0
            cal = day.get("calories")
            if isinstance(cal, dict):
                cal["burnedResting"] = 1800
                cal["burnedActive"] = 300
                cal["burnedTotal"] = 2100
            hr = day.get("heartRate")
            if isinstance(hr, dict):
                hr["minValue"] = 55
                hr["maxValue"] = 115
                hr["restingValue"] = 60
            im = day.get("intensityMinutes")
            if isinstance(im, dict):
                im["goal"] = 150
                im["moderate"] = 30 if idx == 0 else 0
                im["vigorous"] = 10 if idx == 0 else 0
            st = day.get("stress")
            if isinstance(st, dict):
                st["avgLevel"] = 25
                st["maxLevel"] = 70
                st["restProportion"] = 0.50
                st["activityProportion"] = 0.10
                st["uncategorizedProportion"] = 0.05
                st["lowStressProportion"] = 0.25
                st["mediumStressProportion"] = 0.08
                st["highStressProportion"] = 0.02
                st["qualifier"] = "balanced"
                st["totalDurationInMillis"] = 86400000
                st["restDurationInMillis"] = 43200000
                st["activityDurationInMillis"] = 8640000
                st["uncategorizedDurationInMillis"] = 4320000
                st["lowStressDurationInMillis"] = 21600000
                st["mediumStressDurationInMillis"] = 6912000
                st["highStressDurationInMillis"] = 1728000
            bb = day.get("bodyBattery")
            if isinstance(bb, dict):
                bb["minValue"] = 15 if idx == 0 else 20
                bb["maxValue"] = 80
                bb["chargedValue"] = 60
                bb["drainedValue"] = 50
                bb["latestValue"] = synth_bb_latest[idx % len(synth_bb_latest)]
                for ev in bb.get("activityEvents", []):
                    if isinstance(ev, dict):
                        ev["bodyBatteryImpact"] = 40 if ev.get("eventType") == "SLEEP" else -5
                        ev["durationInMillis"] = 28800000 if ev.get("eventType") == "SLEEP" else 3600000
            hyd = day.get("hydration")
            if isinstance(hyd, dict) and hyd:
                hyd["goalInMl"] = 2500
                hyd["goalInFractionalMl"] = 2500.0
                hyd["consumedInMl"] = 1500
                hyd["consumedInFractionalMl"] = 1500.0
            resp = day.get("respiration")
            if isinstance(resp, dict):
                resp["avgValue"] = 15
                resp["maxValue"] = 20
                resp["minValue"] = 10
                resp["latestValue"] = 15
            pox = day.get("pulseOx")
            if isinstance(pox, dict):
                pox["avgValue"] = 97
                pox["minValue"] = 92
                pox["latestValue"] = 98
                pox["avgAltitudeInMeters"] = 250.0
        return data

    if filename in ("activities.json", "sample_activities.json") and isinstance(data, list):
        for idx, act in enumerate(data):
            if not isinstance(act, dict):
                continue
            dist = float((idx + 1) * 5000)
            dur = float((idx + 1) * 1800)
            act["distance"] = dist
            act["duration"] = dur
            act["elapsedDuration"] = dur
            act["movingDuration"] = dur - 60.0
            act["elevationGain"] = 50.0 * (idx + 1)
            act["elevationLoss"] = 50.0 * (idx + 1)
            act["averageSpeed"] = round(dist / dur, 2)
            act["maxSpeed"] = round(dist / dur * 1.5, 2)
            act["calories"] = 300.0 * (idx + 1)
            act["bmrCalories"] = 80.0
            act["averageHR"] = 130.0 + idx * 5.0
            act["maxHR"] = 160.0 + idx * 5.0
            if "averageRunningCadenceInStepsPerMinute" in act:
                act["averageRunningCadenceInStepsPerMinute"] = 160.0
            if "maxRunningCadenceInStepsPerMinute" in act:
                act["maxRunningCadenceInStepsPerMinute"] = 180.0
            if "maxDoubleCadence" in act:
                act["maxDoubleCadence"] = 180.0
            if "steps" in act:
                act["steps"] = 4000 * (idx + 1)
            if "aerobicTrainingEffect" in act:
                act["aerobicTrainingEffect"] = 2.5
            if "anaerobicTrainingEffect" in act:
                act["anaerobicTrainingEffect"] = 0.5
            if "avgStrideLength" in act:
                act["avgStrideLength"] = 100.0
            if "minElevation" in act:
                act["minElevation"] = 200.0
            if "maxElevation" in act:
                act["maxElevation"] = 250.0
            if "avgElevation" in act:
                act["avgElevation"] = 225.0
            if "maxVerticalSpeed" in act:
                act["maxVerticalSpeed"] = 0.5
            if "waterEstimated" in act:
                act["waterEstimated"] = 400.0
            if "activityTrainingLoad" in act:
                act["activityTrainingLoad"] = 50.0
            if "minActivityLapDuration" in act:
                act["minActivityLapDuration"] = 300.0
            if "moderateIntensityMinutes" in act:
                act["moderateIntensityMinutes"] = 20
            if "vigorousIntensityMinutes" in act:
                act["vigorousIntensityMinutes"] = 10
            if "differenceBodyBattery" in act:
                act["differenceBodyBattery"] = -5
            for z in range(1, 6):
                if f"hrTimeInZone_{z}" in act:
                    act[f"hrTimeInZone_{z}"] = 300.0 if z <= 3 else 0.0
                if f"powerTimeInZone_{z}" in act:
                    act[f"powerTimeInZone_{z}"] = 300.0 if z <= 3 else 0.0
            for opt_key, synth_val in (
                ("avgPower", 220.0),
                ("maxPower", 350.0),
                ("normPower", 230.0),
                ("avgGradeAdjustedSpeed", 3.0),
                ("avgGroundContactTime", 240.0),
                ("avgVerticalOscillation", 8.0),
                ("avgVerticalRatio", 7.5),
                ("fastestSplit_1000", 240.0),
                ("fastestSplit_1609", 400.0),
                ("fastestSplit_5000", 1300.0),
            ):
                if opt_key in act:
                    act[opt_key] = synth_val
        return data

    return data


def anonymize_file(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    dates: list[date] = []
    _collect_dates(data, dates)
    shift_days = (TARGET_ANCHOR_DATE - max(dates)).days if dates else 0
    state = {"seq": 0, "act_id_seq": 0, "act_name_seq": 0, "coord_idx": 0}
    scrubbed = _scrub(data, state, shift_days)
    scrubbed = _scrub_biometrics(path.name, scrubbed)
    path.write_text(json.dumps(scrubbed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Anonymized {path.relative_to(REPO_ROOT)} (shift_days={shift_days})")


def main() -> None:
    targets = sorted(SAMPLES_DIR.glob("*.json"))
    if DEBUG_SAMPLE_ACTIVITIES.exists():
        targets.append(DEBUG_SAMPLE_ACTIVITIES)
    for path in targets:
        anonymize_file(path)


if __name__ == "__main__":
    main()
