# Pulse4 Privacy Policy

*Czech version: [PRIVACY.cs.md](PRIVACY.cs.md)*

*Status: draft for publication. Last updated: 20 November 2026. Describes the app as of version 0.2.3, plus the child and parental-approval features described in section 8, which are marked as in development.*

Pulse4 is a privacy-first Android app that gathers your fitness and health data from Garmin, Withings and Health Connect in one place. It has **no server of its own, no user accounts, no advertising and no analytics**. This policy explains what the app does with your data, in plain terms and without promising more than the code does.

## 1. In short

- Your health and fitness data is stored **only on your phone**, in an encrypted database. The developer cannot see it and has no copy of it.
- The app talks directly from your phone to **Garmin** and **Withings** (with your own account) to download your data, and to **map tile servers** when you open a route map. These third parties see the technical data described in section 5.
- Sharing with family or friends ("circles") is **optional, off by default and peer to peer**. Messages are end-to-end encrypted. Only the contacts and data fields you choose receive anything.
- The app contains **no advertising, analytics, crash-reporting or tracking components** and never sells data.
- For children, sharing and joining circles require **approval from a parent's device** (section 8, in development).
- Once data has been delivered to someone else's phone, the app **cannot guarantee it can be taken back** (section 7).

## 2. Who provides the app and how to contact us

Provider: **[PROVIDER NAME AND POSTAL ADDRESS]**

Contact for privacy questions: **pepe.kocourek@gmail.com**

Because Pulse4 has no backend, the provider does not receive, store or process your health data and cannot look up, change or delete it for you. Questions about how the app works, or about this policy, can be sent to the contact above.

## 3. What data the app handles

All of the following is processed on your phone. Nothing in this table is sent to the provider.

| Data | Where it comes from | Can it be shared with circle members? |
|---|---|---|
| Body composition: weight, body fat, fat-free mass, muscle mass, bone mass, body water, visceral fat, vascular age, pulse-wave velocity, basal metabolic rate, metabolic age | Withings, Health Connect (weight and body fat only), manual entry, CSV import | Weight and body fat only |
| ECG recordings and derived values (heart rate, QTc, QRS, atrial-fibrillation classification) | Withings | No |
| Heart rate, resting heart rate, heart-rate variability (HRV), respiration, SpO2 | Garmin, Withings | Heart rate and HRV: yes. Others: no |
| Sleep (duration, stages, score, naps) | Garmin | Yes |
| Body Battery, stress, steps | Garmin | Body Battery: yes. Stress and steps: no |
| Activities: sport, time, distance, laps, charts and **GPS tracks** | Garmin (including original FIT files), FIT import | Yes, in several detail levels, optionally with a privacy zone that hides the area around a place such as your home |
| Training plans and workouts | Created in the app | Yes, with a view, copy, propose or edit right |
| Withings family profiles (names, birth dates) and your Withings e-mail address | Your Withings account | No (see below for the parent-to-child weight feature) |
| Your display name, your contacts (display name, public key, address) and your circle memberships | Entered by you, exchanged by QR code | Display name is shared with contacts you pair with |
| Sharing settings, family links, guardian approvals (section 8) | Created in the app | Exchanged with the other party where needed |
| Garmin and Withings credentials and sessions | Entered by you | Never |

The app does **not** request location permission and does not read your phone's location. The GPS tracks come from your Garmin activities. The Android location permissions that the map library would normally add are explicitly removed in the app manifest.

## 4. Where data is stored and how it is protected

- **Database.** Health data, settings, contacts and sharing records live in a local database encrypted with SQLCipher (AES-256). The database key is 32 random bytes generated on your phone and sealed with a key held in the **Android Keystore** (hardware-backed where your phone supports it).
- **Secrets.** Garmin tokens or API key, the Withings session (cookies, e-mail, family profile list), the optional remembered Withings password and your private Reticulum identity key are kept in a separate store that is also sealed with an Android Keystore key. They are never written to the database, the backup file or the logs.
- **Original activity files.** Downloaded Garmin FIT files are stored on the phone as individually encrypted files (sealed with the Keystore key).
- **Not in Android backups.** The app sets `allowBackup=false`, disables full-backup content and excludes all files, databases and preferences from both cloud backup and device-to-device transfer. Your data is therefore not copied to Google Drive or transferred to a new phone by Android. Moving to a new phone requires the app's own encrypted backup (section 9).
- **Network.** The app does not allow unencrypted (HTTP) internet traffic.

Encryption protects the data on a lost or switched-off phone. It does not protect against someone who can unlock your phone, against malware running as your user, or against a rooted or compromised device.

