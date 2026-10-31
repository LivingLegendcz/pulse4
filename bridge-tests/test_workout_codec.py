"""Test překladu tréninku: strukturální shoda s modely `garminconnect.workout` a bezeztrátový zpětný překlad."""
import os, sys, json, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "python"))
from pulse4_garmin import workout_codec as c

RUN = {
    "id": "w1", "name": "Tempový běh", "sport": "RUNNING", "ownerId": "josef",
    "nodes": [
        {"type": "step", "stepType": "WARMUP", "duration": {"type": "time", "seconds": 600}, "target": {"type": "none"}},
        {"type": "repeat", "count": 4, "steps": [
            {"type": "step", "stepType": "WORK", "duration": {"type": "distance", "meters": 1000}, "target": {"type": "pace", "minSecPerKm": 270, "maxSecPerKm": 285}},
            {"type": "step", "stepType": "RECOVERY", "duration": {"type": "time", "seconds": 120}, "target": {"type": "hr", "minBpm": 110, "maxBpm": 140}},
        ]},
        {"type": "step", "stepType": "COOLDOWN", "duration": {"type": "lap"}, "target": {"type": "none"}},
    ],
}
BIKE = {
    "id": "b1", "name": "Prahy", "sport": "CYCLING", "ownerId": "josef",
    "nodes": [
        {"type": "step", "stepType": "WORK", "duration": {"type": "time", "seconds": 1200}, "target": {"type": "power", "minW": 220, "maxW": 250}},
        {"type": "step", "stepType": "REST", "duration": {"type": "time", "seconds": 300}, "target": {"type": "cadence", "min": 85, "max": 95}},
    ],
}


class Codec(unittest.TestCase):
    def test_roundtrip_is_lossless(self):
        for w in (RUN, BIKE):
            g = c.to_garmin(w, "[p4-abc123]")
            back = c.from_garmin(g, w["id"], w["ownerId"], marker_pattern=r"\s*\[p4-[0-9a-f]+\]")
            self.assertEqual(back, w)

    def test_marker_goes_into_name_and_step_order_is_sequential(self):
        g = c.to_garmin(RUN, "[p4-abc123]")
        self.assertEqual(g["workoutName"], "Tempový běh [p4-abc123]")
        steps = g["workoutSegments"][0]["workoutSteps"]
        self.assertEqual([s["stepOrder"] for s in steps], [1, 2, 5])
        self.assertEqual([x["stepOrder"] for x in steps[1]["workoutSteps"]], [3, 4])
        rep = steps[1]
        self.assertEqual((rep["numberOfIterations"], rep["endConditionValue"], rep["stepType"]["stepTypeKey"]), (4, 4.0, "repeat"))

    def test_pace_is_stored_as_speed_slow_first(self):
        s = c.to_garmin(RUN)["workoutSegments"][0]["workoutSteps"][1]["workoutSteps"][0]
        self.assertAlmostEqual(s["targetValueOne"], 1000 / 285); self.assertAlmostEqual(s["targetValueTwo"], 1000 / 270)
        self.assertEqual(s["targetType"]["workoutTargetTypeKey"], "pace.zone")

    def test_lap_button_has_no_value(self):
        last = c.to_garmin(RUN)["workoutSegments"][0]["workoutSteps"][2]
        self.assertEqual(last["endCondition"]["conditionTypeKey"], "lap.button"); self.assertNotIn("endConditionValue", last)

    def test_unknown_remote_shapes_are_rejected_not_guessed(self):
        g = c.to_garmin(RUN)
        g["sportType"]["sportTypeKey"] = "swimming"
        with self.assertRaises(ValueError): c.from_garmin(g)
        g = c.to_garmin(RUN); g["workoutSegments"][0]["workoutSteps"][0]["endCondition"]["conditionTypeKey"] = "calories"
        with self.assertRaises(ValueError): c.from_garmin(g)

    def test_matches_library_models(self):
        try:
            from garminconnect.workout import RunningWorkout, CyclingWorkout
        except Exception as e:  # pydantic nemusí být nainstalován
            self.skipTest("garminconnect.workout nedostupný: %r" % (e,))
        RunningWorkout(**c.to_garmin(RUN)); CyclingWorkout(**c.to_garmin(BIKE))
        # model nesmí zahodit naše pole
        d = RunningWorkout(**c.to_garmin(RUN)).to_dict()
        self.assertEqual(d["workoutSegments"][0]["workoutSteps"][1]["numberOfIterations"], 4)



class Calendar(unittest.TestCase):
    def test_normalizes_known_shapes_and_fails_loudly_on_unknown(self):
        from pulse4_garmin import session as s
        got = s.normalize_calendar([
            {"workoutScheduleId": 11, "workoutId": 5, "date": "2026-11-02"},
            {"scheduleId": 12, "workout": {"workoutId": 6}, "calendarDate": "2026-11-02T00:00:00"},
        ])
        self.assertEqual(got, [{"calendarId": "11", "workoutId": "5", "date": "2026-11-02"},
                               {"calendarId": "12", "workoutId": "6", "date": "2026-11-02"}])
        with self.assertRaises(ValueError):
            s.normalize_calendar([{"foo": 1}])

    def test_module_imports_without_network_and_refuses_calls_without_session(self):
        from pulse4_garmin import session as s
        s.logout()
        with self.assertRaises(RuntimeError): s.list_workouts()
        with self.assertRaises(RuntimeError): s.resume_mfa("123456")


if __name__ == "__main__":
    unittest.main()
