/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.tuya.internal.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openhab.binding.tuya.internal.data.CommandByte;
import org.openhab.binding.tuya.internal.data.Message;
import org.openhab.binding.tuya.internal.exceptions.ParseException;
import org.openhab.binding.tuya.internal.util.MessageParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This emitter is used for a connection with a device, and communicating
 * in both directions.
 *
 * @author Wim Vissers.
 *
 */
public class DeviceEventEmitter extends EventEmitter<DeviceEventEmitter.Event, Message> {

    private Future<?> task;
    private ScheduledFuture<?> heartbeat;
    private final Logger logger;
    private final String host;
    private final int port;
    private final MessageParser parser;
    private Socket clientSocket;
    private long currentSequenceNo;
    private boolean running;
    private final LinkedBlockingQueue<byte[]> queue;

    /**
     * Create a device event emitter. Use connect() after creation to establish a connection.
     *
     * @param host the ip address or host name.
     * @param port the port number to connect to.
     */
    public DeviceEventEmitter(String host, int port, MessageParser parser) {
        this.logger = LoggerFactory.getLogger(this.getClass());
        this.host = host;
        this.port = port;
        this.parser = parser;
        this.queue = new LinkedBlockingQueue<>(4);
    }

    private void connect() {
        try {
            clientSocket = new Socket(host, port);
            if (clientSocket.isConnected()) {
                emit(Event.CONNECTED, null);
            }
        } catch (UnknownHostException e) {
            emit(Event.UNKNOWN_HOST, null);
        } catch (IOException e) {
            emit(Event.IO_ERROR, null);
        }
    }

    public void set(String message, CommandByte command) throws IOException, ParseException {
        byte[] packet = parser.encode(message.getBytes(), command, currentSequenceNo++);
        queue.offer(packet);
    }

    private void disconnect() {
        if (clientSocket != null) {
            try {
                clientSocket.close();
                clientSocket = null;
                emit(Event.DISCONNECTED, null);
            } catch (IOException e) {
                emit(Event.IO_ERROR, null);
            }
        }
    }

    @Override
    public void stop() {
        disconnect();
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        if (heartbeat != null) {
            heartbeat.cancel(true);
            heartbeat = null;
        }
        super.stop();
    }

    /**
     * Create the main task as runnable that can be executed by the scheduler.
     *
     * @return a new runnable task.
     */
    private Runnable createMainTask() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                OutputStream out = null;
                InputStream in = null;
                running = true;
                byte[] buffer = new byte[1024];
                while (running) {
                    try {
                        if (clientSocket == null || !clientSocket.isConnected()) {
                            connect();
                            out = clientSocket.getOutputStream();
                            in = clientSocket.getInputStream();
                        }
                        if (out != null && !queue.isEmpty()) {
                            byte[] packet = queue.poll();
                            out.write(packet);
                            out.flush();
                        }
                        if (in != null && in.available() > 5) {
                            Thread.sleep(20);
                            int len = in.read(buffer, 0, 1024);
                            List<Message> res = parser.parse(buffer, len);
                            for (Message msg : res) {
                                emit(Event.MESSAGE_RECEIVED, msg);
                            }
                        }
                    } catch (IOException | InterruptedException e) {
                        disconnect();
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException ex) {
                        }
                    } catch (ParseException e) {
                        logger.error("Invalid message received.");
                    }
                }
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (IOException e) {
                }
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (IOException | NullPointerException e) {
                }
                disconnect();
            }
        };
        return runnable;
    }

    /**
     * Create heartbeat task.
     *
     * @return
     */
    private Runnable createHeartBeat() {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    set("", CommandByte.HEART_BEAT);
                } catch (IOException | ParseException e) {
                    // Should not happen
                }
            }
        };
        return runnable;
    }

    /**
     * Start this as a task in the executor service.
     *
     * @param scheduler
     */
    public void start(ScheduledExecutorService scheduler) {
        if (task == null) {
            task = scheduler.submit(createMainTask());
        }
        if (heartbeat == null) {
            heartbeat = scheduler.scheduleAtFixedRate(createHeartBeat(), 5, 10, TimeUnit.SECONDS);
        }
    }

    public enum Event {
        UNKNOWN_HOST,
        IO_ERROR,
        CONNECTED,
        DISCONNECTED,
        MESSAGE_RECEIVED;
    }

}