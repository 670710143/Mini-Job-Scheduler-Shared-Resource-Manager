import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * File Server (Java 21+)
 * - 1 connection = 1 virtual thread
 * - เปิดพร้อมกัน 2 พอร์ต เพื่อให้เทียบกันโดยใช้ไฟล์และเครื่องเดียวกัน:
 *     พอร์ต <port>   = Traditional I/O : ServerSocket + RandomAccessFile.read + OutputStream.write
 *     พอร์ต <port+1> = NIO             : ServerSocketChannel + FileChannel.transferTo (native/zero-copy ถ้า OS รองรับ)
 *
 * Protocol (ข้อความ 1 บรรทัดต่อ 1 คำสั่ง ลงท้ายด้วย \n):
 *   LIST                      -> FILE <name> <size> (หลายบรรทัด) แล้วปิดด้วย END
 *   INFO <name>               -> SIZE <bytes> | ERROR <code> <msg>
 *   HASH <name>               -> HASH <sha256-hex> | ERROR ...
 *   GET <name> <offset> <len> -> OK <len>\n + payload <len> bytes | ERROR ...
 *   QUIT                      -> ปิด connection
 * connection หนึ่งส่งได้หลายคำสั่งต่อเนื่อง
 *
 * วิธีรัน:  java FileServer.java <port> <shared-dir>
 */
public class FileServer {

    static final int BUF_SIZE = 64 * 1024;
    static Path dir;
    static volatile int ioPort, nioPort;      // ให้ client ถามได้ว่าพอร์ต I/O และ NIO คือเลขอะไร
    static final Map<String, String> hashCache = new ConcurrentHashMap<>();

    // ---------- abstraction ของ connection สองแบบ ----------
    interface Conn extends Closeable {
        String readLine() throws IOException;                       // null เมื่อ client ปิด
        void send(String text) throws IOException;
        void sendRange(Path file, long offset, long length) throws IOException;
        void flush() throws IOException;
    }

    /** Traditional I/O: Socket + stream + buffer ใน user space */
    static class IoConn implements Conn {
        private final Socket s;
        private final InputStream in;
        private final OutputStream out;

        IoConn(Socket s) throws IOException {
            this.s = s;
            s.setTcpNoDelay(true);
            in = new BufferedInputStream(s.getInputStream());
            out = new BufferedOutputStream(s.getOutputStream(), BUF_SIZE);
        }

        public String readLine() throws IOException { return FileServer.readLine(in); }

        public void send(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }

        public void sendRange(Path file, long offset, long length) throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(offset);
                byte[] buf = new byte[BUF_SIZE];
                long remaining = length;
                while (remaining > 0) {
                    int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) throw new EOFException("File is shorter than expected");
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
        }

