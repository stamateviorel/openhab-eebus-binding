/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.eebus.internal.handler;

import static org.openhab.binding.eebus.internal.EEBusBindingConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.eebus.internal.EEBusControllableSystemConfiguration;
import org.openhab.binding.eebus.internal.LimitationConfigFactory;
import org.openhab.binding.eebus.internal.ServiceNameSanitizer;
import org.openhab.binding.eebus.internal.cert.EEBusCertificateStorage;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.storage.Storage;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.openmuc.jeebus.ship.api.ShipNodeConfiguration;
import org.openmuc.jeebus.shipspine.ShipCommunication;
import org.openmuc.jeebus.shipspine.ShipCommunication.ConnectClientsTo;
import org.openmuc.jeebus.spine.api.Device;
import org.openmuc.jeebus.spine.api.IncompleteBuildException;
import org.openmuc.jeebus.spine.spi.UseCase;
import org.openmuc.jeebus.spine.xsd.v1.DeviceTypeEnumType;
import org.openmuc.jeebus.spine.xsd.v1.EntityTypeEnumType;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.ActiveLimit;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.lpc.LpcCs;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.lpp.LppCs;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.states.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link EEBusControllableSystemHandler} runs a local EEBus SHIP/SPINE node presenting
 * openHAB as a Controllable System (CS) hosting the LPC and/or LPP use cases. A remote CEM/EMS or
 * smart-meter CLS gateway pairs with this node over SHIP and issues power limits, which are
 * surfaced here as channels.
 * <p>
 * Building the underlying {@link Device} opens a listening socket and starts mDNS advertisement as
 * a side effect, so it is done on the handler's scheduler rather than on the calling thread. That
 * makes {@link #connect()} and {@link #dispose()} concurrent by construction (a config change right
 * after the thing is added, for example, disposes and re-initializes while the first
 * {@link Device#getBuilder()} call may still be in flight) - {@link #disposed} plus publishing the
 * built resources to the instance fields only once, right before going ONLINE, is what keeps a
 * disposed-mid-connect run from leaking an open socket/mDNS advertisement that nothing can ever
 * close again.
 *
 * @author openHAB EEBus Binding Contributors - Initial contribution
 */
@NonNullByDefault
public class EEBusControllableSystemHandler extends BaseThingHandler {

    private static final int CERTIFICATE_VALIDITY_DAYS = 3650;

    private final Logger logger = LoggerFactory.getLogger(EEBusControllableSystemHandler.class);
    private final Storage<String> certStorage;

    private volatile boolean disposed;

    private @Nullable Device device;
    private @Nullable LpcCs lpcCs;
    private @Nullable LppCs lppCs;

    public EEBusControllableSystemHandler(Thing thing, Storage<String> certStorage) {
        super(thing);
        this.certStorage = certStorage;
    }

    @Override
    public void initialize() {
        disposed = false;
        updateStatus(ThingStatus.UNKNOWN);
        scheduler.execute(this::connect);
    }

    private void connect() {
        EEBusControllableSystemConfiguration config = getConfigAs(EEBusControllableSystemConfiguration.class);

        String deviceId = config.deviceId;
        if (deviceId == null || deviceId.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Device ID must be set");
            return;
        }
        if (!config.lpcEnabled && !config.lppEnabled) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Enable at least one of LPC or LPP");
            return;
        }

        DeviceTypeEnumType deviceType;
        EntityTypeEnumType entityType;
        ConnectClientsTo connectPolicy;
        try {
            deviceType = DeviceTypeEnumType.valueOf(config.deviceType);
            entityType = EntityTypeEnumType.valueOf(config.entityType);
            connectPolicy = ConnectClientsTo.valueOf(config.connectPolicy);
        } catch (IllegalArgumentException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Invalid deviceType/entityType/connectPolicy: " + e.getMessage());
            return;
        }

        // Built as locals and only published to the instance fields once everything below
        // succeeds - see the class javadoc on why that matters for dispose()/connect() safety.
        @Nullable
        LpcCs lpcCs = null;
        @Nullable
        LppCs lppCs = null;
        try {
            List<UseCase> useCases = new ArrayList<>();
            if (config.lpcEnabled) {
                lpcCs = createLpcCs(config);
                useCases.add(lpcCs);
            }
            if (config.lppEnabled) {
                lppCs = createLppCs(config);
                useCases.add(lppCs);
            }

            EEBusCertificateStorage certificateStorage = new EEBusCertificateStorage(certStorage,
                    thing.getUID().getAsString(), "CN=" + config.friendlyName, CERTIFICATE_VALIDITY_DAYS);

            // ShipNodeConfiguration's constructors are marked deprecated-for-removal in ship 2.3.0,
            // but no replacement (e.g. a builder) is published on Maven Central yet - revisit once
            // one ships.
            //
            // The service instance name is advertised via mDNS and echoed back as the TLS SNI value
            // by at least some SHIP clients connecting in - a name containing e.g. spaces (any
            // openHAB Thing label is fair game) crashes the inbound TLS handshake with an unhandled
            // IllegalArgumentException in the JDK's strict SNI hostname validation (confirmed live
            // against a real EEBus peer on 2026-08-01), so it must be sanitized to a safe charset.
            ShipNodeConfiguration shipConfig = new ShipNodeConfiguration(Set.of(config.bindAddress), config.port,
                    config.wssPath, true, deviceId, config.serviceDomain,
                    ServiceNameSanitizer.sanitize(config.friendlyName), certificateStorage,
                    aliasFor(thing.getUID().getId()), CERTIFICATE_VALIDITY_DAYS);

            ShipCommunication shipCommunication = new ShipCommunication(shipConfig).withConnectClientsTo(connectPolicy)
                    .withAutoAcceptMode(config.autoAcceptPairing);

            Set<String> trustedSkis = parseTrustedSkis(config.trustedSkis);
            if (!trustedSkis.isEmpty()) {
                shipCommunication = shipCommunication.withTrustedSkis(trustedSkis);
            }

            Device device = Device.getBuilder().withDeviceType(deviceType).withCommunication(shipCommunication)
                    .withId(deviceId).withDiscoverDevices(false).addEntity().setType(entityType)
                    .withUseCases(useCases.toArray(new UseCase[0])).applyToDevice().build();

            if (disposed) {
                // The thing was disposed while this connect() run was still building - close what
                // we just opened instead of publishing it, or it leaks (open socket, live mDNS
                // advertisement) with nothing left able to close it.
                closeQuietly(lpcCs, lppCs, device);
                return;
            }

            this.lpcCs = lpcCs;
            this.lppCs = lppCs;
            this.device = device;

            updateState(CHANNEL_OWN_SKI, new StringType(shipCommunication.getOwnSki()));
            updateStatus(ThingStatus.ONLINE);
        } catch (IncompleteBuildException e) {
            closeQuietly(lpcCs, lppCs, null);
            logger.warn("Failed to build EEBus device for {}: {}", thing.getUID(), e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            // e.g. the configured port is already in use, or a certificate could not be
            // generated/loaded - anything the SHIP/SPINE SDK itself doesn't wrap in a checked
            // exception.
            closeQuietly(lpcCs, lppCs, null);
            logger.warn("Failed to start EEBus node for {}", thing.getUID(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private LpcCs createLpcCs(EEBusControllableSystemConfiguration config) {
        LpcCs cs = new LpcCs(LimitationConfigFactory.lpc(config));
        cs.addListener((event, state, limit) -> onLimitationUpdate(CHANNEL_LPC_STATE, CHANNEL_LPC_ACTIVE_LIMIT,
                CHANNEL_LPC_ACTIVE_LIMIT_DURATION, CHANNEL_LPC_EVENT, event, state, limit));
        return cs;
    }

    private LppCs createLppCs(EEBusControllableSystemConfiguration config) {
        LppCs cs = new LppCs(LimitationConfigFactory.lpp(config));
        cs.addListener((event, state, limit) -> onLimitationUpdate(CHANNEL_LPP_STATE, CHANNEL_LPP_ACTIVE_LIMIT,
                CHANNEL_LPP_ACTIVE_LIMIT_DURATION, CHANNEL_LPP_EVENT, event, state, limit));
        return cs;
    }

    private void onLimitationUpdate(String stateChannel, String limitChannel, String durationChannel,
            String eventChannel, Event event,
            org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.states.State state, ActiveLimit limit) {
        updateState(stateChannel, new StringType(state.name()));
        updateState(limitChannel, toQuantityState(limit));
        Optional<String> duration = limit.getDuration();
        updateState(durationChannel, duration.isPresent() ? new StringType(duration.get()) : UnDefType.NULL);
        triggerChannel(eventChannel, event.name());
    }

    private State toQuantityState(ActiveLimit limit) {
        Double value = limit.getResultingValue();
        if (value == null) {
            return UnDefType.NULL;
        }
        if (!"W".equals(limit.getUnit())) {
            logger.debug("Unexpected EEBus active-limit unit '{}' on {}, expected W", limit.getUnit(), thing.getUID());
        }
        return new QuantityType<>(value, Units.WATT);
    }

    private static Set<String> parseTrustedSkis(@Nullable String trustedSkis) {
        if (trustedSkis == null || trustedSkis.isBlank()) {
            return Set.of();
        }
        return Stream.of(trustedSkis.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static String aliasFor(String thingId) {
        return "openhab-" + thingId;
    }

    private static void closeQuietly(@Nullable LpcCs lpcCs, @Nullable LppCs lppCs, @Nullable Device device) {
        if (lpcCs != null) {
            lpcCs.close();
        }
        if (lppCs != null) {
            lppCs.close();
        }
        if (device != null) {
            device.close();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // All channels are read-only telemetry driven by the paired CEM/EMS - there is nothing to
        // send in response to a command.
    }

    @Override
    public void dispose() {
        disposed = true;
        LpcCs lpc = this.lpcCs;
        this.lpcCs = null;
        LppCs lpp = this.lppCs;
        this.lppCs = null;
        Device dev = this.device;
        this.device = null;
        closeQuietly(lpc, lpp, dev);
    }
}
