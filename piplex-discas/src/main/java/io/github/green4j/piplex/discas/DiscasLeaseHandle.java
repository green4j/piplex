/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.piplex.discas;

import io.github.green4j.discas.client.lock.LockToken;
import io.github.green4j.piplex.store.LeaseHandle;

import java.util.Objects;

/**
 * A lease handle carrying the discas lock token that renewing and releasing need.
 *
 * <p>The token is what authorises a write; an info read never hands it out. It is kept here and nowhere
 * else, so that losing the handle means losing the ability to renew -- which is the intended shape.
 *
 * @param ownerId      who the lease was taken under
 * @param fencingToken the lock's generation, which increases on every acquisition
 * @param token        what authorises renewing and releasing
 */
record DiscasLeaseHandle(String ownerId, long fencingToken, LockToken token)
        implements LeaseHandle {

    /**
     * @param ownerId      who the lease was taken under
     * @param fencingToken the lock's generation
     * @param token        what authorises renewing and releasing
     */
    DiscasLeaseHandle {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(token, "token");
    }

    static LockToken tokenOf(final LeaseHandle handle) {
        if (handle instanceof DiscasLeaseHandle mine) {
            return mine.token();
        }
        throw new IllegalArgumentException(
                "this store only understands handles it issued, but got " + handle);
    }
}
