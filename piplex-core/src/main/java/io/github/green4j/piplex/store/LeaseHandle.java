/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.store;

/**
 * What a holder must present to renew or release the lease it took. Implementations carry whatever the
 * store needs; piplex only reads the two values below.
 */
public interface LeaseHandle {

    /**
     * @return the identity this lease was taken under
     */
    String ownerId();

    /**
     * The token which increases strictly on every acquisition of the key.
     *
     * <p>It is the only real safety mechanism a lease offers, and it works only where the protected
     * resource itself remembers the highest token it has seen and refuses anything lower. Where the
     * resource does not, two holders can overlap between a lease expiring and its former holder
     * noticing, and no lease duration makes that window zero.
     *
     * @return the fencing token
     */
    long fencingToken();
}
