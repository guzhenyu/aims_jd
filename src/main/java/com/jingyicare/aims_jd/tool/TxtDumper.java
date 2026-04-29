package com.jingyicare.aims_jd.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.aims_jd.utils.*;

@Component
@Slf4j
public class TxtDumper {
    public TxtDumper(
        @Value("${txtdumper.path:}") String path,
        @Value("${txtdumper.queue.size:2048}") int queueSize
    ) {
        queue = new ArrayBlockingQueue<>(queueSize);
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "txt-dumper-writer");
            t.setDaemon(true);
            return t;
        });
        running = new AtomicBoolean(true);
        if (path == null || path.trim().isEmpty()) {
            this.filePath = null; // noop 模式
            return;
        }
        this.filePath = Paths.get(path).toAbsolutePath().normalize();

        // 启动后台写循环
        writer.submit(this::runLoop);
    }

    /**
     * 对外唯一接口：将文本写入文件。
     * 当路径为空 / 队列满 / IO 问题时，均视为 noop。
     */
    public void dump(String txt) {
        if (filePath == null || !running.get() || txt == null) {
            return; // noop
        }
        // 尽量不阻塞调用方，队列满则直接丢弃（noop）
        queue.offer(txt);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        writer.shutdown();
        try {
            // 尽量处理完已入队的数据（最多 2 秒）
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** 后台单线程写循环（顺序写 + 大文件裁剪） */
    private void runLoop() {
        while (running.get() || !queue.isEmpty()) {
            String txt = null;
            try {
                txt = queue.poll(200, TimeUnit.MILLISECONDS);
                if (txt == null) continue;
                appendWithTrim(txt);
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                // 任意异常即 noop（但打日志便于排查）
                log.warn("TxtDumper write failed, noop {}", e);
            }
        }
    }

    /** 追加写入并在必要时裁剪头部 1MB */
    private void appendWithTrim(String txt) {
        try {
            ensureParentDir();
            // 先检查大小并裁剪（使用 NIO FileChannel，效率更好）
            trimIfNeeded();

            // 追加写入（一次打开，减少句柄抖动；单线程保证顺序）
            try (FileChannel ch = FileChannel.open(filePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                byte[] data = txt.getBytes(Consts.CHARSET);
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
            }
        } catch (IOException ioe) {
            // 失败 noop
            log.warn("TxtDumper append failed, noop {}", ioe);
        }
    }

    /** 若文件大小 > 2MB，则去掉头部 1MB（拷贝尾部到临时文件再覆盖回原文件） */
    private void trimIfNeeded() throws IOException {
        if (Files.notExists(filePath)) {
            // 确保文件存在
            Files.createFile(filePath);
            return;
        }
        long size = Files.size(filePath);
        if (size <= MAX_BYTES) return;

        // 仅保留 [TRIM_HEAD_BYTES, size) 这段内容
        long keepStart = TRIM_HEAD_BYTES;
        if (keepStart >= size) {
            // 极端情况：整文件都被裁掉，清空即可
            try (FileChannel ch = FileChannel.open(filePath, StandardOpenOption.WRITE)) {
                ch.truncate(0);
            }
            return;
        }

        Path tmp = tempSibling(filePath);
        try (
                FileChannel src = FileChannel.open(filePath, StandardOpenOption.READ);
                FileChannel dst = FileChannel.open(tmp,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        ) {
            long remaining = size - keepStart;
            long pos = keepStart;
            long transferred;
            while (remaining > 0) {
                transferred = src.transferTo(pos, remaining, dst);
                if (transferred <= 0) break;
                pos += transferred;
                remaining -= transferred;
            }
        } catch (IOException e) {
            // 裁剪失败视为 noop
            safeDelete(tmp);
            throw e;
        }

        // 将 tmp 覆盖回原文件（原子替换）
        try {
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 文件系统不支持原子移动，则非原子替换
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            safeDelete(tmp);
        }
    }

    private void ensureParentDir() throws IOException {
        Path parent = filePath.getParent();
        if (parent != null && Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private static Path tempSibling(Path path) {
        String fn = path.getFileName().toString();
        String tmpName = "." + fn + ".trim." + System.nanoTime();
        return path.getParent() == null ? Paths.get(tmpName) : path.getParent().resolve(tmpName);
    }

    private static void safeDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    public static String bytesToString(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    public static String bytesToAsciiString(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            char c = (char) b;
            if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    public static byte[] stringToBytes(String s) {
        if (s == null || s.isEmpty()) return new byte[0];
        String[] parts = s.trim().split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    // 2MB 上限 + 1MB 头部裁剪
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final long TRIM_HEAD_BYTES = 1L * 1024 * 1024;

    // 队列和单线程执行器（高吞吐 + 顺序写）
    private final BlockingQueue<String> queue;
    private final ExecutorService writer;
    private final Path filePath;  // 目标文件路径（可能为 null 表示 noop）
    private final AtomicBoolean running;
}
