# openHAB EEBus Binding

> **Status**: early, working prototype. Builds clean (checkstyle/spotbugs/spotless/i18n all pass, 12/12 unit
> tests pass) and has been live-tested against a real independent EEBus implementation
> ([meisel2000/eebus-cbsim](https://github.com/meisel2000/eebus-cbsim), built on the `enbility/eebus-go` stack) —
> SHIP pairing (TLS handshake, mDNS discovery, SKI-based trust) and SPINE LPC use-case discovery/data exchange all
> verified working end-to-end. Not yet verified: a full accepted *active* limit write from a real CEM (blocked on
> a simulator-side heartbeat quirk, not this binding — see commit history / linked issue for details). No EEBus
> hardware has been used in this development; testing so far is simulator-only.
>
> This is a mirror of `bundles/org.openhab.binding.eebus/` from a working branch against
> [openhab/openhab-addons](https://github.com/openhab/openhab-addons) - it needs that repo's parent reactor POM to
> build (`mvn -pl bundles/org.openhab.binding.eebus -am package` from an openhab-addons checkout with this bundle
> added), it isn't standalone-buildable on its own. Published here for visibility/feedback ahead of an eventual
> upstream PR; see [openhab/openhab-addons#21211](https://github.com/openhab/openhab-addons/issues/21211).

# EEBus Binding

This binding lets openHAB present itself as an [EEBus](https://www.eebus.org/) Controllable System (CS) on the local
network, backed by the [jEEBus](https://www.openmuc.org/eebus/) SHIP/SPINE implementation from Fraunhofer ISE /
OpenMUC. A remote CEM/EMS or a smart-meter CLS gateway (§14a EnWG in Germany) can pair with it over the standard
EEBus SHIP transport and issue LPC (Limitation of Power Consumption) and LPP (Limitation of Power Production) power
limits, which are surfaced as openHAB channels for use in rules.

Note the direction of control: this binding implements the _device being limited_, not the _thing issuing limits_.
It does not pair with and control other EEBus devices (e.g. a real wallbox or heat pump) directly - as of this
writing, OpenMUC has not published a Java library for that (CEM/controller) side of the protocol.

## Supported Things

| Thing               | Description                                                                      |
|----------------------|-----------------------------------------------------------------------------------|
| `controllableSystem` | A local EEBus SHIP/SPINE node hosting the LPC and/or LPP Controllable System use cases. |

## Discovery

There is no discovery service. This binding _is_ the node that a remote EEBus partner discovers via mDNS and pairs
with - there is nothing on the local network for openHAB to discover in the other direction.

## Thing Configuration

### `controllableSystem`

| Parameter               | Group    | Required | Default                              | Description                                                                                   |
|--------------------------|----------|----------|---------------------------------------|-------------------------------------------------------------------------------------------------|
| bindAddress              | network  | no       | `0.0.0.0`                             | Local IP address the SHIP WebSocket server binds to.                                            |
| port                     | network  | no       | `4712`                                | TCP port the SHIP WebSocket server listens on.                                                  |
| wssPath                  | network  | no       | `/ship/`                              | HTTP path the SHIP WebSocket endpoint is served under.                                          |
| serviceDomain            | network  | no       | `local.`                              | mDNS domain used for SHIP service advertisement.                                                |
| deviceId                 | identity | yes      | `d:_i:openHAB:controllable-system-01` | Unique SPINE device identifier. Change the default before pairing with a real partner.          |
| friendlyName             | identity | no       | `openHAB`                             | Human-readable name advertised to pairing partners.                                             |
| deviceType               | identity | no       | `GENERIC`                             | SPINE device type reported to pairing partners.                                                 |
| entityType               | identity | no       | `CEM`                                 | SPINE entity type hosting the LPC/LPP use cases (must be one of CEM, COMPRESSOR, EVSE, HEAT_PUMP_APPLIANCE, INVERTER, SMART_ENERGY_APPLIANCE, SUB_METER_ELECTRICITY). |
| connectPolicy            | pairing  | no       | `TRUSTED`                             | `TRUSTED` (only pre-trusted SKIs), `ALL` (insecure), or `NONE`.                                 |
| trustedSkis              | pairing  | no       | -                                      | Comma-separated list of remote SKIs to pre-trust. Used when `connectPolicy` is `TRUSTED`.       |
| autoAcceptPairing        | pairing  | no       | `false`                               | Accept any pairing request without a pre-trusted SKI. Lab testing only, never in production.    |
| lpcEnabled               | lpc      | no       | `true`                                | Expose the LPC use case.                                                                         |
| lpcNominalMaxWatts       | lpc      | no       | `4200`                                | Maximum consumption this installation could ever draw.                                          |
| lpcFailsafeLimitWatts    | lpc      | no       | `4200`                                | Limit applied if the pairing partner's heartbeat is lost. **Review and lower this** - it is not a safe default. |
| lpcFailsafeDuration      | lpc      | no       | `PT2H`                                | ISO 8601 duration the failsafe limit stays valid for.                                           |
| lppEnabled               | lpp      | no       | `false`                               | Expose the LPP use case.                                                                          |
| lppNominalMaxWatts       | lpp      | no       | `0`                                    | Maximum production this installation could ever feed in.                                        |
| lppFailsafeLimitWatts    | lpp      | no       | `0`                                    | Limit applied if the pairing partner's heartbeat is lost. **Review and set this deliberately.**  |
| lppFailsafeDuration      | lpp      | no       | `PT2H`                                | ISO 8601 duration the failsafe limit stays valid for.                                           |

The failsafe limits are intentionally **not** defaulted to a conservative fraction of the nominal max - only the
installer knows what this site's wiring can safely sustain unattended. Review both `lpcFailsafeLimitWatts` and
`lppFailsafeLimitWatts` before pairing with a live partner.

## Channels

| Channel                 | Type          | Read-only | Description                                                                 |
|---------------------------|---------------|-----------|-------------------------------------------------------------------------------|
| ownSki                    | String        | yes       | This node's own SKI - give it to your CLS gateway/EMS installer to pre-trust it. |
| lpcState / lppState       | String        | yes       | `INIT`, `CONTROLLED`, `LIMITED`, `FAILSAFE`, or `AUTONOMOUS`.                |
| lpcActiveLimit / lppActiveLimit | Number:Power | yes | The currently active power limit.                                           |
| lpcActiveLimitDuration / lppActiveLimitDuration | String | yes | ISO 8601 duration the active limit stays valid for, if reported.        |
| lpcEvent / lppEvent       | trigger       | -         | Fires `INIT_TIMEOUT`, `HEARTBEAT_TIMEOUT`, `ACTIVE_LIMIT_RECEIVED`, `LIMIT_DEACTIVATED`, or `FAILSAFE_TIMEOUT` on every state transition. |

## Testing Without EEBus Hardware

Since this binding implements the standard EEBus SHIP/SPINE Controllable System role, it can be paired against any
generic EEBus control-box simulator or the free conformance-testing lab run by the EEBus Initiative
([Living Lab Cologne](https://www.livinglabcologne.com/)) rather than only against real hardware.

## Full Example

eebus.things:

```java
Thing eebus:controllableSystem:home "Home Controllable System" [
    deviceId="d:_i:openHAB:home-01",
    connectPolicy="TRUSTED",
    trustedSkis="e268fabdcbb076e13d5f2ea7df6b2d7c382a967f",
    lpcEnabled=true,
    lpcNominalMaxWatts=11000,
    lpcFailsafeLimitWatts=4200
]
```

eebus.items:

```java
String   EEBus_LPC_State         "LPC State"          { channel="eebus:controllableSystem:home:lpcState" }
Number:Power EEBus_LPC_Limit     "LPC Active Limit"    { channel="eebus:controllableSystem:home:lpcActiveLimit" }
String   EEBus_Own_SKI           "Own SKI"             { channel="eebus:controllableSystem:home:ownSki" }
```
