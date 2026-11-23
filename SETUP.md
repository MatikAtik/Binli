# Binly setup

The app includes on-device EfficientNet-Lite0 classification and ML Kit OCR, an interactive OpenStreetMap map,
and live recycling-point lookup through Overpass.

## Accounts

The sign-in and create-account screens use Firebase Authentication. No Firebase
project is included in this repository. Enable Email/Password in your Firebase
project and add these values to your user Gradle properties or a local project
Gradle properties file (do not commit project-specific configuration):

```properties
BINLY_FIREBASE_API_KEY=your_firebase_web_api_key
BINLY_FIREBASE_PROJECT_ID=your_project_id
BINLY_FIREBASE_APP_ID=your_android_firebase_app_id
```

Register the Android app as `com.example.binli`, then rebuild. The Android app ID
is the Firebase ID such as `1:123456789:android:abcdef`, not the package name.
Use an API key authorized for this Android application and Firebase Authentication.
Until configuration is supplied, account actions explain the missing configuration.
Passwords are passed to the Firebase SDK and are never saved by app code.
Learning progress remains local and is not synced between accounts.

## Scanning

Choose a photo with Android's permission-free photo picker, or take a full-resolution
photo using an installed camera app. Temporary camera output uses a private
FileProvider scoped to the photos cache directory. No broad media or camera permission
is requested. Photos are resized before labeling and processed on-device.

Recognition evaluates the full photo first with EfficientNet-Lite0 and bundled Latin OCR.
A confident result keeps the user's framing. Only an unresolved photo gets an optional
detector crop with generous surrounding context. A failed crop never discards the full-photo
result; manual crops remain authoritative. Conflicting printed codes block automatic
example matching and cropping. Printed material codes (PET/HDPE/PP/PS, PAP, GL, ALU/FE)
and battery markings supplement visual labels. Codes may span adjacent lines.
Receipts are not automatically classified as clean paper.

The optional object detector model is downloaded by Google Play services. If it is not
available yet, classification and OCR still run on the selected or full photo. Photos stay on-device. Results
show the analyzed region and the recognized material code; Refine crop lets the user
select a specific item and rescan it. The custom crop is retained across rotation.

The former broad ML Kit labeler has been replaced by EfficientNet-Lite0 float32 v1
(ImageNet, 1,000 classes), including computer/typewriter keyboards. The model and its
English labels are downloaded from Google's versioned MediaPipe model URL by a Gradle
task into generated build assets. The task checks the TFLite header, float32 NHWC
1x224x224x3 input, and embedded normalization (mean 127, standard deviation 128).
The runtime reads RGB pixels using exactly that contract and validates output/label count.

Inference uses the LiteRT CompiledModel API on CPU in Google Play services 17.0.0,
keeping the runtime out of the APK. Google Play services is required and may download
the runtime module on first use. Native model creation, warm-up, reusable buffers,
inference/readback and closing run on one background thread. The Kotlin compiler is
pinned to 2.4.10 to support the runtime's Kotlin metadata.

Computer-keyboard class probabilities are grouped because computer-keyboard and
typewriter-keyboard predictions can describe the same item. They still need enough
probability and separation from other classes. QWERTY/control-key OCR provides
additional keyboard evidence. Visible e-cigarette/vape text or a vape brand with
supporting device details provides a vape-specific guide. A generic musical-instrument
or lighter prediction is never relabelled as a vape or keyboard by itself.
Unsupported visual guesses are no longer presented as confirmed detections or accuracy
percentages. The user can correct an item directly; corrections are labelled as user
selections and do not train the model.

A bundled reference bank adds material-specific example matching when the base model
cannot identify a supported item. It contains 360 TrashNet pictures (60 each of plastic,
glass, paper, cardboard, metal and non-recyclable trash), one keyboard photo and two vape
photos with quarter-turn augmentations: 369 reference vectors. Trash examples provide
negative evidence. The highest similarity, support from neighbouring examples and separation
from competing categories must pass acceptance thresholds; weak/conflicting matches remain
unknown. Reference matches are labeled as suggestions. Clear OCR and confident visual
classifications take priority over both reference and personal matches.

This is a small example classifier, not retraining EfficientNet or universal object
recognition. Unmarked vapes unlike the two photographed devices can still require a correction.
A single photo cannot reliably establish every bottle's material. Vape markings on
packaging also require checking that the actual device is pictured.

Source images: https://github.com/garythung/trashnet (Gary Thung and Mindy Yang, MIT);
Commons keyboard (Steve Karg, public domain); Lost Vape Ursa Nano Pod (Jacek Halicki,
CC BY-SA 4.0); Flerbar M Mango Ice-9467 (© Raimond Spekking, CC BY-SA 4.0).
Full notices and original links are in app assets/recognition_credits.txt and Settings.
The build checks the TrashNet archive SHA-256 before extracting it. Reference IDs 1–60,
calibration IDs 61–80 and evaluation IDs 101–110 are disjoint for all six categories.
Calibration/evaluation photographs are packaged only with instrumentation tests.

The bank is bundled as precomputed, per-vector int8 features in reference_vectors_v1.txt;
the user's first scan does not embed hundreds of reference photos. Features were measured
with the actual CPU runtime, and device tests compare dequantized samples with fresh
inference (cosine similarity greater than 0.999). The public images remain bundled for
reproducibility. Updating the bank requires regenerating vectors using the developer-only
ScannerInstrumentedTest#exportReferenceVectors instrumentation test with
`-e exportReferenceBank true`, then writing its BINLY_REFERENCE_VECTOR rows to the asset
after a header `mobilenet-v3-small-float32-v1|<row count>`. Never include evaluation or user
photos in that export.

