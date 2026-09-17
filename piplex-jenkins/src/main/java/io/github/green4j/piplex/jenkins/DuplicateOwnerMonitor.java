/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.jenkins;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;

/**
 * Tells an administrator that another live controller uses this controller's owner id.
 *
 * <p>A designation names an owner id, so while two controllers share one it names both of them. The
 * lease still keeps them from running the same work at once; which of them does is left to chance.
 *
 * <p>Also tells when that check cannot be made: a heartbeat that cannot be written -- an ACL without
 * {@code piplex/instances/} is the usual reason -- would otherwise leave this monitor quiet for good.
 */
@Extension
public final class DuplicateOwnerMonitor extends AdministrativeMonitor {

    @Override
    public String getDisplayName() {
        return "Piplex: duplicate owner id";
    }

    @Override
    public boolean isActivated() {
        return duplicatedOwner() != null || silentOwner() != null;
    }

    /**
     * @return the owner id in question, or {@code null}
     */
    public String duplicatedOwner() {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        return configuration == null ? null : configuration.duplicatedOwner();
    }

    /**
     * @return the owner id whose heartbeat cannot be written, or {@code null}
     */
    public String silentOwner() {
        final PiplexConfiguration configuration = PiplexConfiguration.get();
        return configuration == null ? null : configuration.silentOwner();
    }
}
