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
package org.openhab.binding.eebus.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link EEBusControllableSystemConfiguration} class contains fields mapping the
 * {@code controllableSystem} thing configuration parameters. Field names must match the parameter
 * names declared in {@code OH-INF/thing/thing-types.xml} exactly.
 *
 * @author openHAB EEBus Binding Contributors - Initial contribution
 */
@NonNullByDefault
public class EEBusControllableSystemConfiguration {

    public String bindAddress = "0.0.0.0";
    public int port = 4712;
    public String wssPath = "/ship/";
    public String serviceDomain = "local.";

    public @Nullable String deviceId;
    public String friendlyName = "openHAB";
    public String deviceType = "GENERIC";
    public String entityType = "CEM";

    public String connectPolicy = "TRUSTED";
    public @Nullable String trustedSkis;
    public boolean autoAcceptPairing;

    public boolean lpcEnabled = true;
    public int lpcNominalMaxWatts = 4200;
    public int lpcFailsafeLimitWatts = 4200;
    public String lpcFailsafeDuration = "PT2H";

    public boolean lppEnabled;
    public int lppNominalMaxWatts;
    public int lppFailsafeLimitWatts;
    public String lppFailsafeDuration = "PT2H";
}
