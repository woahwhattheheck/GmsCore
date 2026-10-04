/*
 * SPDX-FileCopyrightText: 2015, microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public abstract class SocketConnectionThread extends Thread {

    private SocketWearableConnection wearableConnection;

    private SocketConnectionThread() {
        super();
    }

    protected void setWearableConnection(org.microg.gms.wearable.SocketWearableConnection wearableConnection) {
        this.wearableConnection = wearableConnection;
    }

    public SocketWearableConnection getWearableConnection() {
        return wearableConnection;
    }

    public abstract void close();

    public static SocketConnectionThread serverListen(final int port, final WearableConnection.Listener listener) {
        return new SocketConnectionThread() {
            private volatile ServerSocket serverSocket = null;
            private volatile boolean closed;
            private final ExecutorService executor = Executors.newCachedThreadPool();

            @Override
            public void close() {
                closed = true;
                executor.shutdownNow();
                ServerSocket current = serverSocket;
                if (current != null) {
                    try { current.close(); } catch (IOException ignored) {}
                }
            }

            @Override
            public void run() {
                ServerSocket current = null;
                try {
                    current = new ServerSocket();
                    serverSocket = current;
                    if (closed) {
                        current.close();
                        return;
                    }
                    current.bind(new InetSocketAddress(port));
                    while (!closed && !Thread.currentThread().isInterrupted()) {
                        final Socket accepted = current.accept();
                        if (closed) {
                            try { accepted.close(); } catch (IOException ignored) {}
                            break;
                        }
                        try {
                            executor.submit(() -> {
                                try {
                                    SocketWearableConnection connection =
                                            new SocketWearableConnection(accepted, listener);
                                    setWearableConnection(connection);
                                    connection.run();
                                } catch (IOException e) {
                                    // connection failed
                                }
                            });
                        } catch (RejectedExecutionException e) {
                            try { accepted.close(); } catch (IOException ignored) {}
                            if (closed) break;
                            throw e;
                        }
                    }
                } catch (IOException e) {
                    // quit
                } finally {
                    try {
                        if (current != null) current.close();
                    } catch (IOException ignored) {}
                    if (serverSocket == current) serverSocket = null;
                }
            }
        };
    }

    public static SocketConnectionThread clientConnect(final int port, final WearableConnection.Listener listener) {
        return new SocketConnectionThread() {
            private volatile Socket socket;
            private volatile boolean closed;

            @Override
            public void close() {
                closed = true;
                Socket current = socket;
                if (current != null) {
                    try {
                        current.close();
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public void run() {
                Socket current = null;
                try {
                    current = new Socket();
                    socket = current;
                    if (closed) {
                        current.close();
                        return;
                    }
                    current.connect(new InetSocketAddress("127.0.0.1", port));
                    if (closed) return;
                    SocketWearableConnection connection = new SocketWearableConnection(current, listener);
                    setWearableConnection(connection);
                    connection.run();
                } catch (IOException e) {
                    // quit
                } finally {
                    try {
                        if (current != null) current.close();
                    } catch (IOException e) {
                    }
                    if (socket == current) socket = null;
                }
            }
        };
    }
}
