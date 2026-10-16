import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * MultiThread File Downloader - Modern UX/UI Edition + Download History
 *
 * History table format (one row per Start Download click):
 *   Run | Started | File | Mode | workers | time per round (s) | average (s) | MB/s | size/hash
 */
public class FileClientGui extends JFrame {

    // ---------- Design tokens ----------
    static final Color BG_MAIN = new Color(0xF1F5F9);
    static final Color CARD_BG = Color.WHITE;
    static final Color SURFACE_ALT = new Color(0xF8FAFC);
    static final Color TEXT_MAIN = new Color(0x0F172A);
    static final Color TEXT_MUTED = new Color(0x475569);
    static final Color TEXT_FAINT = new Color(0x94A3B8);
    static final Color PRIMARY_BLUE = new Color(0x1D4ED8);
    static final Color SUCCESS_GREEN = new Color(0x047857);
    static final Color DANGER_RED = new Color(0xDC2626);
    static final Color DISABLED_GRAY = new Color(0xE2E8F0);
    static final Color PROGRESS_YELLOW = new Color(0xF59E0B);
    static final Color BORDER_COLOR = new Color(0xCBD5E1);
    static final Color NIO_PURPLE = new Color(0x7C3AED);

    static final Font UI_FONT = new Font("SansSerif", Font.PLAIN, 13);
    static final Font BOLD_FONT = new Font("SansSerif", Font.BOLD, 13);
    static final Font HEADER_FONT = new Font("SansSerif", Font.BOLD, 15);
    static final Font SMALL_FONT = new Font("SansSerif", Font.PLAIN, 11);

    static final int BUF = 64 * 1024;
    static final int NIO_CHUNK = 1 << 20;

    static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    enum State { IDLE, RUNNING, VERIFYING, DONE, FAILED, CANCELLED }

    // ---------- Inputs ----------
    final JTextField hostField = field("localhost", 12);
    final JTextField portField = field("9000", 6);
    final JTextField saveDirField = field("downloads", 18);
    final JTextField workersField = field("10", 4);
    final JTextField roundsField = field("1", 3);

    final MinimalButton connectBtn = new MinimalButton("Connect", PRIMARY_BLUE, Color.WHITE);
    final MinimalButton ioBtn = new MinimalButton("I/O", CARD_BG, TEXT_MAIN, true);
    final MinimalButton nioBtn = new MinimalButton("NIO", CARD_BG, TEXT_MAIN, true);
    final MinimalButton minusBtn = new MinimalButton("-", CARD_BG, TEXT_MAIN, true);
    final MinimalButton plusBtn = new MinimalButton("+", CARD_BG, TEXT_MAIN, true);
    final MinimalButton browseBtn = new MinimalButton("Browse", CARD_BG, TEXT_MAIN, true);
    final MinimalButton infoBtn = new MinimalButton("File Info", CARD_BG, TEXT_MAIN, true);
    final MinimalButton downloadBtn = new MinimalButton("Start Download", PRIMARY_BLUE, Color.WHITE);
    final MinimalButton cancelBtn = new MinimalButton("Cancel", DANGER_RED, Color.WHITE);
    final MinimalButton openFolderBtn = new MinimalButton("Open Folder", SUCCESS_GREEN, Color.WHITE);
    final MinimalButton copySummaryBtn = new MinimalButton("Copy Summary", CARD_BG, TEXT_MAIN, true);
    final MinimalButton exportCsvBtn = new MinimalButton("Export CSV", CARD_BG, TEXT_MAIN, true);
    final MinimalButton clearHistoryBtn = new MinimalButton("Clear", CARD_BG, DANGER_RED, true);

    // ---------- File table ----------
    final DefaultTableModel tableModel = new DefaultTableModel(new Object[]{"File Name", "Size"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    final JTable fileTable = new EmptyHintTable(tableModel, "Connect to a server to list available files");

    // ---------- History table ----------
    static final String[] HISTORY_COLS = {"Run", "Started", "File", "Mode", "Workers",
            "Time per round (s)", "Average (s)", "MB/s", "Size/Hash"};
    final DefaultTableModel historyModel = new DefaultTableModel(HISTORY_COLS, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    final JTable historyTable = new EmptyHintTable(historyModel, "No runs yet - completed downloads will appear here");
    final Map<Integer, Case> cases = new LinkedHashMap<>();   // key = run id, touched on EDT only
    final Map<Integer, String> jobStartTimes = new HashMap<>(); // run id -> start timestamp, EDT only
    int jobSeq = 0;                                           // increments on every Start Download

    /** One run = one Start Download click; holds every round's time. */
    static class Case {
        int run;
        String startedAt = "";
        String file, mode;
        int workers;
        long size;
        final List<Double> times = new ArrayList<>();
        boolean sizeOk = true;
        String hashStatus = "OK";   // OK | Mismatch | N/A

        double avg() {
            return times.isEmpty() ? 0 : times.stream().mapToDouble(Double::doubleValue).sum() / times.size();
        }
        double mbps() {
            double a = avg();
            return a <= 0 ? 0 : (size / 1048576.0) / a;
        }
        String timesText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < times.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("%.2f", times.get(i)));
            }
            return sb.toString();
        }
        String sizeHash() { return (sizeOk ? "OK" : "Err") + "/" + hashStatus; }
    }

    // ---------- Telemetry ----------
    final JLabel percentLabel = new JLabel("0%");
    final JLabel detailLabel = new JLabel("Ready to start transfer");
    final JLabel resultLabel = new JLabel(" ");
    final JLabel statusLeft = new JLabel("System Idle");
    final JLabel statusRight = new JLabel();
    final JLabel lanesTitle = label("Worker Lanes (0 Active)");
    final MinimalProgressBar overallBar = new MinimalProgressBar(14);
    final LanesPanel lanesPanel = new LanesPanel();
    final StatusPill pill = new StatusPill();
    final StatCard speedCard = new StatCard("SPEED");
    final StatCard elapsedCard = new StatCard("ELAPSED");
    final StatCard etaCard = new StatCard("ETA");

    // ---------- Runtime state ----------
    boolean useNio = false;
    volatile State state = State.IDLE;
    volatile boolean cancelled;
    volatile long totalSize;
    volatile int parts;
    volatile long[] starts, ends;
    volatile AtomicLongArray done;
    final AtomicLong totalDone = new AtomicLong();
    final List<Closeable> openResources = new CopyOnWriteArrayList<>();
    volatile long startNs;
    Path lastDownloadedFile = null;