## 5. What leaves your phone, and to whom

### 5.1 Garmin

If you connect Garmin, the app contacts Garmin servers directly from your phone, using your own credentials, to download your data (sleep, heart rate, HRV, Body Battery, stress, steps, activities and original FIT files). Two connection methods exist:

- **Garmin Personal API key** (`personal.connectapi.garmin.com`): you enter an API key issued to you by Garmin.
- **Garmin Connect sign-in** (`sso.garmin.com`, `diauth.garmin.com`, `connectapi.garmin.com`): you sign in with your Garmin e-mail and password (and a one-time code if you use multi-factor authentication). The app uses these only to obtain tokens, does not store the password, and keeps the tokens in the Keystore-sealed store. This method is also used when you choose to send a training workout from Pulse4 to your Garmin account, which creates or updates a workout in your Garmin Connect account.

Garmin receives the requests the app makes, your IP address and the usual technical request data. Garmin's own privacy policy applies to what Garmin does with it. The Garmin connection relies on interfaces that Garmin does not fully document for third-party apps. Pulse4 is not affiliated with or endorsed by Garmin.

### 5.2 Withings

If you connect Withings, the app contacts Withings servers directly (`account.withings.com`, `scalews.withings.com`, `app.withings.com`; and `wbsapi.withings.net` if you use the optional OAuth route with your own developer credentials) to download measurements, including body composition, heart rate and ECG, and the list of profiles on your account.

- You sign in with a Withings e-mail code or e-mail and password. The app keeps the resulting session cookies and your e-mail in the Keystore-sealed store.
- If "Remember password for automatic renewal" is on (it is on by default), the password is also kept in the Keystore-sealed store on the phone and used only to re-establish the session when it expires (at most about once per 6 hours in the background). You can turn this off in the login dialog; the password is deleted when you disconnect or when Withings rejects it.
- A Withings account can hold family profiles. For a child profile, a parent can connect a second session and, if the parent turns it on, the app forwards that child's weight to the child's own Pulse4 over the encrypted peer-to-peer channel (section 6).

Withings receives your requests, your IP address and the usual technical request data. Withings' privacy policy applies to what Withings does with it. The Withings connection by e-mail sign-in imitates Withings' web sign-in and is not an officially supported integration. Pulse4 is not affiliated with or endorsed by Withings.

### 5.3 Health Connect

If you grant permission, the app reads **weight and body fat** from Android Health Connect (including in the background if you grant that separate permission). It does not write anything to Health Connect. Health Connect data stays on your phone and is handled as described in section 4.

### 5.4 Map tile providers

When you open a route map, the map library downloads map tiles over the internet. The tile server sees your **IP address**, the technical request data and **which tiles you request, which reveals the approximate area you are looking at**. It does not receive your exact GPS track, which is drawn on the phone. The provider depends on the map style selected in settings:

| Map style in the app | Tile server contacted |
|---|---|
| Dark Canvas (**the default**) | Esri / ArcGIS Online (`services.arcgisonline.com`) |
| Esri World Imagery (satellite) | Esri / ArcGIS Online (`server.arcgisonline.com`) |
| OpenStreetMap | OpenStreetMap tile servers (`tile.openstreetmap.org`) |
| OpenTopoMap | OpenTopoMap (`*.tile.opentopomap.org`) |
| CyclOSM | CyclOSM (`*.tile-cyclosm.openstreetmap.fr`) |
| Custom MapLibre URL | The server you enter. If you leave the field empty, tiles come from CARTO (`a.basemaps.cartocdn.com`) |
| No map background (offline canvas) | None. The route is drawn without any network request |

Each provider has its own privacy policy. If you do not want any map requests, choose "No map background".

### 5.5 Reticulum / LXMF peer-to-peer network

Sharing between Pulse4 users uses the Reticulum network and its LXMF messaging layer. There is no central Pulse4 server. How this works in practice:

