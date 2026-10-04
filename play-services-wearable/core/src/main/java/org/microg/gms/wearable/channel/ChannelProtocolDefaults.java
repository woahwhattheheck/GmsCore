/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable.channel;

/** Proto2 optional scalar defaults used by the channel receive path. */
final class ChannelProtocolDefaults {
    private ChannelProtocolDefaults() {
    }

    static boolean fromChannelOperator(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    static long requestId(Long value) {
        return value != null ? value : 0L;
    }

    static boolean finalMessage(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    static int closeErrorCode(Integer value) {
        return value != null ? value : 0;
    }
}