Material thresholds (nearest 0.50, top-three support 0.45, competing-category margin 0.075)
were selected using 120 separate calibration photos: 48 of 49 accepted suggestions were
correct there. On the subsequent 60-photo development check, 26 material suggestions
were accepted: 25 correct and one wrong, with 34 unresolved. The base visual model/rules
alone identified five materials; ten additional non-recyclable examples remained unknown.
This comparison excludes OCR and automatic cropping, and is not field accuracy.
Vape checks cover resized and rotated versions of two known devices, not broad vape
generalization. More varied devices may still require user examples.

Tests cover CPU keyboard recognition, full-photo framing, printed recycling codes,
conflicting-code crops, local correction persistence, vape reference matching, invalid
vectors, negative examples and a separate 60-photo development evaluation. The evaluation
reports accepted predictions separately from unknown results; these controlled pictures
do not measure accuracy on users' photos. Guidance is general and does not assume
municipal bin colours or acceptance rules.

Validation: debug build and lint passed, along with 45 JVM tests and 10 physical-device
scanner checks. The developer-only vector export is skipped in ordinary test runs.
The device checks include three real evaluation photos through the complete ScanEngine,
plus keyboard framing, OCR/crops, stored corrections, vector parity and rotated vapes.

## Personal photo matching

Choosing or correcting a scanned item saves a normalized visual feature vector and
the chosen category on this device. MobileNetV3-small float32 v1, downloaded from
Google MediaPipe into generated build assets, supplies the features through the existing
single-threaded LiteRT runtime. Input normalization comes from its verified metadata.

This is local example matching, not retraining the base classifier or sharing data
with other users. No photo is stored in the example library. Up to 60 examples are kept;
near-duplicates replace their old label. Conflicting or insufficiently similar examples
are rejected. Confident base-model identities, material markings and battery evidence
take priority; a saved correction cannot overwrite a good classification. The UI labels
personal matches as suggestions for confirmation.

Settings shows the example count and offers clearing the library. The feature-vector
preferences are excluded from cloud backup and device transfer. Tests verify persistent
corrections, matching a resized real photo, conflicting labels, and invalid vectors.

## Quiz interaction

Answers use custom ripple cards with letter badges, correct/incorrect styling,
haptic feedback and an optional confetti overlay. Feedback and explanations appear
inline with retry/continue controls. System-disabled animations are respected.
Selected answers survive rotation; answering again cannot duplicate XP.

## CO2 estimate

The calculator requires user confirmation of the exact supported material and
an entered weight in grams. It estimates recycling instead of landfilling using
EPA WARM v16 Exhibits 2-2 and 6-17, in metric tonnes CO2e per US short ton:

| Material | Landfill | Recycling |
|---|---:|---:|
| PET | 0.02 | -1.04 |
| Glass | 0.02 | -0.28 |
| Aluminium cans | 0.02 | -9.13 |
| Corrugated cardboard | 0.18 | -3.14 |

Formula: weight in kg × (landfill − recycling) × 1000 / 907.18474.
These US-average model results are potential benefits, not measured local emissions
or verified carbon credits. Vapes, batteries and other unsupported items receive no
invented estimate.

Profile totals change only when the user records having recycled an item. A photo
URI gets one entry; the entry can be undone in the calculator. Scanning and answering
quizzes do not add carbon savings automatically.

Source: https://www.epa.gov/system/files/documents/2024-01/warm_management_practices_v16_dec.pdf

## Maps and privacy

OpenStreetMap tiles load over HTTPS with application identification, visible
attribution, and a seven-day cache. No bulk/offline downloading is enabled.
Overpass queries recycling amenities within 10 km of the chosen map centre and
caches results for 10 minutes, with a second Overpass endpoint if the first is unavailable.
Manual city/postcode search uses Nominatim only after an explicit search action, with
rate limiting and cached results. Map-area and city selections are labelled separately
from device location. Tap a marker for details; Directions opens an external map.

On opening Drop-offs, granted approximate foreground location is used. If unavailable
or denied, https://ipwho.is/ estimates the area from the public IP. VPNs and mobile
networks can make this inaccurate. This use and its service providers are disclosed
on the map screen. Location is not used in the background.

Public services are best-effort and may be unavailable or rate-limited. The UI
offers retry, map-area search, and clear unavailable/empty states rather than fake pins.
Community rankings remain labelled example data.

On the connected test phone, Doze blocked application DNS while the screen was asleep.
The public-city lookup, actual collection-point query and HTTPS tile smoke test passed
once the phone was awake. Failed map loads retry when the map resumes; DNS failures
have a connection message instead of appearing as an empty list. The live smoke test
wakes the device without changing global power settings and never calls IP geolocation.

## References

- [EfficientNet-Lite0 model and contract](https://ai.google.dev/edge/mediapipe/solutions/vision/image_classifier)
- [Image embedding model](https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder)
- [LiteRT runtime](https://developers.google.com/edge/litert/android/play_services)
- [Vape disposal guidance](https://www.london-fire.gov.uk/safety/the-home/smoking/vaping-and-e-cigarettes/)
- [ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [Firebase Authentication](https://firebase.google.com/docs/auth/android/password-auth)
- [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/)
- [Overpass QL](https://wiki.openstreetmap.org/wiki/Overpass_API/Overpass_QL)
- [IP geolocation](https://ipwhois.io/documentation)
