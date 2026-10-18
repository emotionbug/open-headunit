# GAL session profiles

GAL is the protocol between the phone and the head unit. Its version is independent of the
Android API level and the Android Auto APK version. Existing installations retain the **1.2
compatibility request**. The advanced setting selects the highest profile to try on the next
connection; it does not change a running session.

## Ownership and extension points

```text
GalVersionExchange              serial version agreement; no service policy
        |
GalConnectionContract.resolve   sole profile-to-policy mapping
        |-- GalMediaPolicy      unsupported / partial / implemented
        |-- GalDisplayContract  discovery and replacement update encoding
        |
ServiceDiscoveryResponse        prepare and cache the actual advertised protobuf
        |
GalMediaOffer                   immutable formats and indices derived from that protobuf
        |
GalMediaSessions                one owner of selected format, ID, ACK mode and option snapshot
        |
common handlers                Setup / Start / DATA / Stop; no GAL version comparisons
```

There are two freeze points: the final version response fixes the protocol policy, and the first
service-discovery response fixes the advertised formats. Repeated discovery requests reuse that
response. Setup and Start use the same offer instead of reading current settings or global fallback
availability again. This does not add sample rates or codecs: existing single audio formats and
the explicit video fallback remain the offered choices. Audio decoding still uses those shared
fixed format definitions; adding multiple audio formats also requires applying the selected format
to decoder construction and PCM timing.

The transport has no second sink-ID table. DATA receipt captures the ID and ACK decision together
from the media-session owner; video keeps that captured result while queued. Stop clears options,
but does not change a tail packet's ACK mode. The microphone remains a separate source lifecycle, including its existing Setup response and
MicrophoneRequest credit window; it is never validated as a sink.

A new profile is added to the version selection list and the contract factory's explicit policy
matrix. Common handlers do not gain new version checks. Receiving an option is distinguished from
executing it: supported requests have concrete consumers, while incomplete timing support remains marked partial.
A/V synchronization is still unsupported; parsing its request does not enable a clock controller.

## Connection lifecycle

```text
new connection
    |
    +-- request 1.2 -> baseline response
    |
    +-- higher profile selected AND baseline reports >= 1.7?
    |       request 1.8 -> reported upper version
    |
    +-- choose a listed profile <= setting AND <= reported version
    |
    +-- send explicit final request if probing or changing from 1.2
    |       success AND final response >= request?
    |
    +-- freeze session profile -> TLS -> service discovery -> media
```

The sender retains the **request** for its feature gates. A response of 1.7 to a request of 1.2
therefore does not turn on 1.6 features. In the inspected modern senders, requests above 1.7
expose a higher constant. The 1.8 probe stays within major 1 because the older GMS cold-start
handler rejects other majors. A probe always needs a final request, including a return to 1.2.

The baseline, probe and final response are retained separately. Only the final response supplies
the final connection-options snapshot. This change does not implement the optional timeout
requests. No identity cache is trusted to select a protocol after a phone upgrade.

Requests are serial and share one deadline. There is no request identifier in VersionResponse:
a late reply to a retransmitted request cannot safely be assigned to another phase. A partial
write, negative I/O result, rejection, malformed response or timeout ends this connection; it does
not attempt another version on the damaged stream. The user can select Compatibility before
reconnecting. This is not an automatic retry loop across new connections. A write explicitly reporting zero bytes may
retry the same request up to three attempts, with 200 ms backoff within the original deadline.
A negative USB result does not expose the transferred count; neither retransmission nor continued
reading is assumed safe merely because the error returned quickly.

## Profile boundaries

