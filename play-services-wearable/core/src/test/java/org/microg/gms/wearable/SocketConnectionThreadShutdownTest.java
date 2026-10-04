/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.wearable;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;

public class SocketConnectionThreadShutdownTest {
    @Test public void closeBeforeStartKeepsServerShutdownSticky() throws Exception {
        SocketConnectionThread thread = SocketConnectionThread.serverListen(0, null);

        thread.close();
        thread.start();
        thread.join(TimeUnit.SECONDS.toMillis(2));

        try {
            assertFalse("closed server thread must not block in accept after start", thread.isAlive());
        } finally {
            thread.close();
            thread.join(TimeUnit.SECONDS.toMillis(2));
        }
    }
}