- **Your address.** Your phone creates a long-term cryptographic identity. Your network address is derived from its public key; it contains no phone number or e-mail. When connected to a gateway, the app publishes an announcement containing this address and your public key, with empty application data (your name is not included). People you pair with by QR code also receive your display name.
- **Nearby (same Wi-Fi).** If networking is not set to manual, the app advertises itself on the local network (service type `_pulse4._tcp`, containing your pseudonymous address) and discovers other Pulse4 phones there. Other devices on the same Wi-Fi network can see that advertisement. Messages exchanged this way are the same end-to-end encrypted messages as below.
- **Gateway (optional).** To reach contacts who are not on the same Wi-Fi, you can connect to a Reticulum gateway node (a TCP connection). **No gateway is preconfigured.** The first-run guide offers a short list of public nodes (currently Dublin Connect by the Reticulum project and BetweenTheBorders), lets you type your own host, scan a configuration QR code, or continue with local Wi-Fi only. Gateways are run by third parties, not by the Pulse4 provider.
- **Propagation node (optional).** To deliver messages while the recipient is offline, you can enter an LXMF propagation node (a mailbox). **None is preconfigured.** Messages are stored there until the recipient collects them. The app gives up on a stored message after about 14 days; the node operator's own retention rules apply to the copy on the node.
- **What a gateway or propagation node can see.** Your IP address, when and how often you connect, the pseudonymous destination address of each message, the approximate size and the timing. They can also see the announcements (address and public key) that pass through them. **They cannot read the contents.** Message contents are encrypted end to end (E2EE v3: per-message ephemeral X25519 key agreement and AES-256-GCM, with the sender, recipient and signature hidden inside the ciphertext and messages padded to blocks of 256 bytes), and the Reticulum/LXMF layer adds its own encryption to the recipient. A relay can infer patterns, for example that two addresses communicate regularly. Anyone who knows which address belongs to which person can draw the same conclusion.
- **Recipients.** Your contact receives exactly the fields you have granted, in a newly built sanitised form (not a copy of your records). Activity routes can hide a privacy zone; precise start time is a separate option.

You can run the app in **local-only** mode (no gateway connection) or fully manual mode.

### 5.6 Google Play and Google Play services

The app is distributed through Google Play. Google processes information about the installation, updates and (if you opted in on your device) usage and diagnostics under its own policies; the Pulse4 provider receives only the aggregate statistics that Google Play Console provides to developers. The app uses two Google components:

- **Google code scanner** (Google Play services) to scan pairing QR codes. It is a system component and does not require camera permission for the app.
- **Google Play in-app update** to check for and install new versions of the app.

### 5.7 What the app does not do

- It has no advertising, no analytics, no crash-reporting service (no Firebase, Crashlytics or similar) and no "phone home" to the provider.
- It does not sell, rent or trade data.
- It does not use your location and does not request location permission.
- It does not link data from different services into a profile held anywhere outside your phone.

## 6. Sharing with family and circles

- **Off by default.** Nothing is shared until you create a share grant. You choose recipients, the data fields (for example sleep duration only, or sleep with stages), the time scope (once, from a date, or continuously from today) and for activities the level of detail, a privacy zone and whether exact start time is included. Continuous sharing never reveals history from before you created it.
- **Circles.** A circle is a named group with an admin. Being in a circle does not by itself give anyone access to your data. Only a grant that names you as a recipient does. Circle membership messages are exchanged between members' phones.
- **Parent-to-child weight.** If a parent has connected a Withings child profile and turned on forwarding, that child's weight is sent to the child's phone over the same encrypted channel. The child's phone stores it only if the child has turned on receiving it.
- **Training plans.** Sharing a plan lets a recipient view, copy, propose changes to or edit it, according to the right you give.
- **Narrowing is immediate.** You can stop or reduce sharing at any time. Queued messages for a stopped grant are cancelled before they leave your phone.

## 7. Limits of taking data back

When you stop sharing, remove someone from a circle, or (for a child) a parent withdraws an approval, the app stops sending, cancels what is still queued and asks the recipient's app to remove the data it already received. Be aware that:

- Data **already delivered** to a recipient's phone cannot be forcibly erased. The request to remove it is carried out by the recipient's app and cannot be enforced. A recipient could also have copied, photographed or screenshotted what they saw.
- A recipient who is **offline** learns about the change only when they next connect.
- A copy of an undelivered message may remain on a **propagation node** operated by a third party until it expires or is collected.
- The app tells you about these limits where you revoke sharing.

## 8. Children and parental approval

*This section applies from version [X.Y] (in development) and describes the intended behaviour. Until that version is released, the app does not yet ask for an age and does not enforce guardian approval.*

Pulse4 is designed so that it can also be used by children from 6 years of age with a parent's involvement. These are the rules the app applies.

**Age question.** When the app is first opened after installation, it asks whether the user is a child (under 18) or an adult. This is a **self-declaration**. The app cannot verify age and no information about it is sent to the provider.

**Adult.** The app works normally.

**Child.** The app marks this phone as belonging to a child immediately. The first required step is to **add a parent**: the child pairs with the parent's phone (by QR code) and sends a family-link request. **Until the parent has accepted this request on the parent's device, the app is blocked.** In that state the child can only pair, send or resend the request, cancel it, or change the answer back to adult (possible only until a parent has accepted the link, see below). The child cannot sign in to Garmin or Withings, see metrics or join circles.

