package com.jingyicare.aims_jd.driver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.tool.TxtDumper;
import com.jingyicare.aims_jd.utils.Consts;

/**
 * 通用连接生命周期基类。
 * 第一阶段只保留 socket 读写、心跳、关闭和并发防抖，不再承载协议解析。
 */
@Slf4j
public abstract class DeviceConnHandler {
    public enum OpType {
        HANDLE_READ,
        HANDLE_HEARTBEAT,
        HANDLE_CLOSE
    }

    protected DeviceConnHandler(SocketChannel socketChannel, String ip) {
        this.socketChannel = socketChannel;
        this.ip = ip;
        this.readBuffer = ByteBuffer.allocate(Consts.CHANNEL_READ_BUFFER_SIZE);
        this.isAlive = true;
        this.closed = new AtomicBoolean(false);
        this.reading = new AtomicBoolean(false);
        this.connLock = new ReentrantLock();
        this.lastHeartbeatNs = System.nanoTime();
    }

    public final void extractMessages(SelectionKey key) {
        if (!isAlive) {
            return;
        }
        if (!reading.compareAndSet(false, true)) {
            return;
        }

        try {
            byte[] chunk;
            try {
                chunk = readChunk(key);
            } catch (IOException e) {
                close(key);
                return;
            }
            if (chunk == null || chunk.length == 0 || !isAlive) {
                return;
            }

            connLock.lock();
            try {
                if (!isAlive) {
                    return;
                }
                onBytes(chunk, key);
            } catch (Throwable t) {
                log.error("handle read chunk error, closing channel", t);
                closeWithoutLock(key);
            } finally {
                connLock.unlock();
            }
        } finally {
            reading.set(false);
        }
    }

    public final void sendHeartbeat() {
        if (!isAlive) {
            return;
        }

        long nowNs = System.nanoTime();
        if (nowNs - lastHeartbeatNs >= TimeUnit.SECONDS.toNanos(Consts.CHANNEL_CONNECT_TIMEOUT_SECS)) {
            close(null);
            return;
        }

        boolean acquired = false;
        try {
            acquired = connLock.tryLock(Consts.DEVICE_HEARTBEAT_TRY_LOCK_MS, TimeUnit.MILLISECONDS);
            if (!acquired || !isAlive) {
                return;
            }
            onHeartbeat();
        } catch (InterruptedException e) {
            close(null);
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.error("heartbeat error, closing channel", t);
            closeWithoutLock(null);
        } finally {
            if (acquired) {
                connLock.unlock();
            }
        }
    }

    public void close(SelectionKey key) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        connLock.lock();
        try {
            closeResources(key);
        } finally {
            connLock.unlock();
        }
    }

    protected void closeWithoutLock(SelectionKey key) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeResources(key);
    }

    public boolean isAlive() {
        return isAlive;
    }

    protected void touchHeartbeat() {
        lastHeartbeatNs = System.nanoTime();
    }

    protected void setStale() {
        isAlive = false;
    }

    protected abstract void onBytes(byte[] chunk, SelectionKey key) throws Exception;

    protected void onHeartbeat() throws Exception {
        // default no-op
    }

    protected void onDisconnected() {
        // default no-op
    }

    protected final SocketChannel socketChannel;
    protected final String ip;
    protected final ByteBuffer readBuffer;
    protected final ReentrantLock connLock;
    protected volatile boolean isAlive;
    protected final AtomicBoolean closed;
    protected final AtomicBoolean reading;
    protected volatile long lastHeartbeatNs;

    private byte[] readChunk(SelectionKey key) throws IOException {
        readBuffer.clear();
        int bytesRead = socketChannel.read(readBuffer);
        if (bytesRead == -1) {
            close(key);
            return null;
        }
        if (bytesRead == 0) {
            return null;
        }

        touchHeartbeat();
        readBuffer.flip();
        byte[] chunk = new byte[readBuffer.remaining()];
        readBuffer.get(chunk);
        log.info("\n\nchunk bytes(len={}):\n{}\nchunk string1:\n{}\n\n",
            chunk.length,
            TxtDumper.bytesToString(chunk),
            TxtDumper.bytesToAsciiString(chunk)
        );
        return chunk;
    }

    private void closeResources(SelectionKey key) {
        try {
            onDisconnected();
        } catch (Throwable t) {
            log.warn("error during onDisconnected for ip={}", ip, t);
        }

        try {
            socketChannel.close();
        } catch (IOException ignore) {
        }
        try {
            if (key != null) {
                key.cancel();
            }
        } catch (Exception ignore) {
        }
        isAlive = false;
    }
}

