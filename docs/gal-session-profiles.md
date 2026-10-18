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
        |-- GalMediaPolicy      unsupported / inspect-only / implemented
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
executing it: current timing options are inspect-only, and AV synchronization and media statistics
remain unsupported. Their presence cannot silently turn on a controller.

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
| 1.6 fallback | Existing formats and buffers | Existing handling | Also handles modern navigation state/position notifications |
| 5.0 experimental | Start options; sender normally selects ackless | Legacy credit path unless Start says otherwise | UiConfig discovery and replacement-safe updates |
| 6.0 experimental | Start and standalone options | Sender normally selects ackless; Start and standalone options | Same UI contract; energy forecast consumed without a response |
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
this receiver replaces its diagnostic snapshot rather than inheriting omitted fields. Sparse
update semantics are not established by the sender analysis.

**Not implemented:** a shared audio/video presentation clock, the requested timing controller,
MediaStats telemetry, or applying the sender's audio buffer request to local playback. Parsing
these options is not a claim that those features work. The receiver keeps its existing playback
policy and logs requests for unsupported synchronization/telemetry. This is why the higher
profiles are opt-in experiments rather than the default.

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