    public FileClientGui() {
        super("MultiThread File Downloader");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(BG_MAIN);
        setPreferredSize(new Dimension(1260, 900));

        styleTable(fileTable, 34);
        styleTable(historyTable, 30);
        setupFileTable();
        setupHistoryTable();

        add(new HeaderPanel(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        addTooltips();
        wireEvents();
        setProtocolMode(false);
        setState(State.IDLE);
        pack();
        setMinimumSize(new Dimension(1100, 800));
        setLocationRelativeTo(null);
    }

    // =====================================================================
    // Layout
    // =====================================================================
    private JComponent buildBody() {
        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setBackground(BG_MAIN);
        body.setBorder(new EmptyBorder(16, 16, 12, 16));

        JPanel top = new JPanel(new GridLayout(1, 2, 16, 0));
        top.setOpaque(false);
        top.add(buildLeftControlPanel());
        top.add(buildRightTelemetryPanel());

        body.add(top, BorderLayout.CENTER);
        body.add(buildHistoryPanel(), BorderLayout.SOUTH);
        return body;
    }

    private JComponent buildLeftControlPanel() {
        JPanel panel = new MinimalPanel(CARD_BG);
        panel.setLayout(new BorderLayout(0, 14));
        panel.setBorder(new EmptyBorder(18, 20, 18, 20));

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);

        form.add(left(sectionTitle("Connection & Configuration")));
        form.add(Box.createVerticalStrut(12));
        form.add(row(rowLabel("Host"), hostField, label("Port"), portField, connectBtn));
        form.add(Box.createVerticalStrut(8));
        form.add(row(rowLabel("Engine"), ioBtn, nioBtn));
        form.add(Box.createVerticalStrut(8));
        form.add(row(rowLabel("Workers"), minusBtn, workersField, plusBtn, label("Rounds"), roundsField));
        form.add(Box.createVerticalStrut(8));
        form.add(row(rowLabel("Save Dir"), saveDirField, browseBtn));
        form.add(Box.createVerticalStrut(16));
        form.add(left(sectionTitle("Available Files on Server")));
        form.add(Box.createVerticalStrut(8));
        panel.add(form, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(fileTable);
        sp.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
        sp.getViewport().setBackground(CARD_BG);
        panel.add(sp, BorderLayout.CENTER);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionRow.setOpaque(false);
        actionRow.add(downloadBtn);
        actionRow.add(infoBtn);
        actionRow.add(cancelBtn);
        actionRow.add(openFolderBtn);
        openFolderBtn.setVisible(false);
        panel.add(actionRow, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildRightTelemetryPanel() {
        JPanel panel = new MinimalPanel(CARD_BG);
        panel.setLayout(new BorderLayout(0, 12));
        panel.setBorder(new EmptyBorder(18, 20, 18, 20));

        JPanel topInfo = new JPanel();
        topInfo.setLayout(new BoxLayout(topInfo, BoxLayout.Y_AXIS));
        topInfo.setOpaque(false);

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.add(sectionTitle("Live Telemetry"), BorderLayout.WEST);
        titleRow.add(pill, BorderLayout.EAST);
        titleRow.setAlignmentX(LEFT_ALIGNMENT);
        titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        topInfo.add(titleRow);
        topInfo.add(Box.createVerticalStrut(8));

        percentLabel.setFont(new Font("SansSerif", Font.BOLD, 44));
        percentLabel.setForeground(TEXT_MAIN);
        percentLabel.setAlignmentX(LEFT_ALIGNMENT);
        topInfo.add(percentLabel);
        topInfo.add(Box.createVerticalStrut(6));
        topInfo.add(left(overallBar));
        topInfo.add(Box.createVerticalStrut(10));

        detailLabel.setFont(UI_FONT);
        detailLabel.setForeground(TEXT_MUTED);
        topInfo.add(left(detailLabel));
        topInfo.add(Box.createVerticalStrut(2));
        resultLabel.setFont(BOLD_FONT);
        topInfo.add(left(resultLabel));
        topInfo.add(Box.createVerticalStrut(10));

        JPanel cards = new JPanel(new GridLayout(1, 3, 10, 0));
        cards.setOpaque(false);
        cards.add(speedCard);
        cards.add(elapsedCard);
        cards.add(etaCard);
        cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
        topInfo.add(left(cards));
        topInfo.add(Box.createVerticalStrut(14));
        lanesTitle.setFont(BOLD_FONT);
        topInfo.add(left(lanesTitle));
        topInfo.add(Box.createVerticalStrut(4));

        panel.add(topInfo, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(lanesPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
        sp.getViewport().setBackground(CARD_BG);
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildHistoryPanel() {
        JPanel panel = new MinimalPanel(CARD_BG);
        panel.setLayout(new BorderLayout(0, 10));
        panel.setBorder(new EmptyBorder(14, 20, 16, 20));

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(sectionTitle("Download History"), BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.setOpaque(false);
        btns.add(copySummaryBtn);
        btns.add(exportCsvBtn);
        btns.add(clearHistoryBtn);
        head.add(btns, BorderLayout.EAST);
        panel.add(head, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(historyTable);
        sp.setPreferredSize(new Dimension(100, 190));
        sp.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
        sp.getViewport().setBackground(CARD_BG);
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(TEXT_MAIN);
        bar.setBorder(new EmptyBorder(8, 20, 8, 20));
        statusLeft.setFont(UI_FONT);
        statusLeft.setForeground(Color.WHITE);
        statusRight.setFont(UI_FONT);
        statusRight.setForeground(TEXT_FAINT);
        bar.add(statusLeft, BorderLayout.WEST);
        bar.add(statusRight, BorderLayout.EAST);
        return bar;
    }

    private void setupFileTable() {
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.setDefaultRenderer(Object.class, new TableRenderer() {
            @Override void customize(JTable t, Object v, int row, int col) {
                setHorizontalAlignment(col == 1 ? SwingConstants.RIGHT : SwingConstants.LEFT);
                if (col == 1) setForeground(TEXT_MUTED);
            }
        });
        fileTable.getColumnModel().getColumn(1).setMaxWidth(110);
    }

    private void setupHistoryTable() {
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyTable.setDefaultRenderer(Object.class, new TableRenderer() {
            @Override void customize(JTable t, Object v, int row, int col) {
                String s = String.valueOf(v);
                switch (col) {
                    case 0, 1 -> {
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setForeground(TEXT_MUTED);
                    }
                    case 3 -> {
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setFont(BOLD_FONT);
                        setForeground("NIO".equals(s) ? NIO_PURPLE : PRIMARY_BLUE);
                    }
                    case 4 -> setHorizontalAlignment(SwingConstants.CENTER);
                    case 6 -> setHorizontalAlignment(SwingConstants.RIGHT);
                    case 7 -> { setHorizontalAlignment(SwingConstants.RIGHT); setFont(BOLD_FONT); }
                    case 8 -> {
                        setHorizontalAlignment(SwingConstants.CENTER);
                        setFont(BOLD_FONT);
                        setForeground(s.equals("OK/OK") ? SUCCESS_GREEN
                                : s.startsWith("OK") && s.endsWith("N/A") ? TEXT_MUTED : DANGER_RED);
                    }
                    default -> setHorizontalAlignment(SwingConstants.LEFT);
                }
            }
        });
        int[] w = {45, 140, 170, 60, 70, 230, 90, 80, 100};
        for (int i = 0; i < w.length; i++) historyTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
    }

    private void addTooltips() {
        connectBtn.setToolTipText("Connect to the server and list files (Enter)");
        ioBtn.setToolTipText("Traditional blocking I/O on the base port");
        nioBtn.setToolTipText("NIO FileChannel.transferFrom on base port + 1");
        workersField.setToolTipText("Parallel connections / virtual threads (1-64)");
        roundsField.setToolTipText("Repeat the same download N times to benchmark (1-20)");
        cancelBtn.setToolTipText("Cancel the running transfer (Esc)");
        downloadBtn.setToolTipText("Download selected file (or double-click a row)");
        copySummaryBtn.setToolTipText("Copy history as a text summary to the clipboard");
        exportCsvBtn.setToolTipText("Save history as a CSV file");
    }

    // =====================================================================
    // Events
    // =====================================================================
    private void wireEvents() {
        connectBtn.addActionListener(e -> connectServer());
        hostField.addActionListener(e -> connectServer());
        portField.addActionListener(e -> connectServer());
        downloadBtn.addActionListener(e -> startDownload());
        infoBtn.addActionListener(e -> fetchFileInfo());
        cancelBtn.addActionListener(e -> cancelDownload());
        openFolderBtn.addActionListener(e -> openFolder());
        ioBtn.addActionListener(e -> setProtocolMode(false));
        nioBtn.addActionListener(e -> setProtocolMode(true));
        minusBtn.addActionListener(e -> adjustWorkers(-1));
        plusBtn.addActionListener(e -> adjustWorkers(1));
        browseBtn.addActionListener(e -> chooseDirectory());
        copySummaryBtn.addActionListener(e -> copySummary());
        exportCsvBtn.addActionListener(e -> exportCsv());
        clearHistoryBtn.addActionListener(e -> clearHistory());

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateStatusRight(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateStatusRight(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateStatusRight(); }
        };
        workersField.getDocument().addDocumentListener(dl);
        roundsField.getDocument().addDocumentListener(dl);

        fileTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !isBusy()) startDownload();
            }
        });

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { cancelDownload(); }
        });
    }

    boolean isBusy() { return state == State.RUNNING || state == State.VERIFYING; }

    void setProtocolMode(boolean nio) {
        useNio = nio;
        ioBtn.setSelected(!nio);
        nioBtn.setSelected(nio);
        updateStatusRight();
    }

    void adjustWorkers(int delta) {
        try {
            int w = Integer.parseInt(workersField.getText().trim());
            workersField.setText(String.valueOf(Math.max(1, Math.min(64, w + delta))));
        } catch (NumberFormatException e) {
            workersField.setText("10");
        }
        updateStatusRight();
    }

    void updateStatusRight() {
        statusRight.setText("Engine: " + (useNio ? "NIO Transfer" : "Traditional I/O")
                + "  |  Workers: " + workersField.getText().trim()
                + "  |  Rounds: " + roundsField.getText().trim());
    }

    void chooseDirectory() {
        try {
            JFileChooser fc = new JFileChooser(saveDirField.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && fc.getSelectedFile() != null) {
                saveDirField.setText(fc.getSelectedFile().getPath());
            }
        } catch (Exception ex) {
            statusLeft.setText("Browse error: " + ex.getMessage());
        }
    }

    // =====================================================================
    // Connect / Info
    // =====================================================================
    void connectServer() {
        if (isBusy()) return;
        String h = hostField.getText().trim();
        int p;
        try {
            p = Integer.parseInt(portField.getText().trim());
            if (p <= 0 || p > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            resetOnError("Invalid port number (1-65535)");
            return;
        }

        tableModel.setRowCount(0);
        percentLabel.setText("0%");
        overallBar.set(0, DISABLED_GRAY);
        detailLabel.setText("Connecting...");
        detailLabel.setFont(UI_FONT);
        detailLabel.setForeground(TEXT_MUTED);
        resultLabel.setText(" ");
        lanesTitle.setText("Worker Lanes (0 Active)");
        resetStats();
        parts = 0;
        done = null;
        starts = ends = null;
        lanesPanel.revalidate();
        lanesPanel.repaint();

        statusLeft.setText("Connecting to " + h + ":" + p + " ...");
        connectBtn.setEnabled(false);
        Thread.ofVirtual().start(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(h, p), 3000);
                s.setSoTimeout(4000);
                s.getOutputStream().write("LIST\nQUIT\n".getBytes(StandardCharsets.UTF_8));
                InputStream in = new BufferedInputStream(s.getInputStream());
                List<String[]> rows = new ArrayList<>();
                String line;
                while ((line = readLine(in)) != null && !line.equals("END")) {
                    if (!line.startsWith("FILE ")) continue;
                    String rest = line.substring(5);
                    int sp = rest.lastIndexOf(' ');
                    if (sp < 0) continue;
                    rows.add(new String[]{rest.substring(0, sp), fmt(Long.parseLong(rest.substring(sp + 1).trim()))});
                }
                SwingUtilities.invokeLater(() -> {
                    connectBtn.setEnabled(true);
                    tableModel.setRowCount(0);
                    for (String[] r : rows) tableModel.addRow(r);
                    if (!rows.isEmpty()) fileTable.setRowSelectionInterval(0, 0);
                    detailLabel.setText("Connected to " + h + ":" + p);
                    detailLabel.setFont(BOLD_FONT);
                    detailLabel.setForeground(SUCCESS_GREEN);
                    statusLeft.setText("Connected successfully. " + rows.size() + " files available.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    connectBtn.setEnabled(true);
                    resetOnError("Connection failed: server offline");
                });
            }
        });
    }

    void fetchFileInfo() {
        if (isBusy()) return;
        int row = fileTable.getSelectedRow();
        if (row < 0) {
            statusLeft.setText("Please select a file first");
            return;
        }
        String fileName = (String) tableModel.getValueAt(row, 0);
        String h = hostField.getText().trim();
        int p;
        try {
            p = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            statusLeft.setText("Invalid port format");
            return;
        }

        statusLeft.setText("Fetching file info...");
        Thread.ofVirtual().start(() -> {
            try {
                String info = requestServer(h, p, "INFO " + fileName);
                String hash = requestServer(h, p, "HASH " + fileName);
                SwingUtilities.invokeLater(() -> {
                    String sb = "<html><b>File:</b> " + fileName + "<br/>"
                            + "<b>Info:</b> " + (info != null ? info : "N/A") + "<br/>"
                            + "<b>SHA-256 Hash:</b> " + (hash != null ? hash : "N/A") + "</html>";
                    JOptionPane.showMessageDialog(this, sb, "File Information", JOptionPane.INFORMATION_MESSAGE);
                    statusLeft.setText("File info retrieved");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> statusLeft.setText("Failed to fetch info: " + ex.getMessage()));
            }
        });
    }

    void resetOnError(String msg) {
        tableModel.setRowCount(0);
        statusLeft.setText(msg);
        resultLabel.setText("Connection Error");
        resultLabel.setForeground(DANGER_RED);
        percentLabel.setText("0%");
        overallBar.set(0, DISABLED_GRAY);
        detailLabel.setText("Unable to reach host");
        detailLabel.setFont(UI_FONT);
        detailLabel.setForeground(TEXT_MUTED);
        openFolderBtn.setVisible(false);
        resetStats();
        totalSize = 0;
        parts = 0;
        done = null;
        starts = ends = null;
        lanesTitle.setText("Worker Lanes (0 Active)");
        setState(State.IDLE);
        lanesPanel.revalidate();
        lanesPanel.repaint();
    }

    void resetStats() {
        speedCard.value.setText("--");
        elapsedCard.value.setText("--");
        etaCard.value.setText("--");
    }

    // =====================================================================
    // Download control
    // =====================================================================
    void startDownload() {
        if (isBusy()) return;
        int row = fileTable.getSelectedRow();
        if (row < 0) {
            statusLeft.setText("Please select a file to download");
            return;
        }
        String fileName = (String) tableModel.getValueAt(row, 0);
        String h = hostField.getText().trim();
        int basePort, workers, rounds;
        try {
            basePort = Integer.parseInt(portField.getText().trim());
            workers = Math.max(1, Math.min(64, Integer.parseInt(workersField.getText().trim())));
            rounds = Math.max(1, Math.min(20, Integer.parseInt(roundsField.getText().trim())));
        } catch (NumberFormatException e) {
            statusLeft.setText("Invalid port, workers or rounds");
            return;
        }
        workersField.setText(String.valueOf(workers));
        roundsField.setText(String.valueOf(rounds));
        Path dir = Paths.get(saveDirField.getText().trim());

        cancelled = false;
        openResources.clear();
        totalDone.set(0);
        startNs = System.nanoTime();
        resultLabel.setText(" ");
        resultLabel.setForeground(TEXT_MAIN);
        percentLabel.setText("0%");
        overallBar.set(0, DISABLED_GRAY);
        detailLabel.setText("Starting...");
        detailLabel.setFont(UI_FONT);
        detailLabel.setForeground(TEXT_MUTED);
        openFolderBtn.setVisible(false);
        resetStats();
        setState(State.RUNNING);
        statusLeft.setText("Downloading: " + fileName);

        final int fr = rounds;
        final int job = ++jobSeq;
        jobStartTimes.put(job, java.time.LocalDateTime.now().format(TS_FMT));
        Thread.ofVirtual().start(() -> runDownload(h, basePort, fileName, useNio, workers, dir, fr, job));
    }

    void cancelDownload() {
        if (state != State.RUNNING) return;
        cancelled = true;
        for (Closeable c : openResources) { try { c.close(); } catch (IOException ignored) {} }
        statusLeft.setText("Transfer cancelled by user");
    }

    void openFolder() {
        if (lastDownloadedFile == null) return;
        Thread.ofVirtual().start(() -> {
            try {
                Path parent = lastDownloadedFile.toAbsolutePath().getParent();
                if (parent != null && Files.exists(parent)) Desktop.getDesktop().open(parent.toFile());
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> statusLeft.setText("Cannot open folder: " + ex.getMessage()));
            }
        });
    }

    void setState(State s) {
        Runnable r = () -> {
            state = s;
            boolean busy = (s == State.RUNNING || s == State.VERIFYING);
            connectBtn.setEnabled(!busy);
            downloadBtn.setEnabled(!busy);
            infoBtn.setEnabled(!busy);
            cancelBtn.setEnabled(s == State.RUNNING);
            ioBtn.setEnabled(!busy);
            nioBtn.setEnabled(!busy);
            minusBtn.setEnabled(!busy);
            plusBtn.setEnabled(!busy);
            browseBtn.setEnabled(!busy);
            workersField.setEnabled(!busy);
            roundsField.setEnabled(!busy);
            hostField.setEnabled(!busy);
            portField.setEnabled(!busy);
            saveDirField.setEnabled(!busy);
            fileTable.setEnabled(!busy);
            clearHistoryBtn.setEnabled(!busy);
            switch (s) {
                case IDLE -> pill.set("Idle", TEXT_MUTED);
                case RUNNING -> pill.set("Downloading", PROGRESS_YELLOW.darker());
                case VERIFYING -> pill.set("Verifying", PRIMARY_BLUE);
                case DONE -> pill.set("Completed", SUCCESS_GREEN);
                case FAILED -> pill.set("Failed", DANGER_RED);
                case CANCELLED -> pill.set("Cancelled", TEXT_MUTED);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r);
    }

    // =====================================================================
    // Download engine
    // =====================================================================
    private void runDownload(String h, int basePort, String name, boolean nioMode, int workers, Path dir, int rounds, int job) {
        Path out = null;
        try {
            String info = requestServer(h, basePort, "INFO " + name);
            if (info == null || !info.startsWith("SIZE ")) throw new IOException("Invalid size response");
            totalSize = Long.parseLong(info.substring(5).trim());
            if (totalSize <= 0) throw new IOException("Empty file");
            String hl = requestServer(h, basePort, "HASH " + name);
            String expectedHash = (hl != null && hl.startsWith("HASH ")) ? hl.substring(5).trim() : null;

            Files.createDirectories(dir);
            out = dir.resolve(Paths.get(name).getFileName().toString());
            final Path finalOut = out;

            parts = (int) Math.min(workers, totalSize);
            long[] st = new long[parts];
            long[] en = new long[parts];
            long chunkSize = totalSize / parts;
            for (int i = 0; i < parts; i++) {
                st[i] = i * chunkSize;
                en[i] = (i == parts - 1) ? totalSize - 1 : st[i] + chunkSize - 1;
            }
            starts = st;
            ends = en;
            final int p = nioMode ? basePort + 1 : basePort;
            final String modeName = nioMode ? "NIO" : "I/O";
            final int nParts = parts;
            SwingUtilities.invokeLater(() -> {
                lanesTitle.setText("Worker Lanes (" + nParts + " Active)");
                lanesPanel.revalidate();
            });

            boolean allOk = true;
            String lastMsg = "";
            for (int round = 1; round <= rounds && !cancelled; round++) {
                final int fRound = round;
                Files.deleteIfExists(out);
                try (RandomAccessFile raf = new RandomAccessFile(out.toFile(), "rw")) { raf.setLength(totalSize); }
                done = new AtomicLongArray(parts);
                totalDone.set(0);
                startNs = System.nanoTime();
                final String roundTag = rounds > 1 ? "Round " + round + "/" + rounds + "  -  " : "";
                final AtomicBoolean stop = new AtomicBoolean(false);

                SwingUtilities.invokeLater(() -> {
                    percentLabel.setText("0%");
                    overallBar.set(0, DISABLED_GRAY);
                    detailLabel.setText(roundTag + "0 B of " + fmt(totalSize));
                });

                Thread progressUpdater = Thread.ofVirtual().start(() -> {
                    long prevBytes = 0, prevNs = System.nanoTime();
                    double ema = 0;
                    while (!stop.get() && !cancelled) {
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                        long now = System.nanoTime();
                        long bytes = totalDone.get();
                        double inst = (bytes - prevBytes) / Math.max(1e-6, (now - prevNs) / 1e9);
                        ema = ema == 0 ? inst : ema * 0.7 + inst * 0.3;
                        prevBytes = bytes;
                        prevNs = now;
                        final double speed = ema;
                        final double prog = Math.min(1.0, (double) bytes / totalSize);
                        final double elapsed = (now - startNs) / 1e9;
                        final double eta = speed > 1 ? (totalSize - bytes) / speed : -1;
                        final long fb = bytes;
                        SwingUtilities.invokeLater(() -> {
                            percentLabel.setText((int) (prog * 100) + "%");
                            overallBar.set(prog, prog >= 1.0 ? SUCCESS_GREEN : PROGRESS_YELLOW);
                            detailLabel.setText(roundTag + fmt(fb) + " of " + fmt(totalSize));
                            speedCard.value.setText(fmt((long) speed) + "/s");
                            elapsedCard.value.setText(fmtTime(elapsed));
                            etaCard.value.setText(fmtTime(eta));
                            lanesPanel.repaint();
                        });
                    }
                });

                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Future<Void>> futures = new ArrayList<>();
                    for (int i = 0; i < parts; i++) {
                        final int idx = i;
                        futures.add(pool.submit(() -> {
                            long len = en[idx] - st[idx] + 1;
                            if (nioMode) workerNio(h, p, name, st[idx], len, finalOut, idx);
                            else workerIo(h, p, name, st[idx], len, finalOut, idx);
                            return null;
                        }));
                    }
                    for (Future<Void> f : futures) f.get();
                } finally {
                    stop.set(true);
                    progressUpdater.interrupt();
                }
                if (cancelled) throw new CancellationException();

                final double secs = (System.nanoTime() - startNs) / 1e9;

                setState(State.VERIFYING);
                SwingUtilities.invokeLater(() -> {
                    percentLabel.setText("100%");
                    overallBar.set(1.0, SUCCESS_GREEN);
                    elapsedCard.value.setText(fmtTime(secs));
                    etaCard.value.setText("0.0s");
                    speedCard.value.setText(String.format("%.1f MB/s", (totalSize / 1048576.0) / secs));
                    statusLeft.setText("Verifying SHA-256 (round " + fRound + "/" + rounds + ")...");
                    lanesPanel.repaint();
                });

                boolean sizeOk = Files.size(finalOut) == totalSize;
                String hashStatus;
                if (expectedHash == null) hashStatus = "N/A";
                else hashStatus = sha256(finalOut).equals(expectedHash) ? "OK" : "Mismatch";
                boolean ok = sizeOk && !hashStatus.equals("Mismatch");
                allOk &= ok;
                lastMsg = "Size: " + (sizeOk ? "OK" : "Err") + " | Hash: " + hashStatus;

                final boolean fSizeOk = sizeOk;
                final String fHash = hashStatus;
                final long fSize = totalSize;
                SwingUtilities.invokeLater(() -> recordHistory(job, name, modeName, workers, fSize, secs, fSizeOk, fHash));

                if (round < rounds && !cancelled) setState(State.RUNNING);
            }
            if (cancelled) throw new CancellationException();

            if (allOk) {
                lastDownloadedFile = finalOut;
                SwingUtilities.invokeLater(() -> {
                    percentLabel.setText("100%");
                    overallBar.set(1.0, SUCCESS_GREEN);
                    openFolderBtn.setVisible(true);
                    detailLabel.setText(fmt(totalSize) + " downloaded" + (rounds > 1 ? " (" + rounds + " rounds)" : ""));
                });
                Thread.ofVirtual().start(this::playSuccessSound);
            }
            finishDownload(allOk ? State.DONE : State.FAILED, lastMsg,
                    allOk ? "Saved to " + finalOut : "Verification failed");
        } catch (Exception e) {
            if (out != null) { try { Files.deleteIfExists(out); } catch (IOException ignored) {} }
            if (cancelled || e instanceof CancellationException)
                finishDownload(State.CANCELLED, "Transfer cancelled", "Cancelled");
            else
                finishDownload(State.FAILED, "Error: " + rootMessage(e), "Download failed");
        }
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private void workerIo(String h, int p, String name, long off, long len, Path out, int idx) throws IOException {
        try (Socket s = new Socket(h, p); RandomAccessFile raf = new RandomAccessFile(out.toFile(), "rw")) {
            openResources.add(s);
            s.setTcpNoDelay(true);
            OutputStream os = s.getOutputStream();
            os.write(("GET " + name + " " + off + " " + len + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), BUF);
            expectOk(readLine(in), len);
            raf.seek(off);
            byte[] buf = new byte[BUF];
            long rem = len;
            int n;
            while (rem > 0 && !cancelled && (n = in.read(buf, 0, (int) Math.min(buf.length, rem))) != -1) {
                raf.write(buf, 0, n);
                rem -= n;
                done.addAndGet(idx, n);
                totalDone.addAndGet(n);
            }
        }
    }

    private void workerNio(String h, int p, String name, long off, long len, Path out, int idx) throws IOException {
        try (SocketChannel sc = SocketChannel.open(new InetSocketAddress(h, p));
             FileChannel fc = FileChannel.open(out, StandardOpenOption.WRITE)) {
            openResources.add(sc);
            sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
            java.nio.ByteBuffer cmd = java.nio.ByteBuffer.wrap(
                    ("GET " + name + " " + off + " " + len + "\n").getBytes(StandardCharsets.UTF_8));
            while (cmd.hasRemaining()) sc.write(cmd);
            expectOk(readLine(Channels.newInputStream(sc)), len);
            long pos = off, rem = len;
            while (rem > 0 && !cancelled) {
                long n = fc.transferFrom(sc, pos, Math.min(rem, NIO_CHUNK));
                if (n <= 0) throw new EOFException("Incomplete NIO transfer");
                pos += n;
                rem -= n;
                done.addAndGet(idx, n);
                totalDone.addAndGet(n);
            }
        }
    }

    static String requestServer(String h, int p, String cmd) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(h, p), 3000);
            s.setSoTimeout(5000);
            s.getOutputStream().write((cmd + "\nQUIT\n").getBytes(StandardCharsets.UTF_8));
            return readLine(new BufferedInputStream(s.getInputStream()));
        }
    }