| Profile | Audio sink | Video sink | UI and navigation |
| --- | --- | --- | --- |
| 1.2 compatibility | Existing handling; honor an explicit ackless Start if sent | Existing handling | Legacy discovery and margin updates |
| 1.6 fallback | Existing formats and buffers | Validates against advertised sinks/configurations | Also handles modern navigation state/position notifications |
| 5.0 experimental | Start options, advisory bank limit and requested telemetry; sender normally selects ackless | Legacy credit path unless Start says otherwise | UiConfig discovery and replacement-safe updates |
| 6.0 experimental | Start and standalone options | Sender normally selects ackless; options and requested telemetry; timing requests retained | Same UI contract; energy forecast consumed without a response |
| 6.1 experimental | Same observed contract as 6.0 | Same observed contract as 6.0 | Separate version identity, shared implementation |

Profiles below the user's maximum are selected only from this table. For example, a reported
4.5 ceiling selects 1.6, and a reported 6.0 ceiling selects 6.0 rather than requesting 6.1.
No codec, sample rate, video format or user buffer preference changes merely because GAL changes.
The optional 48 kHz guidance work belongs to its own configuration-negotiation change.

## Media ownership and flow control

```text
Start (0x8001)
    field 1: session id
    field 2: advertised configuration index
    field 3: flow-control enum (2 = ackless; absent/0/1 retain ACKs)
    field 4: MediaOptions

Options (0x8014)
    MediaOptions only -- no session id
```

The actual accepted Start decides ACK behavior, not a global version switch. Microphone uplink
credits are separate. Every queued video message captures the original wire ID and ACK policy;
a subsequent Start does not rewrite that captured ID. An ACK carries no local generation.
If the sender reuses an ID (including IDs equivalent under its modulo-256 comparison) while old
work is queued, a local counter cannot distinguish those ACKs on the wire. The FIFO video worker
retains CSD-before-Start ordering and received tail frames; a poll-thread Start does not discard
earlier queued work. Stop permits that tail to finish, but clears options and disables updates,
including Stop during a forced keyframe cycle.

Ackless does not mean unlimited buffering. The existing bounded video queue and recovery path
remain in place. Sending legacy ACKs would not turn the phone's credit controller back on.

MediaOptions is a 13-field optional schema, shared by embedded and standalone messages. Durations
use ordinary signed protobuf seconds/nanos fields, not ZigZag encoding. Options are retained as
immutable snapshots with field presence intact. The inspected sender sends complete snapshots;
this receiver replaces its runtime snapshot rather than inheriting omitted fields. Sparse
update semantics are not established by the sender analysis.

## Runtime requests and measured statistics

`MediaStats` is **0x8013, head unit to phone**. `MediaOptions` is **0x8014, phone to
head unit**. The sender's enum helper adds one before dispatch; its internal case 32788
therefore consumes wire 32787, not the options message. Stats are diagnostics, not DATA
credits or evidence that the phone adjusts its bitrate.

```text
Start / Options -> active channel snapshot -> validated runtime requests
                         |                           |
                         |                     PCM bank / measured output counters
                         |
                 writer timer, only if stats requested
                         |
                 current owner + cumulative counters
                         |
                 0x8013: measured fields only
```

The wire distribution contains signed int64 average/minimum/maximum/count in fields 1..4.
Durations use signed seconds/nanos with sign, protobuf range and Long overflow checks.
Malformed individual requests fall back independently. A zero/negative statistics interval
turns reporting off; valid intervals are locally bounded to 250ms..60s. The sender defaults
observed in AA18 are 500ms for TTS and 5000ms for ordinary media. These bounds are a receiver
resource policy, not recovered protocol limits.

| Request | Receiver behavior | Boundary |
| --- | --- | --- |
| Audio buffer limit | Hint for the minimum reserve reached after quiet decay | Saved preference, a full observed supply batch and underrun recovery take precedence; no immediate trim when a request arrives |
| Media statistics | Per-active-channel reports with bounded storage and cadence | PCM bank estimate and bank overflow/underrun event deltas; MediaCodec output-buffer counts. Unavailable fields are omitted |
| Video timing window | Validated and retained; controller unsupported | The sender does not establish the reference point of its min/max delay. Codec latency is not a safe substitute |
| A/V synchronization and signed sync offset | Retained and reported as unsupported | Needs a shared source timeline through AAC, resampling and output playheads, plus a verified offset convention |