**More than one parent or guardian.** A child can be linked to more than one parent or guardian (for example both parents; the app sets a small upper limit). The app unlocks as soon as **one** guardian has accepted the link. Adding a further guardian later needs two things: the new guardian accepts the child's link request, **and** an existing guardian approves adding that person with a signed approval. This prevents a child from appointing a "guardian" of their own to approve things. Every request for approval is sent to **all** linked guardians. **Any one** of them can approve or reject it. The first valid decision counts, and the other guardians then see that it has already been decided and by whom.

**Parent approves each step separately.** After the link is accepted:

- Every **circle the child wants to join or create** is a separate request that the parent approves or rejects. The parent sees the circle's name and admin and what is being asked.
- Every **new or widened data sharing** (new recipient, additional data fields, additional plan rights, a wider time scope or a new data source) is a separate request that the parent approves or rejects. The parent sees the recipients, the fields and the scope, compared with what was approved before.
- The child's phone does not send circle-membership messages or start the sharing until the approval has arrived.
- **Narrowing or switching off sharing never needs approval.**

**How approval is checked.** An approval is a digitally signed message created on the parent's phone with the parent's private key. The child's phone verifies the signature against the parent's public key (the one it received when the link was set up), checks that the approval answers one of its own outstanding requests, has not expired and has not been used before, and that it matches exactly what was requested. Each approval can be used once and has an expiry time. The approval is sent over the same end-to-end encrypted channel.

**A parent can withdraw at any time.** Any linked guardian can revoke a specific data-sharing approval or a circle approval (the child's phone then leaves that circle and revokes its sharing to it), including approvals that another guardian gave. The approval that added another guardian cannot be revoked by a guardian. A guardian leaves the family only by removing their own family link; no guardian can remove another guardian's link. Approvals belong to the family: they stay valid when one guardian leaves, as long as at least one guardian remains linked. The revocation is signed and is applied on the child's phone as soon as it is received. When the **last** guardian link is removed, all approvals are revoked and the app returns to the blocked "waiting for parent" state. A child can also remove a family link from its side, with the same effect. A child can change the answer back to adult on this phone only until a parent has accepted the link. After that, returning to adult is possible only with a signed release from a linked guardian.

**What is stored and where.** The age declaration, the family link and the approvals are stored **only on the phone, in the encrypted database**. They are **not included in the app's backup** and are not sent to the provider. They are visible to the other parties in the link only to the extent needed for it to work (for example each guardian sees the child's requests, and the decisions and approvals of the other guardians).

**Limits you should know about.**

- Enforcement happens **on the devices**. There is no server that can verify or enforce it. Someone with full control of a phone (for example a rooted phone or a modified app) can bypass it.
- **Clearing the app's data or reinstalling resets the age question.** A child who does this and answers "adult" removes the restrictions on that phone. Parents should keep that in mind.
- The app cannot tell whether the person who accepts the family link really is the child's parent or legal guardian. Parents should pair in person with their own child. Any single linked guardian can approve, so every guardian should be someone you trust.
- Data **already delivered** to other phones cannot always be recalled (section 7).
- If you have a child profile on Withings or Garmin, those services have their own age rules and terms. Parents are responsible for choosing whether to connect them.

## 9. Backup and export

The app can export an **encrypted backup file** that you create and save yourself using Android's file picker, to any location you choose.

- It contains: your measurements (observations), your activities, GPS tracks and training plans.
- It does **not** contain: Garmin or Withings tokens, passwords, your Reticulum private identity, your contacts, sharing grants, family links, guardian approvals or the age declaration.
- It is encrypted (AES-256-GCM) with a key derived from a passphrase you choose (at least 8 characters) using PBKDF2. If you lose the passphrase, the file cannot be opened. The provider cannot help recover it.
- Restoring on a new phone creates a new network identity: contacts and sharing have to be set up again, and a child has to go through the parent pairing again.
- If you save the file in a cloud-synced folder, that provider will hold the (encrypted) file.

## 10. Logging

- **Release builds** (the version from Google Play) keep logging to a minimum: informational and debug messages are suppressed, warning and error messages are cleaned of e-mail addresses, tokens, cookies, session identifiers, passwords and host names with ports, shortened, and stripped of stack traces. Logs stay on the phone in Android's system log and are not sent anywhere by the app.
- **Debug builds** (developer builds that are not distributed through Google Play) log more verbosely without this cleaning and include a developer command receiver. Do not use debug builds with real data on a phone you share with others.
- The Withings login diagnostic screen shows a redacted record of recent sign-in attempts (up to 30 entries). It is stored locally and is copied out only if you press the copy button.

