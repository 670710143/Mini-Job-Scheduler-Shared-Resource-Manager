import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * File Client (Java 21+): แบ่งไฟล์เป็นช่วงๆ ให้ worker แต่ละตัวโหลดพร้อมกัน
 * - แต่ละ worker มี connection, offset, length และ file handle/channel ของตัวเอง ช่วงไม่ซ้อนกัน
 * - เขียนลง final file ตาม offset (ไฟล์ถูกจองขนาดไว้ก่อน)
 * - โหมด io  (พอร์ต <port>)   : InputStream.read + RandomAccessFile.write
 * - โหมด nio (พอร์ต <port+1>) : FileChannel.transferFrom(SocketChannel, position, count)
 *
 * วิธีรัน:
 *   java FileClient.java <host> <port> list
 *   java FileClient.java <host> <port> info  <file>             (ขอ INFO + HASH ของไฟล์)
 *   java FileClient.java <host> <port> get   <file> <io|nio> [workers=10] [runs=3]
 *   java FileClient.java <host> <port> bench <file> [runs=3]    (รันครบ io/nio x 1/10 workers)
 */
public class FileClient {

    static final int BUF_SIZE = 64 * 1024;

    public static void main(String[] a) throws Exception {
        if (a.length < 3) { usage(); return; }
        String host = a[0];
        int port = Integer.parseInt(a[1]);
        switch (a[2].toLowerCase()) {
            case "list" -> list(host, port);
            case "info" -> {
                if (a.length < 4) { usage(); return; }
                System.out.println(request(host, port, "INFO " + a[3]));
                System.out.println(request(host, port, "HASH " + a[3]));
            }
            case "get" -> {
                if (a.length < 5) { usage(); return; }
                int workers = a.length > 5 ? Integer.parseInt(a[5]) : 10;
                int runs = a.length > 6 ? Integer.parseInt(a[6]) : 3;
                Meta m = meta(host, port, a[3]);
                if (m != null) runCase(host, port, a[3], m, a[4].equalsIgnoreCase("nio"), workers, runs);
            }
            case "bench" -> {
                if (a.length < 4) { usage(); return; }
                bench(host, port, a[3], a.length > 4 ? Integer.parseInt(a[4]) : 3);
            }
            default -> usage();
        }
    }

    static void usage() {
        System.out.println("=======================[ COMMAND ]=======================\n  > java FileClient.java <host> <port> list\n"
                + "  > java FileClient.java <host> <port> info <file>\n"
                + "  > java FileClient.java <host> <port> get <file> <io|nio> [workers= xx ] [runs= xx ]\n"
                + "  > java FileClient.java <host> <port> bench <file> [runs= xx ]\n"
                + "  >>> [IO] port : XXXX | [NIO] port: XXXX +1\n" + "=======================++++++++++=======================");
    }

    // ---------- คำสั่งควบคุม (LIST / INFO / HASH) ----------
    static void list(String host, int port) throws IOException {
        try (Socket s = new Socket(host, port)) {
            s.getOutputStream().write("LIST\nQUIT\n".getBytes(StandardCharsets.UTF_8));
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line;
            while ((line = readLine(in)) != null && !line.equals("END")) System.out.println(line);
        }
    }

    static String request(String host, int port, String cmd) throws IOException {
        try (Socket s = new Socket(host, port)) {
            s.getOutputStream().write((cmd + "\nQUIT\n").getBytes(StandardCharsets.UTF_8));
            return readLine(new BufferedInputStream(s.getInputStream()));
        }
    }

    record Meta(long size, String hash) { }

    /** ขอข้อมูลไฟล์ก่อนโหลด: INFO (ขนาด) และ HASH (ไว้ตรวจหลังโหลด) */
    static Meta meta(String host, int port, String name) throws IOException {
        String info = request(host, port, "INFO " + name);
        if (info == null || !info.startsWith("SIZE ")) {
            System.err.println("[ Server ] : " + info);          // เช่น ERROR 404 File not found
            return null;
        }
        long size = Long.parseLong(info.substring(5).trim());
        if (size == 0) { System.err.println("Empty File"); return null; }
        String hl = request(host, port, "HASH " + name);
        String hash = (hl != null && hl.startsWith("HASH ")) ? hl.substring(5).trim() : null;
        return new Meta(size, hash);
    }

    // ---------- ทดลองและวัดผล ----------
    record Result(String mode, int workers, double[] secs, boolean verified, long size) {
        double avg() { return Arrays.stream(secs).average().orElse(0); }
        double mbps() { return size / 1048576.0 / avg(); }
    }

    /** รัน 1 กรณี (โหมด x จำนวน worker) ซ้ำ runs ครั้ง พิมพ์ผลทุกรอบ */
    static Result runCase(String host, int port, String name, Meta m, boolean nio, int workers, int runs) throws Exception {
        int p = nio ? port + 1 : port;
        String mode = nio ? "NIO" : "IO";
        Files.createDirectories(Paths.get("downloads"));
        Path out = Paths.get("downloads", name);
        System.out.printf("%n file %s %.2f MB | Mode %s (Port %d) | %d workers | %d round %n",
                name, m.size() / 1048576.0, mode, p, workers, runs);

        double[] secs = new double[runs];
        boolean allOk = true;
        for (int r = 0; r < runs; r++) {
            Files.deleteIfExists(out);
            long t0 = System.nanoTime();
            downloadOnce(host, p, name, m.size(), nio, workers, out);
            secs[r] = (System.nanoTime() - t0) / 1e9;          // เวลารวม: จองไฟล์ + โหลดทุกช่วง (ไม่รวมเวลาคำนวณ hash)

            boolean sizeOk = Files.size(out) == m.size();
            String hashState = m.hash() == null ? "N/A" : (sha256(out).equals(m.hash()) ? "OK" : "MISMATCH");
            allOk &= sizeOk && !hashState.equals("MISMATCH");
            System.out.printf("  round %d: %.3f s | %.1f MB/s | size %s | hash %s%n",
                    r + 1, secs[r], m.size() / 1048576.0 / secs[r], sizeOk ? "OK" : "WRONG", hashState);
        }
        Result res = new Result(mode, workers, secs, allOk, m.size());
        System.out.printf("  Summary: average %.3f s (min %.3f, max %.3f) | %.1f MB/s%n", res.avg(),
                Arrays.stream(secs).min().orElse(0), Arrays.stream(secs).max().orElse(0), res.mbps());
        return res;
    }