An accepted Start creates a local generation even if its wire ID repeats. Changed options,
Stop, owner replacement and counter reset invalidate telemetry windows. The timer samples every
250ms only while requested, serializes on the existing writer and checks the same active session
again before writing. Equal repeated options do not reset a window. Sampling avoids the decoder
lifecycle monitor, so a track rebuild cannot block ACKs through that lock. It does not enqueue a historical report per media packet. Audio tails may
continue after Stop, but no new report is generated for that stopped session. Reports describe
receiver observation windows, not an inferred cohort of source packets.

The audio buffer limit does not resize the ring or the Android device buffer, and it is not a hard
queue ceiling. The quiet-decay floor is `min(initial ceiling, max(user floor, requested limit))`.
Consequently the sender's usual 300/400ms requests do not change the existing reserve policy.
A smaller request can lower the quiet floor only when the user's floor is below the initial
150ms ceiling. It cannot reduce a saved 16x preference or limit underrun recovery.

The audio buffer limit does not resize the ring or the Android device buffer. After ten quiet
seconds it can lower the floor of the existing 5ms-per-second reserve decay. Receiving a new
limit does not immediately lower today's learned reserve. Supply deficits and underruns still
raise the reserve immediately; saved preference, startup warmup and the supply-batch invariant
remain in force. Normal bounded overlap catch-up may still compress PCM as the reserve decays,
just as without this hint; this is not a promise to preserve every input sample unchanged.

Video timing-controller requests are not executed. In particular, the sender's usual 17ms maximum
must not be compared to full decoder latency: that would discard ordinary frames on many head
units. The optional message alone does not define the reference clock, presentation callback or
pacing algorithm. Existing rendering and recovery remain unchanged. Video telemetry reports
processed MediaCodec output buffers, including concealed or skipped decoded pictures. FFmpeg's
available counter has different semantics, so its video report is omitted until a matching
measurement exists. Neither counter is claimed to measure actual screen presentations.

**Remaining unsupported functionality:** the video timing controller, full A/V presentation synchronization, signed sync-offset
application and statistics requiring that clock (A/V lag, calibrated audio/video end-to-end delay).
Phone-side builders do not establish the offset sign or the receiver controller, and the current
PCM/AAC pipeline does not preserve source PTS through every trim/conversion/output transition.
These profiles therefore remain experimental; this implementation is not a complete GAL 5/6
specification. No separate 6.1 runtime feature was found in the inspected sender branches.

## Display contract

For GAL >= 4.3, VideoConfiguration field 11 contains UiConfig. Its presence overrides the old
margin fields; initial primary and fallback configurations therefore include explicit margins.
For total margins W/H, the sender's legacy split is L=W/2, R=(W+1)/2, T=(H+1)/2, B=H/2.

UiConfig field 5 identifies widgets supplied by the head unit. Clock, battery and phone-signal
choices mirror the existing hide settings. Modern UI updates replace the whole config, so every
margin update repeats those same session-snapshot elements. Legacy visibility bits and
hideProjectedClock use that same snapshot rather than reading settings again at discovery.
Theme stays absent and unchanged.

## Evidence and checks

The profile gates and wire layouts come from static analysis of Android Auto 18.0.664204 and
older sender implementations, including GMS 12.8's major-1-only cold handshake. AA18's `jdx`
selects ackless audio at major 5; `jia` selects ackless video at major 6; `jqn` bypasses credits
in that mode. `jhv` switches to UiConfig margins; `jid` replaces UiConfig on modern updates.
No separate 6.1 media feature gate was found in the inspected paths. This is not a complete
specification or a guarantee for every sender, OEM variant or server flag.