    static void expectOk(String header, long len) throws IOException {
        if (header == null) throw new EOFException("Server closed connection");
        if (!header.equals("OK " + len)) throw new IOException("Server error: " + header);
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return bo.toString(StandardCharsets.UTF_8).trim();
            bo.write(c);
            if (bo.size() > 4096) throw new IOException("Header too long");
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

    private void finishDownload(State s, String resText, String statText) {
        SwingUtilities.invokeLater(() -> {
            resultLabel.setText(resText);
            resultLabel.setForeground(s == State.DONE ? SUCCESS_GREEN : DANGER_RED);
            setState(s);
            statusLeft.setText(statText);
            lanesPanel.repaint();
        });
    }

    private void playSuccessSound() {
        try {
            float sampleRate = 8000f;
            byte[] buf = new byte[600];
            for (int i = 0; i < buf.length; i++) {
                double angle = i / (sampleRate / 880) * 2.0 * Math.PI;
                buf[i] = (byte) (Math.sin(angle) * 100.0);
            }
            AudioFormat af = new AudioFormat(sampleRate, 8, 1, true, false);
            SourceDataLine sdl = AudioSystem.getSourceDataLine(af);
            sdl.open(af);
            sdl.start();
            sdl.write(buf, 0, buf.length);
            sdl.drain();
            sdl.close();
        } catch (Exception ignored) {}
    }

    // =====================================================================
    // History
    // =====================================================================
    /** Must be called on the EDT. Adds one round to its run (one Start Download click = one row). */
    void recordHistory(int run, String file, String mode, int workers, long size, double secs, boolean sizeOk, String hash) {
        Case c = cases.computeIfAbsent(run, k -> {
            Case n = new Case();
            n.run = run;
            n.startedAt = jobStartTimes.getOrDefault(run, java.time.LocalDateTime.now().format(TS_FMT));
            n.file = file; n.mode = mode; n.workers = workers; n.size = size;
            return n;
        });
        c.size = size;
        c.times.add(secs);
        c.sizeOk &= sizeOk;
        if (hash.equals("Mismatch")) c.hashStatus = "Mismatch";
        else if (hash.equals("N/A") && c.hashStatus.equals("OK") && c.times.size() == 1) c.hashStatus = "N/A";
        refreshHistory();
    }

    void refreshHistory() {
        historyModel.setRowCount(0);
        for (Case c : cases.values()) {
            historyModel.addRow(new Object[]{
                    c.run, c.startedAt, c.file, c.mode, c.workers, c.timesText(),
                    String.format("%.2f", c.avg()), String.format("%.2f", c.mbps()), c.sizeHash()});
        }
        if (historyModel.getRowCount() > 0) {
            int last = historyModel.getRowCount() - 1;
            historyTable.scrollRectToVisible(historyTable.getCellRect(last, 0, true));
        }
    }

    void clearHistory() {
        if (cases.isEmpty()) return;
        int r = JOptionPane.showConfirmDialog(this, "Clear all download history?", "Clear History",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        cases.clear();
        jobStartTimes.clear();
        refreshHistory();
        statusLeft.setText("History cleared");
    }

    /** Text summary in the console-style format, grouped by file. */
    String buildSummary() {
        Map<String, List<Case>> byFile = new LinkedHashMap<>();
        for (Case c : cases.values()) byFile.computeIfAbsent(c.file, k -> new ArrayList<>()).add(c);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Case>> e : byFile.entrySet()) {
            List<Case> list = e.getValue();
            int per = list.stream().mapToInt(c -> c.times.size()).max().orElse(0);
            double mb = list.get(0).size / 1048576.0;
            sb.append(String.format("========== Summary (%s, %.2f MB, %d per case) ==========%n", e.getKey(), mb, per));
            sb.append(String.format("%-3s | %-19s | %-5s | %-7s | %-28s | %-11s | %-8s | %s%n",
                    "Run", "Started", "Mode", "workers", "time per round (s)", "average (s)", "MB/s", "size/hash"));
            for (Case c : list) {
                sb.append(String.format("%-3d | %-19s | %-5s | %-7d | %-28s | %-11.2f | %-8.2f | %s%n",
                        c.run, c.startedAt, c.mode, c.workers, c.timesText(), c.avg(), c.mbps(), c.sizeHash()));
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    void copySummary() {
        if (cases.isEmpty()) { statusLeft.setText("History is empty"); return; }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(buildSummary()), null);
        statusLeft.setText("Summary copied to clipboard");
    }

    void exportCsv() {
        if (cases.isEmpty()) { statusLeft.setText("History is empty"); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("download-history.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (PrintWriter w = new PrintWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
            w.println("run,started_at,file,size_bytes,mode,workers,rounds,time_per_round_s,average_s,mb_per_s,size_hash");
            for (Case c : cases.values()) {
                w.printf("%d,%s,\"%s\",%d,%s,%d,%d,\"%s\",%.4f,%.4f,%s%n",
                        c.run, c.startedAt, c.file.replace("\"", "\"\""), c.size, c.mode, c.workers,
                        c.times.size(), c.timesText(), c.avg(), c.mbps(), c.sizeHash());
            }
            statusLeft.setText("History exported to " + fc.getSelectedFile().getName());
        } catch (IOException ex) {
            statusLeft.setText("Export failed: " + ex.getMessage());
        }
    }

    // =====================================================================
    // Formatting helpers
    // =====================================================================
    static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format("%.1f %s", v, u[i]);
    }

    static String fmtTime(double s) {
        if (s < 0 || Double.isNaN(s) || Double.isInfinite(s)) return "--";
        if (s < 60) return String.format("%.1fs", s);
        int t = (int) s;
        return String.format("%d:%02d", t / 60, t % 60);
    }

    // =====================================================================
    // UI building blocks
    // =====================================================================
    static JLabel label(String t) {
        JLabel l = new JLabel(t);
        l.setFont(UI_FONT);
        l.setForeground(TEXT_MAIN);
        return l;
    }

    static JLabel sectionTitle(String t) {
        JLabel l = new JLabel(t);
        l.setFont(HEADER_FONT);
        l.setForeground(TEXT_MAIN);
        return l;
    }

    static JLabel rowLabel(String t) {
        JLabel l = label(t);
        l.setForeground(TEXT_MUTED);
        l.setPreferredSize(new Dimension(62, 30));
        return l;
    }

    static JTextField field(String t, int cols) {
        JTextField f = new JTextField(t, cols);
        f.setFont(UI_FONT);
        f.setForeground(TEXT_MAIN);
        f.setBackground(Color.WHITE);
        f.setCaretColor(PRIMARY_BLUE);
        f.setBorder(fieldBorder(false));
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { f.setBorder(fieldBorder(true)); }
            @Override public void focusLost(FocusEvent e) { f.setBorder(fieldBorder(false)); }
        });
        return f;
    }

    static Border fieldBorder(boolean focused) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(focused ? PRIMARY_BLUE : BORDER_COLOR, focused ? 2 : 1),
                BorderFactory.createEmptyBorder(focused ? 5 : 6, focused ? 7 : 8, focused ? 5 : 6, focused ? 7 : 8));
    }

    static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        for (Component c : cs) p.add(c);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return p;
    }

    static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    static void styleTable(JTable t, int rowHeight) {
        t.setFont(UI_FONT);
        t.setRowHeight(rowHeight);
        t.setSelectionBackground(new Color(0xDBEAFE));
        t.setSelectionForeground(TEXT_MAIN);
        t.setFillsViewportHeight(true);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setGridColor(new Color(0xE2E8F0));
        t.setIntercellSpacing(new Dimension(0, 1));
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                l.setFont(BOLD_FONT);
                l.setForeground(TEXT_MUTED);
                l.setBackground(new Color(0xE2E8F0));
                l.setBorder(new EmptyBorder(7, 10, 7, 10));
                return l;
            }
        });
    }

    /** Zebra-striped renderer; subclasses customise alignment / colour per column. */
    abstract static class TableRenderer extends DefaultTableCellRenderer {
        abstract void customize(JTable t, Object v, int row, int col);

        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            setFont(UI_FONT);
            setForeground(TEXT_MAIN);
            setBorder(new EmptyBorder(0, 10, 0, 10));
            if (!sel) setBackground(r % 2 == 0 ? CARD_BG : SURFACE_ALT);
            customize(t, v, r, c);
            return this;
        }
    }

    /** JTable that paints a friendly hint when it has no rows. */
    static class EmptyHintTable extends JTable {
        final String hint;
        EmptyHintTable(DefaultTableModel m, String hint) { super(m); this.hint = hint; }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (getRowCount() > 0) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(UI_FONT);
            g.setColor(TEXT_FAINT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(hint, (getWidth() - fm.stringWidth(hint)) / 2, Math.max(40, getHeight() / 2));
            g.dispose();
        }
    }

    static class MinimalPanel extends JPanel {
        final Color bgColor;
        MinimalPanel(Color bgColor) {
            this.bgColor = bgColor;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(bgColor);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 16, 16));
            g.setColor(BORDER_COLOR);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 16, 16));
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** Small stat tile (value + caption). */
    static class StatCard extends JPanel {
        final JLabel value = new JLabel("--");
        StatCard(String title) {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(9, 14, 9, 14));
            JLabel t = new JLabel(title);
            t.setFont(new Font("SansSerif", Font.BOLD, 10));
            t.setForeground(TEXT_FAINT);
            value.setFont(new Font("SansSerif", Font.BOLD, 18));
            value.setForeground(TEXT_MAIN);
            add(t);
            add(Box.createVerticalStrut(2));
            add(value);
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(SURFACE_ALT);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 12, 12));
            g.setColor(new Color(0xE2E8F0));
            g.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** Coloured state badge. */
    static class StatusPill extends JComponent {
        String text = "Idle";
        Color color = TEXT_MUTED;
        StatusPill() { setFont(new Font("SansSerif", Font.BOLD, 12)); }
        void set(String t, Color c) {
            text = t;
            color = c;
            revalidate();
            repaint();
        }
        @Override public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(text) + 36, 26);
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 28));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g.setColor(color);
            g.fillOval(10, getHeight() / 2 - 3, 6, 6);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 22, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    static class MinimalButton extends JButton {
        final Color bgNormal, fgColor;
        final boolean outline;
        boolean selected;

        MinimalButton(String t, Color bg, Color fg) { this(t, bg, fg, false); }
        MinimalButton(String t, Color bg, Color fg, boolean outline) {
            super(t);
            this.bgNormal = bg;
            this.fgColor = fg;
            this.outline = outline;
            setFont(BOLD_FONT);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override public void setSelected(boolean b) { selected = b; super.setSelected(b); repaint(); }

        @Override public Dimension getPreferredSize() {
            int w = getFontMetrics(getFont()).stringWidth(getText()) + 28;
            return new Dimension(Math.max(w, 38), 36);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean en = isEnabled(), hov = en && getModel().isRollover(), press = en && getModel().isPressed();
            Color bg, fg, borderCol;

            if (!en) {
                bg = new Color(0xE2E8F0); fg = TEXT_FAINT; borderCol = bg;
            } else if (selected) {
                bg = PRIMARY_BLUE; fg = Color.WHITE; borderCol = PRIMARY_BLUE;
            } else if (outline) {
                bg = hov ? new Color(0xEFF6FF) : SURFACE_ALT;
                fg = fgColor == CARD_BG ? TEXT_MAIN : fgColor;
                borderCol = hov ? PRIMARY_BLUE : BORDER_COLOR;
            } else {
                bg = press ? bgNormal.darker().darker() : hov ? bgNormal.darker() : bgNormal;
                fg = fgColor; borderCol = bg;
            }
            if (fgColor == TEXT_MAIN && outline && en && !selected) fg = TEXT_MAIN;

            g.setColor(bg);
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
            g.setColor(borderCol);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1.5f, getHeight() - 1.5f, 10, 10));

            if (hasFocus() && en) {
                g.setColor(new Color(PRIMARY_BLUE.getRed(), PRIMARY_BLUE.getGreen(), PRIMARY_BLUE.getBlue(), 90));
                g.setStroke(new BasicStroke(2f));
                g.draw(new RoundRectangle2D.Float(2, 2, getWidth() - 5, getHeight() - 5, 8, 8));
            }

            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.setColor(fg);
            g.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    static class MinimalProgressBar extends JComponent {
        double v;
        Color fill = DISABLED_GRAY;
        final int h;

        MinimalProgressBar(int h) { this.h = h; }

        void set(double v, Color c) {
            if (this.v != v || fill != c) { this.v = v; fill = c; repaint(); }
        }

        @Override public Dimension getPreferredSize() { return new Dimension(320, h); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, h); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xE2E8F0));
            g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), h, h, h));
            if (v > 0) {
                int w = Math.max(h, (int) (getWidth() * v));
                g.setColor(v >= 1.0 ? SUCCESS_GREEN : PROGRESS_YELLOW);
                g.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            }
            g.dispose();
        }
    }

    class LanesPanel extends JPanel {
        @Override public Dimension getPreferredSize() { return new Dimension(100, Math.max(parts, 1) * 22 + 6); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(CARD_BG);
                g.fillRect(0, 0, getWidth(), getHeight());
                AtomicLongArray d = done;
                long[] s = starts, e = ends;
                if (d == null || s == null || e == null) {
                    g.setFont(UI_FONT);
                    g.setColor(TEXT_FAINT);
                    String hint = "Worker progress will appear here during a download";
                    g.drawString(hint, 12, 24);
                    return;
                }
                int n = Math.min(parts, Math.min(d.length(), s.length));
                int barX = 42, barW = Math.max(10, getWidth() - barX - 45);
                g.setFont(SMALL_FONT);
                for (int i = 0; i < n; i++) {
                    int y = 4 + i * 22;
                    double f = Math.min(1.0, d.get(i) / (double) (e[i] - s[i] + 1));
                    g.setColor(TEXT_MUTED);
                    g.drawString(String.format("W%02d", i + 1), 4, y + 12);

                    g.setColor(new Color(0xE2E8F0));
                    g.fillRoundRect(barX, y + 2, barW, 10, 5, 5);

                    g.setColor(f >= 1.0 ? SUCCESS_GREEN : (f > 0 ? PROGRESS_YELLOW : DISABLED_GRAY));
                    int progW = Math.max(f >= 1.0 ? 8 : (f > 0 ? 4 : 0), (int) ((barW - 2) * f));
                    g.fillRoundRect(barX + 1, y + 3, progW, 8, 4, 4);

                    String pc = (int) (f * 100) + "%";
                    g.setColor(TEXT_MUTED);
                    g.drawString(pc, getWidth() - 5 - g.getFontMetrics().stringWidth(pc), y + 12);
                }
            } finally {
                g.dispose();
            }
        }
    }

    static class HeaderPanel extends JComponent {
        @Override public Dimension getPreferredSize() { return new Dimension(100, 64); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, TEXT_MAIN, getWidth(), 0, new Color(0x1E293B)));
            g.fillRect(0, 0, getWidth(), 64);
            g.setPaint(new GradientPaint(0, 0, PRIMARY_BLUE, getWidth(), 0, NIO_PURPLE));
            g.fillRect(0, 60, getWidth(), 4);

            // logo: rounded square with a down arrow
            g.setColor(PRIMARY_BLUE);
            g.fillRoundRect(20, 14, 34, 34, 10, 10);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(37, 21, 37, 36);
            g.drawLine(30, 30, 37, 37);
            g.drawLine(44, 30, 37, 37);
            g.drawLine(30, 42, 44, 42);

            g.setFont(HEADER_FONT);
            g.drawString("MultiThread File Downloader", 66, 29);
            g.setFont(SMALL_FONT);
            g.setColor(TEXT_FAINT);
            g.drawString("Parallel range transfer  -  I/O & NIO engines  -  SHA-256 verification", 66, 45);

            String r = "Virtual Threads (Java 21)";
            g.setFont(UI_FONT);
            g.setColor(TEXT_FAINT);
            g.drawString(r, getWidth() - 20 - g.getFontMetrics().stringWidth(r), 36);
            g.dispose();
        }
    }

    public static void main(String[] args) {
        if (Runtime.version().feature() < 21) {
            System.err.println("Error: Requires Java 21 or higher for Virtual Threads.");
            return;
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> new FileClientGui().setVisible(true));
    }
}