    /** รันครบ 4 กรณี: IO/NIO x 1/10 workers แล้วพิมพ์ตารางสรุป */
    static void bench(String host, int port, String name, int runs) throws Exception {
        Meta m = meta(host, port, name);
        if (m == null) return;
        // วอร์มอัพ 1 รอบ (ไม่นับผล) ให้ไฟล์อยู่ใน page cache ทุกกรณีเท่ากัน
        System.out.println("... Warm up  ..."); // ไม่นับผลในรอบนี้
        Path out = Paths.get("downloads", name);
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);
        downloadOnce(host, port, name, m.size(), false, 10, out);

        List<Result> all = new ArrayList<>();
        for (boolean nio : new boolean[]{false, true})
            for (int w : new int[]{1, 10})
                all.add(runCase(host, port, name, m, nio, w, runs));

        System.out.printf("%n========== Summary (%s, %.2f MB, %d per case) ==========%n", name, m.size() / 1048576.0, runs);
        System.out.println("Mode | workers | time per round (s)          | average (s) | MB/s    | size/hash");
        for (Result r : all) {
            StringBuilder t = new StringBuilder();
            for (double v : r.secs()) t.append(String.format("%.3f ", v));
            System.out.printf("%-4s | %7d | %-26s | %10.3f | %7.1f | %s%n",
                    r.mode(), r.workers(), t.toString().trim(), r.avg(), r.mbps(), r.verified() ? "OK" : "FAIL");
        }
    }

    static void downloadOnce(String host, int port, String name, long size,
                             boolean nio, int workers, Path out) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(out.toFile(), "rw")) {
            raf.setLength(size);                               // จองขนาดไว้ก่อน ทุก worker เขียนคนละช่วง
        }
        int parts = (int) Math.min(workers, size);
        long chunk = size / parts;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> fs = new ArrayList<>();
            for (int i = 0; i < parts; i++) {
                long off = i * chunk;
                long len = (i == parts - 1) ? size - off : chunk;   // worker สุดท้ายรับเศษ
                fs.add(pool.submit(() -> {
                    if (nio) workerNio(host, port, name, off, len, out);
                    else workerIo(host, port, name, off, len, out);
                    return null;
                }));
            }
            try {
                for (Future<Void> f : fs) f.get();
            } catch (ExecutionException e) {
                pool.shutdownNow();
                Throwable c = e.getCause();
                throw (c instanceof Exception) ? (Exception) c : e;
            }
        }
    }

    // ---------- worker แบบ Traditional I/O ----------
    static void workerIo(String host, int port, String name, long off, long len, Path out) throws IOException {
        try (Socket s = new Socket(host, port);
             RandomAccessFile raf = new RandomAccessFile(out.toFile(), "rw")) {   // file handle ของ worker นี้เอง
            s.setTcpNoDelay(true);
            OutputStream os = s.getOutputStream();
            os.write(("GET " + name + " " + off + " " + len + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), BUF_SIZE);
            expectOk(readLine(in), len);

            raf.seek(off);
            byte[] buf = new byte[BUF_SIZE];
            long remaining = len;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new EOFException("ได้ข้อมูลไม่ครบ ขาดอีก " + remaining + " byte");
                raf.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    // ---------- worker แบบ NIO ----------
    static void workerNio(String host, int port, String name, long off, long len, Path out) throws IOException {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress(host, port));
             FileChannel fc = FileChannel.open(out, StandardOpenOption.WRITE)) {  // channel ของ worker นี้เอง
            sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
            ByteBufferUtil.writeAll(sc, "GET " + name + " " + off + " " + len + "\n");

            // อ่าน header แบบไม่ buffer เพื่อไม่ให้กลืน payload ที่ transferFrom ต้องอ่านต่อ
            expectOk(readLine(Channels.newInputStream(sc)), len);

            long pos = off, remaining = len;
            while (remaining > 0) {
                long n = fc.transferFrom(sc, pos, remaining);
                if (n <= 0) throw new EOFException("ได้ข้อมูลไม่ครบ ขาดอีก " + remaining + " byte");
                pos += n;
                remaining -= n;
            }
        }
    }

    static class ByteBufferUtil {
        static void writeAll(SocketChannel sc, String text) throws IOException {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (b.hasRemaining()) sc.write(b);
        }
    }

    static void expectOk(String header, long len) throws IOException {
        if (header == null) throw new EOFException("Server shutdown connection");
        if (!header.equals("OK " + len)) throw new IOException("[ Server ]: " + header);
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return bo.toString(StandardCharsets.UTF_8).trim();
            bo.write(c);
            if (bo.size() > 4096) throw new IOException("header is too long");
        }
        return bo.size() == 0 ? null : bo.toString(StandardCharsets.UTF_8).trim();
    }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] b = new byte[1 << 20];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