Unit tests cover serial probing, version bounds, legacy fallback, split/coalesced envelopes,
failed exchanges before TLS, final-options ownership, per-channel ACK mode, repeated session IDs,
Stop/update ownership, signed Duration encoding and replacement-safe UI fields. Hardware
interoperability of the new opt-in profiles remains separate from these deterministic checks.


## Service lifecycle and selected formats

- Setup rejects unsupported channel/codec combinations before READY. Video must match the
  advertised codec; audio continues to accept supported PCM/AAC framing selected by the phone.
  A microphone Setup is accepted only if that source was advertised.
- Video codec and software backend preferences are frozen with discovery. Decoder creation and
  the software-HEVC ACK window consume that same snapshot instead of rereading mutable settings.
- Start's configuration index selects the actual audio rate, channels and PCM duration calculation.
  Identical formats keep the existing decoder and bank. A format change requires a new decoder;
  remaining audio in an incompatible old format is not transferred to it. Repeated Setup retains
  the previous selected index until Start selects another one.
- CSD before Start is retained as an owned, bounded copy and used only with a matching AAC-LC
  format. CSD still consumes no ACK credit. New Setup retires the previous pending CSD.
- ChannelClose retires runtime requests and statistics. Active audio drains through the existing
  Stop path, retaining tail ACK ownership; microphone capture is closed even if Stop was omitted.
- Initial UiConfig and subsequent margin updates use the same odd-pixel split: the extra pixel
  goes to the right/top. Selecting primary after fallback also restores display/touch geometry.

## Sensor requests

Only sensors in the connection's discovery offer can be subscribed. `-1` stops a subscription;
`0` permits change-driven updates. Stop and channel close invalidate queued events, including
an event waiting for its rate slot. Starting NIGHT/LOCATION/DRIVING_STATUS requests an initial value;
stopping never triggers a new value.

The historical OHU field name `min_update_period` is misleading: the inspected AA14–18 continuous-event limiters
use the positive value as Hz. OHU uses that cadence for positive requests and coalesces to the
latest sample, sending it at the next slot even if no further callback occurs. This is a receiver
policy derived from the inspected sender, not a version-independent statement about the original
schema. The observed LOCATION/NIGHT/DRIVING_STATUS callers request zero. One pending value and one
scheduled task per active subscription keep sensor work separate from the audio flow-control policy.

## Bluetooth pairing service

The service handles phone PairingRequest (0x8001) and replies with PairingResponse (0x8002), including
both required fields: status and already-paired. Bluetooth Binder work runs outside the shared
receive thread, with at most one pending lookup. Channel close invalidates a pending result.

Success requires an existing bond and an already connected HFP-client link to that phone on the
local adapter whose readable address matches the advertised address. The profile lookup is bounded
and runs on the Bluetooth worker. If its callback never arrives, that transport does not repeat
the binding attempt; a new connection may try again. Application context owns the lookup. A missing bond or unconfirmed call link receives pairing-delayed; an unreadable/redacted address,
permission denial, unavailable adapter or external vehicle radio receives unavailable. OHU does
not return success/false: old phones can interpret it as mismatched bond records and unpair.
Even success/true can start HFP retries which eventually unpair on old phones, so a bond alone
is insufficient. This handler confirms an existing call link; it does not establish a new one.

This is **partial pairing-service support**, not HFP implementation or automatic pairing. Android
continues to own PIN/confirmation dialogs. OHU does not invent authentication data (0x8003) and an
incoming authentication result (0x8004) does not grant a bond or a call-audio route. Global vendor
MAC properties are insufficient to prove that a local Android bond database belongs to that radio.


Microphone ANC/EC fields remain diagnostic requests, not a promise about hardware processing.
The inspected AA17/18 builders always send false for both; using those values as mandatory
switches would disable the user's configured effects. Local microphone settings and Android's
actual effect availability remain authoritative. Firmware processing associated with the capture
source may also exist independently of the app's AudioEffect instances.
