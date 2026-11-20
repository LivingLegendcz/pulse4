# Privacy policy: open legal and distribution items

Status: **all items below are UNVERIFIED, unless the status column says otherwise.** They were written from general knowledge while drafting [PRIVACY.md](PRIVACY.md) and have not been checked against the official texts. No web sources were consulted for this file; the URLs are starting points to look up, not citations. When an item is checked, record the date, the exact source URL and the outcome next to it.

Until these are resolved, the policy and the app must **not** be described as compliant with the GDPR, COPPA, the Google Play Families Policy or any other law or store policy.

## 1. Google Play

| # | Item | What to verify | Where to look (starting point, not consulted) | Status |
|---|---|---|---|---|
| 1.1 | Target audience and content declaration | The app is meant to include children from 6 years. Selecting a child audience in Play Console triggers the Families Policy requirements. Decide the declared age groups and whether the app qualifies for the "Designed for Families" programme or must be declared as mixed audience | Play Console Help: Target audience and content; Play Families Policy | UNVERIFIED |
| 1.2 | Families Policy requirements on SDKs and services | The app uses Google Play services (code scanner), Play in-app update, MapLibre and third-party tile servers. Check whether any of these are allowed or restricted for apps in the Families programme, and whether network calls to third-party map servers are acceptable for a child audience | Play Families Policy; Families self-certified ads SDK program (not relevant as there are no ads, but confirm) | UNVERIFIED |
| 1.3 | Data safety form | Declare data collected and shared accurately. Decide how to classify data that is processed only on-device, data sent to Garmin, Withings and map providers, and peer-to-peer sharing to other users. Confirm what counts as "collected" when the developer never receives it. Declare health and fitness data types handled | Play Console: App content, Data safety; Data safety help article | UNVERIFIED |
| 1.4 | Health apps declaration and Health Connect | Health Connect read permissions (weight, body fat, read in background) require a permissions declaration, an in-app rationale screen and a privacy policy link. Verify the extra rules for background read and for health apps | Play Console: Health apps declaration; Health Connect permission policy | UNVERIFIED |
| 1.5 | Foreground service type and boot receiver | The app uses a `remoteMessaging` foreground service, `RECEIVE_BOOT_COMPLETED` and a battery-optimisation exemption request. Verify the use is allowed and declare the foreground service use | Play Console: foreground service declaration; policy on `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | UNVERIFIED |
| 1.6 | Privacy policy requirements | The policy URL must be public and linked in the app. Verify the exact required content (data accessed, collected, shared, retention and deletion, developer contact) and whether in-app and store-listing links are both needed | Play User Data policy | UNVERIFIED |
| 1.7 | Account deletion requirement | Play requires a deletion route for apps that let users create an account. Pulse4 has no accounts of its own; confirm that no account-deletion declaration is needed | Play account deletion requirements | UNVERIFIED |
| 1.8 | Unofficial integrations and policy on impersonation or deceptive behaviour | Garmin Connect sign-in uses Garmin Connect mobile client identifiers and a browser-style user agent. Withings sign-in imitates the Withings website. Check Google Play's rules on such behaviour in addition to the services' own terms (items 3.1 and 3.2) | Play Developer Program Policy | UNVERIFIED |

## 2. Data protection law

| # | Item | What to verify | Where to look | Status |
|---|---|---|---|---|
| 2.1 | Who is the "controller" of a serverless app | The provider never receives health data. Decide whether the provider is a controller, a joint controller, or outside the controller role for on-device processing. Consider the end user as controller of their own data (household exemption) and the effect of the app facilitating sharing with others. Decide how this is to be stated in the policy | GDPR Articles 4, 24, 26; Recital 18; guidance from EDPB and the Czech data-protection authority (UOOU) | UNVERIFIED |
| 2.2 | Health data as special-category data | Data such as ECG, heart rhythm, body composition and sleep is likely health data under Article 9. Check the legal basis available for each processing step, and whether anything the provider does counts as processing at all | GDPR Articles 6 and 9 | UNVERIFIED |
| 2.3 | Children's consent, Article 8 | Check the digital-consent age in the Czech Republic and in every country where the app will be offered (it can be 13 to 16 under the GDPR). Check which processing needs consent at all, and whether parental authorisation via a paired device meets "reasonable efforts to verify" | GDPR Article 8; national implementing laws; Czech Act No. 110/2019 Coll. | UNVERIFIED |
| 2.4 | Information for children | Whether the policy needs a child-friendly summary for users from 6 years, and in what form | GDPR Article 12 and Recital 58; guidance on children's data | UNVERIFIED |
| 2.5 | Data subject rights wording | Check that "exercised locally" is acceptable when no provider-held data exists, and whether a statement about the supervisory authority and about international transfers (Garmin, Withings, Esri, relays outside the EEA) is needed | GDPR Articles 12 to 22, 44 to 49 | UNVERIFIED |
| 2.6 | Data protection impact assessment | Whether a DPIA is advisable given health data and children | GDPR Article 35; supervisory authority lists | UNVERIFIED |
| 2.7 | Data Act, ePrivacy and other regimes | Whether other rules apply (for example for terminal-equipment access or for health apps and medical-device classification of ECG display) | ePrivacy Directive Art. 5(3); EU MDR guidance on software | UNVERIFIED |
| 2.8 | COPPA and other non-EU laws | If the app is offered in the United States or other regions, verify children's privacy laws (COPPA, state laws) and the rules for "actual knowledge" and for mixed audiences. Verify UK and other regional rules | FTC COPPA Rule and FAQ; regional laws | UNVERIFIED |

## 3. Third-party terms

| # | Item | What to verify | Status |
|---|---|---|---|
| 3.1 | Garmin | Terms for the Garmin Connect Developer Program and the Personal API key. Whether the Garmin Connect sign-in route (SSO, DI OAuth, mobile client identifiers, workout write access) is permitted for a distributed app, and whether Garmin permits access by or for children. Whether the Personal API is allowed for apps used by many people | UNVERIFIED |
| 3.2 | Withings | Terms of use for the Withings web sign-in imitation, session and password handling, ECG and family-profile data, and the OAuth route with a user's own developer credentials. Withings' rules on access to child profiles | UNVERIFIED |
| 3.3 | Esri / ArcGIS tiles (the default map) | Whether using the public ArcGIS Online tile endpoints for Dark Canvas and World Imagery from a distributed app is allowed without a licence or API key, the attribution required, and any usage limits | UNVERIFIED |
| 3.4 | OpenStreetMap tile servers | Whether the OSMF tile usage policy allows use from a distributed app (user agent, caching, heavy-use prohibitions). Attribution requirements | UNVERIFIED |
| 3.5 | OpenTopoMap, CyclOSM, CARTO | Usage terms and limits for each public tile server, including the CARTO fallback used when the custom map URL is empty | UNVERIFIED |
| 3.6 | MapLibre SDK network behaviour | Confirm by traffic capture and by SDK documentation that the SDK sends no telemetry or other requests beyond tile and style fetches. The policy currently states that tile servers see IP, request data and requested tiles | UNVERIFIED |
| 3.7 | Reticulum public nodes | Terms or acceptable-use rules for the public nodes offered in the first-run guide (Dublin Connect, BetweenTheBorders) and whether the operators agree to being listed | UNVERIFIED |
| 3.8 | Google Play services components | Privacy terms for Google code scanner and Play in-app update, and what data they send to Google | UNVERIFIED |

## 4. Parental verification and guardian model

| # | Item | What to verify | Status |
|---|---|---|---|
| 4.1 | Is parental verification via a paired device sufficient | The design verifies a guardian by a cryptographic link between two Pulse4 phones, with no identity check. Check whether this meets any legal or store expectation of verifiable parental consent, or whether it is only a household control that must not be presented as legal consent | UNVERIFIED |
| 4.2 | Self-appointed guardian | A child could pair a second phone they control and accept it as the first "parent", so the child's own device unlocks the app. A guardian added later needs an existing guardian's signed approval (section 8, "Several guardians"), so a fake guardian added after a real one is blocked; the open case is only a fake first guardian. This is the same contact-trust problem as pairing any wrong device, covered by general contact verification (QR / fingerprint pairing), not a separate item. The policy (section 8, "Limits") already states that the app cannot tell whether the person who accepts the link is the parent. Possible mitigation to check: Google Family Link approval of app installs (UNVERIFIED: not checked against Google's documentation) | ACCEPTED RESIDUAL RISK, low likelihood (product owner decision, 2026-11-20). Family Link mitigation: UNVERIFIED, needs checking against Google's documentation |
| 4.3 | Reset by clearing data or reinstalling | Clearing app data returns to the age question, so a child can re-declare as an adult. Decide whether this limitation is acceptable for store and legal purposes, and how prominent it must be | UNVERIFIED |
| 4.4 | Several guardians | Any single guardian can approve. Any guardian can revoke an individual approval, but cannot revoke the approval that added another guardian and cannot remove another guardian's link; a guardian leaves only by removing their own link. Check implications where parents disagree, are separated, or where a person other than a legal guardian is linked | UNVERIFIED |
| 4.5 | Self-declared age | The age question is self-declared and unverified. Check whether this is acceptable for the declared target audience, and whether a neutral age screen is expected | UNVERIFIED |
| 4.6 | Blocking the app for children | A child cannot use any local feature until a parent accepts. Check this against accessibility and Families expectations, and decide what a child without a parent device should see | UNVERIFIED |
| 4.7 | Recall limits | Data already delivered to other phones cannot be recalled. Check whether the wording in sections 7 and 8 of the policy is sufficient for children's data | UNVERIFIED |
| 4.8 | Local enforcement | Enforcement is only on devices and does not resist a rooted phone or modified app. Check whether this needs to be stated in store declarations | UNVERIFIED |

## 5. Facts to re-verify against code before publishing

These are code facts the policy depends on. They were checked on commit `0e41ed6` (version 0.2.3) and can change:

- The default map style is "Dark Canvas" and it loads Esri tiles (the enum name `CARTO_DARK` is misleading). A blank custom map URL falls back to CARTO.
- No gateway or propagation node is preconfigured. `Settings.kt` has an outdated comment that mentions a preset default gateway; the code default is empty.
- `docs/garmin-endpoints.md` says all Garmin endpoints are unverified placeholders, but `garmin_endpoints.json` now has `verified: true`; the document is out of date.
- Section 8 of the policy (children and guardians, including multiple guardians) describes a feature in development. Re-verify every statement once implemented, in particular: first-run blocking, per-request approvals, signed revocation, last-guardian removal behaviour, the rule that no guardian can remove another guardian's link, the release rules for returning to adult (allowed for the child only before a parent accepts the link), exclusion from backups, and the reset-on-clear-data behaviour.
- The Withings password is remembered by default; confirm this default before publishing, and consider whether a default-off setting is preferable for a child audience.
- The policy states that release logs are redacted and that the app has no analytics, crash reporting or advertising. Re-check dependencies on every release.
