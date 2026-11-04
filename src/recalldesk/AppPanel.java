package recalldesk;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

public final class AppPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Color NAVY = new Color(24, 48, 68), BLUE = new Color(30, 98, 147);
    private static final Color PAPER = new Color(244, 247, 249), INK = new Color(25, 43, 58);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.forLanguageTag("id-ID")).withZone(ZoneId.systemDefault());
    private final StudyService service;
    private final List<JButton> actions = new ArrayList<>();
    final JComboBox<Topic> topics = new JComboBox<>();
    final JTextField search = new JTextField();
    final JTabbedPane tabs = new JTabbedPane();
    final JLabel total = new JLabel(), due = new JLabel(), advanced = new JLabel(), reviews = new JLabel();
    final JLabel status = new JLabel("Pilih topik dan mulai dari kartu yang jatuh tempo.");
    final JLabel queueLabel = new JLabel(), tableCount = new JLabel();
    final JTextArea question = area(26), answer = area(18);
    final JButton reveal = button("Tampilkan jawaban"), again = button("Lupa · 10 menit"), remember = button("Ingat · 1 hari");
    final JTextField tagInput = new JTextField();
    final JTextArea frontInput = area(15), backInput = area(15);
    final JButton save = button("Simpan"), newCard = button("Baru"), delete = button("Hapus");
    final CardTable tableModel = new CardTable();
    final JTable table = new JTable(tableModel);
    private final Timer timer;
    private Card current;
    private String editingId;
    private boolean revealed, updating;
    volatile boolean busy;
    volatile String lastError = "";

    public AppPanel(StudyService service) {
        super(new BorderLayout());
        this.service = service;
        setBackground(PAPER);
        setPreferredSize(new Dimension(1180, 820));
        JPanel header = new JPanel(new BorderLayout(8, 10));
        header.setBackground(NAVY);
        header.setBorder(BorderFactory.createEmptyBorder(22, 28, 20, 28));
        JLabel brand = label("RecallDesk", 30, Color.WHITE);
        JLabel subtitle = label("Kartu belajar, jadwal ulang, dan progres dalam satu tempat.", 14, new Color(192, 216, 232));
        JPanel identity = vertical(); identity.setOpaque(false); identity.add(brand); identity.add(Box.createVerticalStrut(6)); identity.add(subtitle);
        header.add(identity, BorderLayout.NORTH);
        JPanel metrics = new JPanel(new GridLayout(1, 4, 14, 0)); metrics.setOpaque(false);
        metrics.add(metric("SIAP DIULANG", due)); metrics.add(metric("DALAM KOLEKSI", total));
        metrics.add(metric("KOTAK 3–5", advanced)); metrics.add(metric("TOTAL ULASAN", reviews));
        header.add(metrics, BorderLayout.CENTER); add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 12)); body.setBackground(PAPER);
        body.setBorder(BorderFactory.createEmptyBorder(16, 24, 14, 24));
        JPanel filters = new JPanel(new BorderLayout(12, 0)); filters.setOpaque(false);
        JPanel choose = new JPanel(new BorderLayout(10, 0)); choose.setOpaque(false);
        choose.add(new JLabel("Topik"), BorderLayout.WEST); choose.add(topics, BorderLayout.CENTER);
        choose.setPreferredSize(new Dimension(285, 36)); filters.add(choose, BorderLayout.WEST);
        JPanel searchBox = new JPanel(new BorderLayout(10, 0)); searchBox.setOpaque(false);
        searchBox.add(new JLabel("Cari koleksi"), BorderLayout.WEST); searchBox.add(search, BorderLayout.CENTER);
        search.setToolTipText("Cari teks pertanyaan, jawaban, atau topik pada tab Koleksi.");
        filters.add(searchBox, BorderLayout.CENTER);
        JButton refresh = button("Muat ulang sesi"); filters.add(refresh, BorderLayout.EAST);
        body.add(filters, BorderLayout.NORTH);
        tabs.addTab("  Belajar  ", studyPanel()); tabs.addTab("  Koleksi & CSV  ", collectionPanel());
        body.add(tabs, BorderLayout.CENTER); add(body, BorderLayout.CENTER);
        JPanel footer = new JPanel(new GridLayout(2, 1, 0, 5)); footer.setBackground(PAPER);
        footer.setBorder(BorderFactory.createEmptyBorder(0, 28, 14, 28));
        status.setForeground(BLUE); footer.add(status);
        JLabel data = label("Pustaka lokal  ·  " + service.dataPath(), 11, new Color(91, 107, 118));
        data.setToolTipText(service.dataPath().toString()); footer.add(data); add(footer, BorderLayout.SOUTH);

        reveal.addActionListener(event -> { revealed = true; showStudy(); });
        again.addActionListener(event -> grade(Scheduler.Grade.AGAIN));
        remember.addActionListener(event -> grade(Scheduler.Grade.REMEMBER));
        refresh.addActionListener(event -> { current = null; refreshAll(); });
        topics.addActionListener(event -> { if (!updating) { current = null; refreshAll(); } });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { loadTable(); }
            @Override public void removeUpdate(DocumentEvent event) { loadTable(); }
            @Override public void changedUpdate(DocumentEvent event) { loadTable(); }
        });
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0) editSelection();
        });
        newCard.addActionListener(event -> clearEditor());
        save.addActionListener(event -> saveEditor());
        delete.addActionListener(event -> deleteSelection());
        timer = new Timer(30_000, event -> { if (!busy) { updateMetrics(); if (current == null) chooseNext(); } });
        refreshAll();
    }

    private JPanel studyPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 16)); panel.setBackground(PAPER);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 12, 12, 12));
        JPanel card = new JPanel(new BorderLayout(0, 12)); card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(214, 225, 232)), BorderFactory.createEmptyBorder(22, 26, 20, 26)));
        queueLabel.setFont(new Font("Dialog", Font.BOLD, 13)); queueLabel.setForeground(BLUE); card.add(queueLabel, BorderLayout.NORTH);
        question.setEditable(false); answer.setEditable(false);
        JPanel texts = new JPanel(new GridLayout(2, 1, 0, 16)); texts.setBackground(Color.WHITE);
        texts.add(scroll(question)); texts.add(scroll(answer)); card.add(texts, BorderLayout.CENTER);
        JLabel note = label("Coba jawab sendiri, buka jawaban, lalu nilai ingatanmu.", 13, new Color(96, 111, 122)); card.add(note, BorderLayout.SOUTH);
        panel.add(card, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(1, 3, 14, 0)); buttons.setOpaque(false);
        buttons.setPreferredSize(new Dimension(800, 48));
        buttons.add(reveal); buttons.add(again); buttons.add(remember); panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel collectionPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12)); panel.setBackground(PAPER);
        panel.setBorder(BorderFactory.createEmptyBorder(14, 10, 10, 10));
        JPanel tools = new JPanel(new BorderLayout(8, 0)); tools.setOpaque(false);
        tools.add(tableCount, BorderLayout.WEST);
        JPanel csvButtons = new JPanel(new GridLayout(1, 2, 10, 0)); csvButtons.setOpaque(false);
        JButton importButton = button("Impor CSV"), exportButton = button("Ekspor semua CSV");
        importButton.addActionListener(event -> chooseCsv(false)); exportButton.addActionListener(event -> chooseCsv(true));
        csvButtons.add(importButton); csvButtons.add(exportButton); tools.add(csvButtons, BorderLayout.EAST); panel.add(tools, BorderLayout.NORTH);
        table.setRowHeight(32); table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {80, 260, 50, 160, 60};
        for (int index = 0; index < widths.length; index++) table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
        JPanel editor = vertical(); editor.setBackground(Color.WHITE);
        editor.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        editor.add(label("Editor kartu", 18, INK)); editor.add(Box.createVerticalStrut(12));
        tagInput.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        editor.add(new JLabel("Topik (maksimal 40 karakter)")); editor.add(tagInput); editor.add(Box.createVerticalStrut(12));
        editor.add(new JLabel("Pertanyaan")); editor.add(scroll(frontInput)); editor.add(Box.createVerticalStrut(12));
        editor.add(new JLabel("Jawaban")); editor.add(scroll(backInput)); editor.add(Box.createVerticalStrut(12));
        frontInput.setEditable(true); backInput.setEditable(true);
        JPanel editButtons = new JPanel(new GridLayout(1, 3, 8, 0)); editButtons.add(newCard); editButtons.add(save); editButtons.add(delete);
        editButtons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44)); editor.add(editButtons);
        for (java.awt.Component child : editor.getComponents()) if (child instanceof javax.swing.JComponent component) component.setAlignmentX(0);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll(table), editor);
        split.setResizeWeight(0.62); split.setDividerLocation(660); split.setBorder(null); panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel metric(String title, JLabel value) {
        JPanel panel = vertical(); panel.setBackground(new Color(236, 243, 248));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 10, 16));
        panel.add(label(title, 11, new Color(75, 102, 122)));
        value.setFont(new Font("Dialog", Font.BOLD, 32)); value.setForeground(INK); panel.add(value); return panel;
    }

    private JButton button(String text) {
        JButton result = new JButton(text); result.setFocusPainted(false); result.setFont(new Font("Dialog", Font.BOLD, 13));
        actions.add(result); return result;
    }
    private static JPanel vertical() { JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); return panel; }
    private static JLabel label(String text, int size, Color color) { JLabel result = new JLabel(text); result.setFont(new Font("Dialog", Font.PLAIN, size)); result.setForeground(color); return result; }
    private static JTextArea area(int size) {
        JTextArea result = new JTextArea(); result.setLineWrap(true); result.setWrapStyleWord(true);
        result.setFont(new Font("Dialog", Font.PLAIN, size)); result.setForeground(INK); result.setBackground(Color.WHITE);
        result.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8)); return result;
    }
    private static JScrollPane scroll(java.awt.Component component) {
        JScrollPane result = new JScrollPane(component);
        if (component instanceof JTable grid) result.setColumnHeaderView(grid.getTableHeader());
        result.setBorder(BorderFactory.createLineBorder(new Color(222, 229, 235))); return result;
    }

    private String selectedTag() { Topic selected = (Topic) topics.getSelectedItem(); return selected == null ? null : selected.value(); }

    void selectTopic(String tag) {
        for (int index = 0; index < topics.getItemCount(); index++) {
            if (Objects.equals(topics.getItemAt(index).value(), tag)) { topics.setSelectedIndex(index); return; }
        }
    }

    private void refreshAll() {
        String tag = selectedTag();
        updating = true; topics.removeAllItems(); topics.addItem(new Topic(null));
        for (String value : service.tags()) topics.addItem(new Topic(value));
        selectTopic(tag); updating = false;
        updateMetrics(); loadTable(); clearEditor();
        current = null; chooseNext();
    }

    private void updateMetrics() {
        StudyService.Stats stats = service.stats(selectedTag());
        total.setText(Integer.toString(stats.total())); due.setText(Integer.toString(stats.due()));
        advanced.setText(Integer.toString(stats.advanced())); reviews.setText(Long.toString(stats.reviews()));
    }

    private void chooseNext() {
        List<Card> ready = service.due(selectedTag()); current = ready.isEmpty() ? null : ready.get(0);
        revealed = false; showStudy();
    }

    private void showStudy() {
        if (current == null) {
            queueLabel.setText("SESI SELESAI UNTUK SAAT INI"); question.setText("Tidak ada kartu jatuh tempo.");
            answer.setForeground(INK);
            answer.setText("Tambahkan kartu melalui tab Koleksi & CSV, pilih topik lain, atau kembali saat jadwal berikutnya tiba.");
        } else {
            queueLabel.setText(current.tag() + "  /  KOTAK " + current.box() + "  /  " + service.due(selectedTag()).size() + " kartu siap");
            question.setText(current.front()); answer.setText(revealed ? current.back() : "Jawaban masih tertutup.");
            answer.setForeground(revealed ? INK : new Color(124, 137, 147));
            again.setText("Lupa · " + Scheduler.intervalLabel(Scheduler.Grade.AGAIN, current.box()));
            remember.setText("Ingat · " + Scheduler.intervalLabel(Scheduler.Grade.REMEMBER, current.box()));
        }
        question.setCaretPosition(0); answer.setCaretPosition(0);
        reveal.setEnabled(!busy && current != null && !revealed);
        again.setEnabled(!busy && current != null && revealed); remember.setEnabled(!busy && current != null && revealed);
    }

    private void loadTable() {
        String tag = selectedTag(), query = search.getText().strip().toLowerCase(Locale.ROOT);
        tableModel.cards = service.snapshot().cards().stream().filter(card -> tag == null || card.tag().equals(tag))
                .filter(card -> (card.tag() + "\n" + card.front() + "\n" + card.back()).toLowerCase(Locale.ROOT).contains(query)).toList();
        tableModel.fireTableDataChanged(); tableCount.setText(tableModel.cards.size() + " kartu ditampilkan"); clearEditor();
    }

    private void clearEditor() {
        editingId = null; table.clearSelection(); tagInput.setText(selectedTag() == null ? "Umum" : selectedTag());
        frontInput.setText(""); backInput.setText(""); delete.setEnabled(false);
    }

    private void editSelection() {
        Card card = tableModel.cards.get(table.convertRowIndexToModel(table.getSelectedRow())); editingId = card.id();
        tagInput.setText(card.tag()); frontInput.setText(card.front()); backInput.setText(card.back()); delete.setEnabled(!busy);
    }

    private void saveEditor() {
        try {
            Card.Draft draft = new Card.Draft(tagInput.getText(), frontInput.getText(), backInput.getText());
            String id = editingId;
            runJob(() -> { if (id == null) service.add(List.of(draft)); else service.edit(id, draft); }, "Kartu tersimpan.");
        } catch (IllegalArgumentException error) { report(error); }
    }

    private void deleteSelection() {
        if (editingId == null) return;
        if (!GraphicsEnvironment.isHeadless() && JOptionPane.showConfirmDialog(this, "Hapus kartu terpilih beserta progresnya?", "Hapus kartu", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        String id = editingId; runJob(() -> service.delete(id), "Kartu dihapus.");
    }

    private void grade(Scheduler.Grade grade) {
        if (current == null || !revealed) return;
        String id = current.id(); runJob(() -> service.review(id, grade), "Penilaian tersimpan. Kartu berikutnya siap.");
    }

    void importFile(Path path) { runJob(() -> service.importCsv(path), "Impor berhasil. Semua kartu baru siap dipelajari."); }
    void exportFile(Path path) { runJob(() -> service.exportCsv(path), "CSV seluruh koleksi berhasil disimpan."); }

    private void chooseCsv(boolean export) {
        JFileChooser chooser = new JFileChooser(service.dataPath().getParent().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("CSV UTF-8 (*.csv)", "csv"));
        chooser.setDialogTitle(export ? "Ekspor semua kartu ke berkas baru" : "Impor CSV flashcard");
        int result = export ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            if (export) exportFile(chooser.getSelectedFile().toPath()); else importFile(chooser.getSelectedFile().toPath());
        }
    }

    private void runJob(IOAction action, String success) {
        if (busy) return;
        setBusy(true); lastError = ""; status.setText("Menyimpan / memproses berkas…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { action.run(); return null; }
            @Override protected void done() {
                boolean completed = false;
                try { get(); completed = true; status.setText(success); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); report(error); }
                catch (ExecutionException error) { report(error.getCause()); }
                finally { if (completed) refreshAll(); setBusy(false); }
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value; for (JButton action : actions) action.setEnabled(!value);
        topics.setEnabled(!value); search.setEnabled(!value); table.setEnabled(!value);
        tagInput.setEnabled(!value); frontInput.setEnabled(!value); backInput.setEnabled(!value);
        delete.setEnabled(!value && editingId != null); showStudy();
    }

    private void report(Throwable error) {
        lastError = error.getMessage() == null ? error.toString() : error.getMessage(); status.setText(lastError);
        if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(this, lastError, "RecallDesk", JOptionPane.ERROR_MESSAGE);
    }

    public boolean isBusy() { return busy; }
    public void startRefresh() { timer.start(); }
    public void stopRefresh() { timer.stop(); }
    @FunctionalInterface private interface IOAction { void run() throws Exception; }
    record Topic(String value) { @Override public String toString() { return value == null ? "Semua topik" : value; } }

    static final class CardTable extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        List<Card> cards = List.of();
        private final String[] columns = {"Topik", "Pertanyaan", "Kotak", "Jadwal berikutnya", "Ulasan"};
        @Override public int getRowCount() { return cards.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            Card card = cards.get(row);
            return switch (column) {
                case 0 -> card.tag(); case 1 -> card.front().replace('\n', ' '); case 2 -> card.box();
                case 3 -> DATE.format(card.due()); default -> card.reviews();
            };
        }
    }
}
