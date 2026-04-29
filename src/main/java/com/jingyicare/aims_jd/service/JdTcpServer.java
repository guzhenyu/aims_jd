package com.jingyicare.aims_jd.service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.tool.*;
import com.jingyicare.aims_jd.utils.*;

@Service
@Slf4j
public class JdTcpServer {
    public JdTcpServer(
        ConfigurableApplicationContext context,
        @Value("${tcp.server.port:50003}") int port,
        @Value("${tcp.server.select_timeout_ms:1000}") long selectTimeoutMs,
        @Value("${tcp.server.selector_thread_join_timeout_ms:180000}") long selectorThreadJoinTimeoutMs,
        @Autowired DeviceConnManager deviceConnManager
    ) {
        this.context = context;
        this.port = port;
        this.selectTimeoutMs = selectTimeoutMs;
        this.selectorThreadJoinTimeoutMs = selectorThreadJoinTimeoutMs;
        this.deviceConnManager = deviceConnManager;
    }

    @PostConstruct
    public void init() {
        serverThread = new Thread(this::startServer, "JdTcpServerMainThread");
        serverThread.start();
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private void startServer() {
        Selector selector = null;
        ServerSocketChannel serverChannel = null;
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false); // 非阻塞模式
            serverChannel.socket().setReuseAddress(true); // 服务端重启后端口马上可以重用
            
            // 真正开始监听连接的系统调用在这里 (bind + listen)
            serverChannel.bind(new InetSocketAddress(port));

            // 注册到 Selector，监听连接事件
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            // 保存到成员变量，供 stop() 使用
            selectorRef = selector;
            serverChannelRef = serverChannel;

            log.info("\n\nServer started on port {} and listening for connections...\n", port);

            // 循环监听事件
            while (running) {
                selector.select(selectTimeoutMs);  // !!!避免永久阻塞

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> iter = keys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove(); // 移除，防止重复处理
                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        // 有新连接
                        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                        SocketChannel client = ssc.accept();
                        deviceConnManager.accept(client, selector, key);
                    } else if (key.isReadable()) {
                        Object att = key.attachment();
                        if (!(att instanceof String)) { 
                            key.cancel(); 
                            continue; 
                        }
                        String ip = (String) key.attachment();
                        deviceConnManager.read(ip, key);
                    }
                }
            }
        } catch (ClosedSelectorException e) {
            log.info("Selector closed, server loop exiting");
        } catch (IOException e) {
            log.error("IO Exception in TCP server", e);
        } finally {
            closeQuietly(serverChannel);
            closeQuietly(selector);
            selectorRef = null;
            serverChannelRef = null;
            log.info("Server stopped.");
        }
    }

    public void stop() {
        running = false;

        // 唤醒 select()
        Selector sel = selectorRef;
        if (sel != null) {
            try { sel.wakeup(); } catch (Exception ignore) {}
        }

        // 关闭 serverChannel 以促使 OP_ACCEPT/OP_READ 返回
        ServerSocketChannel ssc = serverChannelRef;
        if (ssc != null) {
            try { ssc.close(); } catch (IOException ignore) {}
        }

        // 兜底：关闭 selector
        if (sel != null && sel.isOpen()) {
            try { sel.close(); } catch (IOException ignore) {}
        }

        if (serverThread != null && serverThread.isAlive()) {
            try {
                serverThread.join(selectorThreadJoinTimeoutMs);
                log.info("TCP server thread joined successfully");
            } catch (InterruptedException e) {
                log.warn("Interrupted while joining server thread", e);
                Thread.currentThread().interrupt();
            }
        }

        log.info(">>> JdTcpServer stopped.");
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignore) {}
    }
    private static void closeQuietly(Selector s) {
        if (s != null) try { s.close(); } catch (IOException ignore) {}
    }
    private static void closeQuietly(Channel ch) {
        if (ch != null) try { ch.close(); } catch (IOException ignore) {}
    }

    private String getRemoteIpFromSocketChannel(SocketChannel channel) {
        if (channel == null) return "";
        try {
            SocketAddress remote = channel.getRemoteAddress();
            if (remote instanceof InetSocketAddress) {
                InetSocketAddress inetSocketAddress = (InetSocketAddress) remote;
                InetAddress addr = inetSocketAddress.getAddress();
                return addr != null ? addr.getHostAddress() : "";
            }
            return "";
        } catch (IOException e) {
            log.warn("Failed to get remote address from SocketChannel: {}", e.toString());
            return "";
        }
    }

    private final ConfigurableApplicationContext context;
    private final int port;
    private final long selectTimeoutMs;
    private final long selectorThreadJoinTimeoutMs;
    private final DeviceConnManager deviceConnManager;

    private volatile boolean running = true;
    private volatile Selector selectorRef;
    private volatile ServerSocketChannel serverChannelRef;
    private Thread serverThread;
}
