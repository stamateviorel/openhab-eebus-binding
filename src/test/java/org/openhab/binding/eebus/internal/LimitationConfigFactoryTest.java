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

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openmuc.jeebus.usecase.powerlimitation.controllablesystem.SimpleLimitationConfig;

/**
 * Tests for {@link LimitationConfigFactory}.
 * <p>
 * These specifically guard against transposing {@code nominalMax} and {@code failsafeLimit} into
 * {@link SimpleLimitationConfig}'s constructor - it takes {@code (failsafeDurationMin,
 * failsafeLimit, loadControlLimit, nominalMax)}, not "nominal, then failsafe", and getting that
 * wrong silently swaps which value governs the failsafe power limit vs. the nominal max reported
 * to the CEM. Found live on 2026-08-01 by an independent review after the LPC/LPP defaults
 * happened to be identical, which made the original bug unobservable in earlier live testing.
 */
@NonNullByDefault
class LimitationConfigFactoryTest {

    @Test
    void lpcMapsNominalMaxAndFailsafeToTheirOwnFields() {
        EEBusControllableSystemConfiguration config = new EEBusControllableSystemConfiguration();
        config.lpcNominalMaxWatts = 11000;
        config.lpcFailsafeLimitWatts = 4200;
        config.lpcFailsafeDuration = "PT2H";

        SimpleLimitationConfig limitationConfig = LimitationConfigFactory.lpc(config);

        assertEquals(11000, limitationConfig.getNominalMax().toDouble());
        assertEquals(4200, limitationConfig.getFailsafeLimit().toDouble());
        assertEquals(11000, limitationConfig.getLoadControlLimit().toDouble(), "default limit starts unrestricted");
        assertEquals("PT2H", limitationConfig.getFailsafeDurationMin());
    }

    @Test
    void lppMapsNominalMaxAndFailsafeToTheirOwnFieldsWithNegatedLoadControlLimit() {
        EEBusControllableSystemConfiguration config = new EEBusControllableSystemConfiguration();
        config.lppNominalMaxWatts = 5000;
        config.lppFailsafeLimitWatts = 1000;
        config.lppFailsafeDuration = "PT1H";

        SimpleLimitationConfig limitationConfig = LimitationConfigFactory.lpp(config);

        assertEquals(5000, limitationConfig.getNominalMax().toDouble());
        assertEquals(1000, limitationConfig.getFailsafeLimit().toDouble());
        assertEquals(-5000, limitationConfig.getLoadControlLimit().toDouble());
        assertEquals("PT1H", limitationConfig.getFailsafeDurationMin());
    }
}