## 11. Retention and deletion

The provider holds no data about you, so retention is under your control.

| What you do | What happens |
|---|---|
| **Disconnect Garmin or Withings** in the app | The related tokens, sessions and remembered password are deleted from the phone. Already downloaded measurements stay in the app. |
| **Remove a child profile / revoke sharing / leave a circle** | Forwarding or sharing stops. See section 7 for what cannot be recalled. |
| **Clear the app's storage** in Android settings, or **uninstall** the app | The database, secrets, original activity files, settings, identity key, age declaration and approvals are deleted from the phone. Data already delivered to other phones, and data at Garmin, Withings, relays and map providers, is not affected. |
| **Delete the backup file** | You delete it yourself where you saved it. The app cannot reach it afterwards. |

Data sent to a propagation node is kept by that node under its operator's rules. The app stops retrying undelivered messages after a limited time (about 7 days for superseded summaries and 14 days for messages stored on a propagation node).

## 12. Your rights

Depending on where you live, you may have rights over personal data, such as access, correction, erasure, restriction, portability and objection, and the right to complain to your local data-protection authority. Because the provider holds no copy of your data, these rights are exercised mainly **locally and with the services you choose to use**:

- **Access.** Everything the app holds about you is visible in the app. The encrypted backup lets you export your measurements, activities and plans (section 9).
- **Correction.** Correct data at its source (Garmin, Withings) or by entering new values. Measurements from those services are re-downloaded from the source.
- **Erasure.** Disconnect accounts, revoke sharing, then clear the app's storage or uninstall (section 11).
- **Portability.** The encrypted backup is the app's own container and is meant for moving between phones running Pulse4. It is not a general-purpose export format.
- **Withdrawing consent.** Disconnecting an account, turning off a data source or revoking a share stops further processing at once.
- **Third parties.** For data held by Garmin, Withings, map providers, relay operators or the people you shared with, exercise your rights with them.
- **Parents and children.** A parent can withdraw individual approvals at any time and leave the family by removing their own link (section 8).

If you write to the contact in section 2, the provider will tell you what it can and cannot do. It cannot look up data that it does not have.

## 13. Permissions the app uses

| Permission | Why |
|---|---|
| Internet, network state | Download from Garmin and Withings, map tiles, Reticulum gateways |
| Wi-Fi state, multicast, local network | Find other Pulse4 phones on the same Wi-Fi |
| Health Connect read: weight, body fat, background read | Optional import of weight and body fat |
| Notifications | Sync status and incoming-share notices |
| Foreground service (remote messaging), run at boot, ignore battery optimisation | Keep the peer-to-peer connection alive so shared data arrives on time. Can be turned off in the app's settings |

Location, camera, microphone, contacts and storage-wide permissions are **not** requested.

## 14. Security

Beyond the protections above, the app aims to keep peer-to-peer messages authenticated (sender checked, content hash verified, replays rejected). No system is perfectly secure. Please keep your phone updated and locked, choose a strong backup passphrase and report vulnerabilities to the contact in section 2.

## 15. Changes to this policy

When the app changes in a way that affects your data, this policy will be updated and the "last updated" date changed. Material changes will also be mentioned in the app's release notes. Earlier versions can be found in the project's repository history.

## 16. Third-party names

Garmin, Withings, Google, Esri, OpenStreetMap, OpenTopoMap, CyclOSM, CARTO and Reticulum are the property of their respective owners. Pulse4 is an independent project and is not affiliated with or endorsed by them.

---

## Deployment notes (not part of the policy)

- Google Play requires a **public, non-editable URL** for the privacy policy, reachable without sign-in, and the same policy must also be linked inside the app. Publish this file (and the Czech version) via **GitHub Pages**, or use the repository file view as the URL. A stable, rendered page is preferable to a raw Markdown view.
- Before publishing, replace the placeholders `[PROVIDER NAME AND POSTAL ADDRESS]` and `[X.Y]`.
- Section 8 describes a feature in development. Do not publish it as current behaviour until the release containing it ships, and re-verify every statement against the shipped code.
- Items that still need verification against official sources are listed in [PRIVACY_OPEN_ITEMS.md](PRIVACY_OPEN_ITEMS.md). Do not describe this policy as compliant with any law or store policy until they are resolved.
- Keep the Play Console "Data safety" answers consistent with this document.
