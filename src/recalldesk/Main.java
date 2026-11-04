package recalldesk;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    public static final String VERSION = "1.0.0";
    private static final String HELP = """
        RecallDesk 1.0.0 — flashcard desktop dengan jadwal pengulangan

        java -jar dist/recalldesk.jar [gui] [--data FILE.rdb] [--sample]
        java -jar dist/recalldesk.jar info [--data FILE.rdb]
        java -jar dist/recalldesk.jar import --csv FILE.csv [--data FILE.rdb]
        java -jar dist/recalldesk.jar export --csv BARU.csv [--data FILE.rdb]
        java -jar dist/recalldesk.jar restore-backup --data FILE.rdb
        java -jar dist/recalldesk.jar demo

        --sample menambahkan enam kartu contoh hanya pada pustaka kosong.
        Data bawaan: direktori .recalldesk dalam home pengguna, library.rdb.
        Ekspor CSV membawa isi kartu. Progres lengkap berada pada pustaka/backup.
        Exit: 0 berhasil, 2 input/IO bermasalah. GUI memerlukan desktop/display.
        """;
    private Main() { }

    public static void main(String[] args) {
        int code;
        try { code = run(args); }
        catch (Exception error) { System.err.println("RecallDesk: " + error.getMessage()); code = 2; }
        if (code != 0) System.exit(code);
    }

    static int run(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) { System.out.print(HELP); return 0; }
        if (args.length == 1 && args[0].equals("--version")) { System.out.println(VERSION); return 0; }
        if (args.length == 1 && args[0].equals("demo")) { Demo.run(); return 0; }
        String command = args.length == 0 || args[0].startsWith("--") ? "gui" : args[0];
        if (!java.util.List.of("gui", "info", "import", "export", "restore-backup").contains(command)) throw new IllegalArgumentException("Perintah tidak dikenal. Lihat --help.");
        int start = args.length == 0 || args[0].startsWith("--") ? 0 : 1;
        Map<String, String> options = new HashMap<>();
        for (int index = start; index < args.length; index++) {
            String key = args[index];
            if (options.containsKey(key)) throw new IllegalArgumentException("Opsi duplikat: " + key);
            if (key.equals("--sample") && command.equals("gui")) options.put(key, "true");
            else if (key.equals("--data") || (key.equals("--csv") && (command.equals("import") || command.equals("export")))) {
                if (++index >= args.length || args[index].startsWith("--")) throw new IllegalArgumentException("Nilai opsi belum diisi: " + key);
                options.put(key, args[index]);
            } else throw new IllegalArgumentException("Opsi tidak dikenal untuk perintah ini: " + key);
        }
        Path data = Path.of(options.getOrDefault("--data", Path.of(System.getProperty("user.home"), ".recalldesk", "library.rdb").toString()));
        if (command.equals("gui")) { openGui(data, options.containsKey("--sample")); return 0; }
        if ((command.equals("import") || command.equals("export")) && !options.containsKey("--csv")) throw new IllegalArgumentException("Opsi --csv wajib diisi.");
        try (Store store = new Store(data)) {
            if (command.equals("restore-backup")) {
                Library restored = store.restoreBackup(); System.out.println("{\"restored\":true,\"cards\":" + restored.cards().size() + ",\"revision\":" + restored.revision() + "}"); return 0;
            }
            StudyService service = new StudyService(store, Clock.systemUTC());
            if (command.equals("import")) {
                int count = service.importCsv(Path.of(options.get("--csv"))); System.out.println("{\"imported\":" + count + ",\"revision\":" + service.snapshot().revision() + "}");
            } else if (command.equals("export")) {
                service.exportCsv(Path.of(options.get("--csv"))); System.out.println("{\"exported\":" + service.snapshot().cards().size() + "}");
            } else {
                StudyService.Stats stats = service.stats(null);
                System.out.println("{\"cards\":" + stats.total() + ",\"due\":" + stats.due() + ",\"advanced\":" + stats.advanced() + ",\"reviews\":" + stats.reviews() + ",\"lapses\":" + stats.lapses() + ",\"revision\":" + service.snapshot().revision() + "}");
            }
        }
        return 0;
    }

    private static void openGui(Path path, boolean sample) throws IOException {
        if (GraphicsEnvironment.isHeadless()) throw new IOException("Tidak ada display desktop. Gunakan perintah demo/info, atau jalankan GUI pada komputer desktop.");
        Store store = new Store(path);
        final StudyService service;
        try {
            service = new StudyService(store, Clock.systemUTC());
            if (sample) {
                if (!service.snapshot().cards().isEmpty()) throw new IllegalArgumentException("--sample hanya untuk pustaka kosong; pilih --data baru.");
                service.add(Samples.cards());
            }
        } catch (IOException | RuntimeException error) { store.close(); throw error; }
        SwingUtilities.invokeLater(() -> {
            try {
                for (String key : java.util.List.of("Label.font", "Button.font", "TextField.font", "ComboBox.font", "Table.font", "TabbedPane.font")) {
                    UIManager.put(key, new java.awt.Font("Dialog", java.awt.Font.PLAIN, 14));
                }
                JFrame window = new JFrame("RecallDesk — Kartu belajar");
                AppPanel panel = new AppPanel(service); window.setContentPane(panel);
                window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); window.pack(); window.setMinimumSize(new Dimension(1000, 740));
                window.setLocationRelativeTo(null);
                window.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent event) {
                        if (panel.isBusy()) { panel.status.setText("Tunggu sampai proses penyimpanan selesai sebelum menutup aplikasi."); return; }
                        panel.stopRefresh();
                        try { store.close(); window.dispose(); }
                        catch (IOException error) { JOptionPane.showMessageDialog(window, error.getMessage(), "RecallDesk", JOptionPane.ERROR_MESSAGE); }
                    }
                });
                window.setVisible(true); panel.startRefresh();
            } catch (RuntimeException error) {
                try { store.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
                JOptionPane.showMessageDialog(null, error.getMessage(), "RecallDesk", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
