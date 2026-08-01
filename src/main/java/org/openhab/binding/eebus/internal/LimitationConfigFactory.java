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
import org.openmuc.jeebus.spine.utils.datatypes.ScaledNumberWrapper;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.SimpleLimitationConfig;

/**
 * Builds the {@link SimpleLimitationConfig} passed to {@code LpcCs}/{@code LppCs} from thing
 * configuration. Pulled out of the handler so the mapping from config fields to
 * {@code SimpleLimitationConfig}'s constructor - {@code (failsafeDurationMin, failsafeLimit,
 * loadControlLimit, nominalMax)}, easy to get wrong since the first two positional
 * {@link ScaledNumberWrapper} arguments are not in "nominal, then failsafe" order - can be unit
 * tested directly against the SDK's own getters.
 *
 * @author openHAB EEBus Binding Contributors - Initial contribution
 */
@NonNullByDefault
public final class LimitationConfigFactory {

    private static final int SCALED_NUMBER_SCALE = 0;

    private LimitationConfigFactory() {
    }

    public static SimpleLimitationConfig lpc(EEBusControllableSystemConfiguration config) {
        ScaledNumberWrapper nominalMax = new ScaledNumberWrapper(config.lpcNominalMaxWatts, SCALED_NUMBER_SCALE);
        ScaledNumberWrapper failsafe = new ScaledNumberWrapper(config.lpcFailsafeLimitWatts, SCALED_NUMBER_SCALE);
        // loadControlLimit (the initial/default active limit) starts at nominalMax, i.e.
        // unrestricted until the CEM says otherwise.
        return new SimpleLimitationConfig(config.lpcFailsafeDuration, failsafe, nominalMax, nominalMax);
    }

    public static SimpleLimitationConfig lpp(EEBusControllableSystemConfiguration config) {
        ScaledNumberWrapper nominalMax = new ScaledNumberWrapper(config.lppNominalMaxWatts, SCALED_NUMBER_SCALE);
        ScaledNumberWrapper failsafe = new ScaledNumberWrapper(config.lppFailsafeLimitWatts, SCALED_NUMBER_SCALE);
        // Per the jEEBus reference usage: for LPP the load-control (default) limit is negative,
        // while nominal max and failsafe remain positive.
        return new SimpleLimitationConfig(config.lppFailsafeDuration, failsafe, nominalMax.negate(), nominalMax);
    }
}