        public void flush() throws IOException { out.flush(); }
        public void close() throws IOException { s.close(); }
    }

    /** NIO: SocketChannel + FileChannel.transferTo */
    static class NioConn implements Conn {
        private final SocketChannel sc;
        private final InputStream in;

        NioConn(SocketChannel sc) throws IOException {
            this.sc = sc;
            sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
            in = new BufferedInputStream(Channels.newInputStream(sc));   // ใช้อ่านแค่บรรทัดคำสั่ง
        }

        public String readLine() throws IOException { return FileServer.readLine(in); }

        public void send(String text) throws IOException {
            ByteBuffer b = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (b.hasRemaining()) sc.write(b);
        }

        public void sendRange(Path file, long offset, long length) throws IOException {
            try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
                long pos = offset, remaining = length;
                ByteBuffer fallback = null;
                boolean useTransfer = true;
                while (remaining > 0) {                         // transferTo อาจส่งได้น้อยกว่าที่ขอ ต้องวนซ้ำ
                    long n = 0;
                    if (useTransfer) {
                        try { n = fc.transferTo(pos, remaining, sc); }
                        catch (IOException e) { useTransfer = false; }   // transferTo ใช้ไม่ได้บนระบบนี้ -> คัดลอกเองด้วย read/write
                    }
                    if (n <= 0) {
                        // บน virtual thread ตัว JDK ตั้ง socket เป็น non-blocking ภายใน เมื่อ send buffer เต็ม
                        // sendfile จะคืน 0 แทนการรอ จึงเขียนก้อนเล็กด้วย write() (ซึ่งจอด thread รอได้) แล้ววนต่อ
                        if (fallback == null) fallback = ByteBuffer.allocateDirect(BUF_SIZE);
                        fallback.clear();
                        fallback.limit((int) Math.min(BUF_SIZE, remaining));
                        int r = fc.read(fallback, pos);
                        if (r <= 0) throw new EOFException("ไฟล์สั้นกว่าที่คาด");
                        fallback.flip();
                        while (fallback.hasRemaining()) sc.write(fallback);
                        n = r;
                    }
                    pos += n;
                    remaining -= n;
                }
            }
        }

        public void flush() { /* เขียนตรงลง channel ไม่มี buffer */ }
        public void close() throws IOException { sc.close(); }
    }

    // อ่านทีละ byte จนถึง '\n' (อ่านจาก stream ที่ buffer แล้ว จึงไม่ช้า)
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return bo.toString(StandardCharsets.UTF_8).trim();
            bo.write(c);
            if (bo.size() > 4096) throw new IOException("บรรทัดคำสั่งยาวเกินไป");
        }
        return bo.size() == 0 ? null : bo.toString(StandardCharsets.UTF_8).trim();
    }

    // ---------- ประมวลผลคำสั่ง ----------
    static void serve(Conn c) {
        try (c) {
            String line;
            while ((line = c.readLine()) != null) {
                String[] t = line.split("\\s+");
                switch (t[0].toUpperCase()) {
                    case "LIST" -> doList(c);
                    case "INFO" -> doInfo(c, t);
                    case "HASH" -> doHash(c, t);
                    case "PORTS" -> c.send("PORTS " + ioPort + " " + nioPort + "\n");
                    case "GET" -> doGet(c, t);
                    case "QUIT" -> { return; }
                    default -> c.send(" >ERROR 400 Unknown command\n");
                }
                c.flush();
            }
        } catch (IOException e) {
            // client ตัดการเชื่อมต่อกลางทาง: ปิด connection นี้เฉยๆ
        }
    }

    static Path resolve(String name) {
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) return null;
        try {
            Path p = dir.resolve(name).normalize();
            return (p.startsWith(dir) && Files.isRegularFile(p)) ? p : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    static void doList(Conn c) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        StringBuilder sb = new StringBuilder();
        for (Path p : files) {
            String n = p.getFileName().toString();
            if (n.matches(".*\\s.*")) continue;               // protocol แบบแยกด้วยช่องว่าง ไม่รองรับชื่อที่มีช่องว่าง
            sb.append("FILE ").append(n).append(' ').append(Files.size(p)).append('\n');
        }
        sb.append("END\n");
        c.send(sb.toString());
    }

    static void doInfo(Conn c, String[] t) throws IOException {
        if (t.length != 2) { c.send(" >ERROR 400 Usage: INFO <filename>\n"); return; }
        Path p = resolve(t[1]);
        if (p == null) { c.send(" >ERROR 404 File not found\n"); return; }
        c.send("SIZE " + Files.size(p) + "\n");
    }

    static void doHash(Conn c, String[] t) throws IOException {
        if (t.length != 2) { c.send(" >ERROR 400 Usage: HASH <filename>\n"); return; }
        Path p = resolve(t[1]);
        if (p == null) { c.send(" >ERROR 404 File not found\n"); return; }
        String key = p + ":" + Files.size(p) + ":" + Files.getLastModifiedTime(p).toMillis();
        String h = hashCache.get(key);
        if (h == null) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = Files.newInputStream(p)) {
                    byte[] b = new byte[1 << 20];
                    int n;
                    while ((n = in.read(b)) > 0) md.update(b, 0, n);
                }
                h = HexFormat.of().formatHex(md.digest());
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            hashCache.put(key, h);
        }
        c.send("HASH " + h + "\n");
    }

    static void doGet(Conn c, String[] t) throws IOException {
        if (t.length != 4) { c.send("> ERROR 400 Usage: GET <filename> <offset> <length>\n"); return; }
        long off, len;
        try {
            off = Long.parseLong(t[2]);
            len = Long.parseLong(t[3]);
        } catch (NumberFormatException e) {
            c.send(" > ERROR 400 offset/length must be numbers\n");
            return;
        }
        Path p = resolve(t[1]);
        if (p == null) { c.send("ERROR 404 File not found\n"); return; }
        long size = Files.size(p);
        if (off < 0 || len <= 0 || off >= size || len > size - off) {
            c.send(" > ERROR 416 Invalid range (file size " + size + ")\n");
            return;
        }
        c.send("OK " + len + "\n");
        c.flush();
        c.sendRange(p, off, len);      // payload เท่ากับ len byte พอดี
    }

    // ---------- main ----------
    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.out.println("> COMMAND : java FileServer.java <port> <shared-dir>   [IO] port : XXXX | [NIO] port: XXXX +1");
            return;
        }
        int port = Integer.parseInt(a[0]);
        ioPort = port;
        nioPort = port + 1;
        dir = Paths.get(a[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            System.err.println(" >Folder not found: " + dir);
            return;
        }
        System.out.println(" Folder " + dir);
        System.out.println("  Traditional I/O  port " + port);
        System.out.println("  NIO transferTo   port " + (port + 1));
       
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (ni.isUp() && !ni.isLoopback())
                    for (InetAddress ia : Collections.list(ni.getInetAddresses()))
                        if (ia instanceof Inet4Address) System.out.println("  Reachable at " + ia.getHostAddress());
        } catch (Exception ignored) { }

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();   // 1 connection = 1 virtual thread
        Thread ioLoop = Thread.ofPlatform().name("accept-io").start(() -> acceptIo(port, pool));
        Thread nioLoop = Thread.ofPlatform().name("accept-nio").start(() -> acceptNio(port + 1, pool));
        // คอนโซลควบคุม server: พิมพ์ quit / exit / q เพื่อปิดโปรแกรม (หรือกด Ctrl+C)
        Thread.ofPlatform().daemon(true).name("console").start(() -> {
            System.out.println("  Type 'quit' to stop the server  |  'help' for commands");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
                String l;
                while ((l = br.readLine()) != null) {
                    switch (l.trim().toLowerCase()) {
                        case "quit", "exit", "q" -> {
                            System.out.println(" Shutting down server...");
                            pool.shutdownNow();          // ตัด connection ที่ยังโหลดค้างอยู่
                            System.exit(0);
                        }
                        case "help" -> System.out.println("  quit | exit | q   stop the server");
                        case "" -> { }
                        default -> System.out.println("  Unknown command (type 'help')");
                    }
                }
            } catch (IOException ignored) { }
        });
        ioLoop.join();
        nioLoop.join();
    }

    static void acceptIo(int port, ExecutorService pool) {
        try (ServerSocket ss = new ServerSocket(port, 128)) {
            while (true) {
                Socket s = ss.accept();
                pool.submit(() -> {
                    try { serve(new IoConn(s)); } catch (IOException ignored) { }
                });
            }
        } catch (IOException e) {
            System.err.println("> Port " + port + " can't use: " + e);
        }
    }

    static void acceptNio(int port, ExecutorService pool) {
        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            ssc.bind(new InetSocketAddress(port), 128);
            while (true) {
                SocketChannel sc;
                try {
                    sc = ssc.accept();
                } catch (ClosedChannelException e) {
                    throw e;
                } catch (IOException e) {            // accept พลาดชั่วคราว: อย่าให้ NIO acceptor ตายทั้งตัว
                    System.err.println("> NIO accept error: " + e);
                    continue;
                }
                pool.submit(() -> {
                    try { serve(new NioConn(sc)); } catch (IOException ignored) { }
                });
            }
        } catch (IOException e) {
            System.err.println("> Port " + port + " can't use: " + e);
        }
    }
}
