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
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link EEBusBindingConstants} class defines common constants used across the binding.
 *
 * @author openHAB EEBus Binding Contributors - Initial contribution
 */
@NonNullByDefault
public class EEBusBindingConstants {

    public static final String BINDING_ID = "eebus";

    public static final ThingTypeUID THING_TYPE_CONTROLLABLE_SYSTEM = new ThingTypeUID(BINDING_ID,
            "controllableSystem");

    // Channel IDs
    public static final String CHANNEL_OWN_SKI = "ownSki";
    public static final String CHANNEL_LPC_STATE = "lpcState";
    public static final String CHANNEL_LPC_ACTIVE_LIMIT = "lpcActiveLimit";
    public static final String CHANNEL_LPC_ACTIVE_LIMIT_DURATION = "lpcActiveLimitDuration";
    public static final String CHANNEL_LPC_EVENT = "lpcEvent";
    public static final String CHANNEL_LPP_STATE = "lppState";
    public static final String CHANNEL_LPP_ACTIVE_LIMIT = "lppActiveLimit";
    public static final String CHANNEL_LPP_ACTIVE_LIMIT_DURATION = "lppActiveLimitDuration";
    public static final String CHANNEL_LPP_EVENT = "lppEvent";
}
