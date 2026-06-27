import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.stream.*;

public class SmartTaskScheduler extends JFrame {

    // ── Palette ──────────────────────────────
    static final Color BG       = new Color(0x0F1117);
    static final Color SURFACE  = new Color(0x1A1D27);
    static final Color CARD     = new Color(0x22263A);
    static final Color ACCENT   = new Color(0x6C63FF);
    static final Color TEXT     = new Color(0xEEEEF5);
    static final Color TEXT_DIM = new Color(0x8888AA);
    static final Color SUCCESS  = new Color(0x43E08C);
    static final Color WARNING  = new Color(0xFFB84C);
    static final Color DANGER   = new Color(0xFF5370);
    static final Color BORDER   = new Color(0x2E3150);

    static final String DATA_FILE = "tasks.json";
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── Data ─────────────────────────────────
    private final PriorityQueue<Task> taskQueue =
            new PriorityQueue<>(Comparator.comparingInt(Task::priorityScore).reversed());
    private final List<Task> allTasks = new ArrayList<>();

    // ── UI ───────────────────────────────────
    private TaskTableModel tableModel;
    private JTable table;
    private JLabel statusLabel;
    private JComboBox<String> filterCombo;
    private JTextField searchField;
    private Timer reminderTimer;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new SmartTaskScheduler().setVisible(true);
        });
    }

    public SmartTaskScheduler() {
        setTitle("Smart Task Scheduler");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 700);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());

        add(buildHeader(),  BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatus(), BorderLayout.SOUTH);

        loadTasks();
        startReminderTimer();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                saveTasks();
                if (reminderTimer != null) reminderTimer.cancel();
            }
        });
    }

    // ═══════════════════════════════════════
    //  UI Builders
    // ═══════════════════════════════════════
    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(SURFACE);
        header.setBorder(new MatteBorder(0, 0, 1, 0, BORDER));
        header.setPreferredSize(new Dimension(0, 64));

        JLabel title = makeLabel("⬡  Smart Task Scheduler", 20, Font.BOLD, ACCENT);
        title.setBorder(new EmptyBorder(0, 24, 0, 0));
        header.add(title, BorderLayout.WEST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        actions.setOpaque(false);
        actions.add(makeChip("+ New Task", ACCENT,   e -> openTaskDialog(null)));
        actions.add(makeChip("✓ Complete", SUCCESS,  e -> completeSelected()));
        actions.add(makeChip("✎ Edit",     WARNING,  e -> editSelected()));
        actions.add(makeChip("✕ Delete",   DANGER,   e -> deleteSelected()));
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JPanel buildCenter() {
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(BG);
        center.add(buildToolbar(),    BorderLayout.NORTH);
        center.add(buildTablePanel(), BorderLayout.CENTER);
        center.add(buildSidePanel(),  BorderLayout.EAST);
        return center;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        bar.setBackground(SURFACE);
        bar.setBorder(new MatteBorder(0, 0, 1, 0, BORDER));

        bar.add(makeLabel("Filter:", 12, Font.PLAIN, TEXT_DIM));
        filterCombo = new JComboBox<>(new String[]{
            "All Tasks","Today","This Week","High Priority","Overdue","Completed"});
        styleCombo(filterCombo);
        filterCombo.addActionListener(e -> applyFilter());
        bar.add(filterCombo);

        bar.add(Box.createHorizontalStrut(16));
        bar.add(makeLabel("Search:", 12, Font.PLAIN, TEXT_DIM));
        searchField = makeDarkField(20);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        bar.add(searchField);
        return bar;
    }

    private JScrollPane buildTablePanel() {
        tableModel = new TaskTableModel();
        table = new JTable(tableModel);
        styleTable(table);
        JScrollPane scroll = new JScrollPane(table);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private JPanel buildSidePanel() {
        JPanel side = new JPanel();
        side.setPreferredSize(new Dimension(220, 0));
        side.setBackground(SURFACE);
        side.setBorder(new MatteBorder(0, 1, 0, 0, BORDER));
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.add(sideSection("📊 Summary"));
        side.add(buildSummaryCards());
        side.add(Box.createVerticalStrut(16));
        side.add(sideSection("🔔 Upcoming"));
        side.add(buildUpcomingPanel());
        side.add(Box.createVerticalGlue());
        return side;
    }

    private JPanel buildSummaryCards() {
        JPanel p = new JPanel(new GridLayout(2, 2, 6, 6));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(8, 12, 8, 12));
        p.add(miniCard("Total",    () -> String.valueOf(allTasks.size()), ACCENT));
        p.add(miniCard("High",     () -> count(t -> t.priority == Priority.HIGH || t.priority == Priority.CRITICAL), DANGER));
        p.add(miniCard("Due Today",() -> count(t -> isToday(t.deadline)), WARNING));
        p.add(miniCard("Done",     () -> count(t -> t.completed), SUCCESS));
        return p;
    }

    private JPanel buildUpcomingPanel() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(4, 12, 4, 12));

        List<Task> soon = allTasks.stream()
            .filter(t -> !t.completed && t.deadline != null)
            .sorted(Comparator.comparing(t -> t.deadline))
            .limit(5).collect(Collectors.toList());

        for (Task t : soon) {
            JPanel row = new JPanel(new BorderLayout(4, 0));
            row.setOpaque(false);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            row.setBorder(new EmptyBorder(3, 0, 3, 0));
            row.add(makeLabel(truncate(t.title, 16), 11, Font.PLAIN, TEXT), BorderLayout.WEST);
            row.add(makeLabel(shortDate(t.deadline), 10, Font.PLAIN, priorityColor(t.priority)), BorderLayout.EAST);
            p.add(row);
        }
        if (soon.isEmpty()) p.add(makeLabel("No upcoming tasks", 11, Font.ITALIC, TEXT_DIM));
        return p;
    }

    private JPanel buildStatus() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(SURFACE);
        bar.setBorder(new MatteBorder(1, 0, 0, 0, BORDER));
        bar.setPreferredSize(new Dimension(0, 28));
        statusLabel = makeLabel("  Ready", 11, Font.PLAIN, TEXT_DIM);
        bar.add(statusLabel, BorderLayout.WEST);
        JLabel clock = makeLabel("", 11, Font.PLAIN, TEXT_DIM);
        clock.setBorder(new EmptyBorder(0, 0, 0, 12));
        bar.add(clock, BorderLayout.EAST);
        new javax.swing.Timer(1000, e ->
            clock.setText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE dd MMM  HH:mm:ss  ")))
        ).start();
        return bar;
    }

    // ═══════════════════════════════════════
    //  Task Dialog
    // ═══════════════════════════════════════
    private void openTaskDialog(Task existing) {
        JDialog dlg = new JDialog(this, existing == null ? "New Task" : "Edit Task", true);
        dlg.setSize(480, 420);
        dlg.setLocationRelativeTo(this);
        dlg.getContentPane().setBackground(CARD);
        dlg.setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(CARD);
        form.setBorder(new EmptyBorder(24, 28, 24, 28));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 4, 6, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;

        JTextField titleF = makeDarkField(0);
        JTextArea  descF  = new JTextArea(3, 0);
        descF.setBackground(SURFACE); descF.setForeground(TEXT);
        descF.setCaretColor(ACCENT);
        descF.setBorder(new LineBorder(BORDER));
        descF.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        descF.setLineWrap(true); descF.setWrapStyleWord(true);

        JComboBox<Priority> prioF = new JComboBox<>(Priority.values());
        styleCombo(prioF);

        JTextField deadF = makeDarkField(0);
        deadF.setText("yyyy-MM-dd HH:mm");
        deadF.setForeground(TEXT_DIM);
        deadF.addFocusListener(new FocusAdapter() {
            public void focusGained(FocusEvent e) {
                if (deadF.getText().equals("yyyy-MM-dd HH:mm")) {
                    deadF.setText(""); deadF.setForeground(TEXT);
                }
            }
        });

        JTextField catF = makeDarkField(0);

        if (existing != null) {
            titleF.setText(existing.title);
            descF.setText(existing.description);
            prioF.setSelectedItem(existing.priority);
            if (existing.deadline != null) { deadF.setText(existing.deadline.format(FMT)); deadF.setForeground(TEXT); }
            catF.setText(existing.category);
        }

        int row = 0;
        addFormRow(form, gc, row++, "Title *",     titleF);
        addFormRow(form, gc, row++, "Description", new JScrollPane(descF));
        addFormRow(form, gc, row++, "Priority",    prioF);
        addFormRow(form, gc, row++, "Deadline",    deadF);
        addFormRow(form, gc, row++, "Category",    catF);
        dlg.add(form, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        btns.setBackground(CARD);
        btns.add(makeChip("Cancel", TEXT_DIM, e -> dlg.dispose()));
        btns.add(makeChip(existing == null ? "Create" : "Save", ACCENT, e -> {
            String t = titleF.getText().trim();
            if (t.isEmpty()) { status("Title is required."); return; }
            LocalDateTime dl = null;
            String dText = deadF.getText().trim();
            if (!dText.isEmpty() && !dText.equals("yyyy-MM-dd HH:mm")) {
                try { dl = LocalDateTime.parse(dText, FMT); }
                catch (Exception ex) { status("Invalid date. Use yyyy-MM-dd HH:mm"); return; }
            }
            if (existing == null) {
                Task task = new Task(t, descF.getText().trim(),
                        (Priority) prioF.getSelectedItem(), dl, catF.getText().trim());
                allTasks.add(task);
                taskQueue.offer(task);
                status("Task created: " + t);
            } else {
                existing.title = t; existing.description = descF.getText().trim();
                existing.priority = (Priority) prioF.getSelectedItem();
                existing.deadline = dl; existing.category = catF.getText().trim();
                status("Task updated: " + t);
            }
            saveTasks(); applyFilter(); dlg.dispose();
        }));
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.setVisible(true);
    }

    // ═══════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════
    private void completeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) { status("Select a task first."); return; }
        Task t = tableModel.getTask(row);
        t.completed = !t.completed;
        saveTasks(); applyFilter();
        status(t.completed ? "✓ Marked complete: " + t.title : "Reopened: " + t.title);
    }

    private void editSelected() {
        int row = table.getSelectedRow();
        if (row < 0) { status("Select a task first."); return; }
        openTaskDialog(tableModel.getTask(row));
    }

    private void deleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0) { status("Select a task first."); return; }
        Task t = tableModel.getTask(row);
        int r = JOptionPane.showConfirmDialog(this,
            "Delete \"" + t.title + "\"?", "Confirm Delete",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            allTasks.remove(t); taskQueue.remove(t);
            saveTasks(); applyFilter(); status("Deleted: " + t.title);
        }
    }

    private void applyFilter() {
        String filter = (String) filterCombo.getSelectedItem();
        String search = searchField.getText().trim().toLowerCase();
        LocalDate today = LocalDate.now();

        List<Task> filtered = allTasks.stream().filter(t -> {
            if (!search.isEmpty() &&
                !t.title.toLowerCase().contains(search) &&
                !t.category.toLowerCase().contains(search)) return false;
            switch (filter) {
                case "Today":         return t.deadline != null && t.deadline.toLocalDate().equals(today);
                case "This Week":     return t.deadline != null &&
                                             !t.deadline.toLocalDate().isBefore(today) &&
                                             t.deadline.toLocalDate().isBefore(today.plusWeeks(1));
                case "High Priority": return t.priority == Priority.HIGH || t.priority == Priority.CRITICAL;
                case "Overdue":       return !t.completed && t.deadline != null &&
                                             t.deadline.isBefore(LocalDateTime.now());
                case "Completed":     return t.completed;
                default:              return true;
            }
        }).sorted(Comparator.comparingInt(Task::priorityScore).reversed())
          .collect(Collectors.toList());

        tableModel.setTasks(filtered);
        status(filtered.size() + " task(s) shown");
    }

    // ═══════════════════════════════════════
    //  Reminder Timer
    // ═══════════════════════════════════════
    private void startReminderTimer() {
        reminderTimer = new Timer(true);
        reminderTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                LocalDateTime now  = LocalDateTime.now();
                LocalDateTime soon = now.plusMinutes(15);
                for (Task t : allTasks) {
                    if (!t.completed && !t.reminded && t.deadline != null &&
                        t.deadline.isAfter(now) && t.deadline.isBefore(soon)) {
                        t.reminded = true;
                        SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(SmartTaskScheduler.this,
                                "⏰  \"" + t.title + "\" is due in ~15 minutes!",
                                "Task Reminder", JOptionPane.WARNING_MESSAGE));
                    }
                }
            }
        }, 0, 60_000);
    }

    // ═══════════════════════════════════════
    //  JSON Persistence (no external lib)
    // ═══════════════════════════════════════
    private void saveTasks() {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < allTasks.size(); i++) {
            Task t = allTasks.get(i);
            sb.append("  {")
              .append("\"id\":\"").append(esc(t.id)).append("\",")
              .append("\"title\":\"").append(esc(t.title)).append("\",")
              .append("\"description\":\"").append(esc(t.description)).append("\",")
              .append("\"priority\":\"").append(t.priority.name()).append("\",")
              .append("\"deadline\":\"").append(t.deadline != null ? t.deadline.format(FMT) : "").append("\",")
              .append("\"category\":\"").append(esc(t.category)).append("\",")
              .append("\"completed\":").append(t.completed).append(",")
              .append("\"created\":\"").append(t.created.format(FMT)).append("\"")
              .append("}").append(i < allTasks.size()-1 ? "," : "").append("\n");
        }
        sb.append("]");
        try (FileWriter fw = new FileWriter(DATA_FILE)) { fw.write(sb.toString()); }
        catch (IOException ex) { status("Save error: " + ex.getMessage()); }
    }

    private void loadTasks() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return;
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line; while ((line = br.readLine()) != null) sb.append(line).append("\n");
            }
            String json = sb.toString().trim();
            // Strip outer [ ]
            json = json.substring(1, json.lastIndexOf(']')).trim();
            if (json.isEmpty()) return;

            // Split objects on "},{"
            String[] objects = json.split("\\},\\s*\\{");
            for (String obj : objects) {
                obj = obj.replace("{","").replace("}","").trim();
                Map<String,String> m = parseSimpleJsonObject(obj);
                Task t = new Task(
                    m.getOrDefault("title",""),
                    m.getOrDefault("description",""),
                    Priority.valueOf(m.getOrDefault("priority","MEDIUM")),
                    m.getOrDefault("deadline","").isEmpty() ? null
                        : LocalDateTime.parse(m.get("deadline"), FMT),
                    m.getOrDefault("category","")
                );
                t.id = m.getOrDefault("id", UUID.randomUUID().toString().substring(0,8));
                t.completed = Boolean.parseBoolean(m.getOrDefault("completed","false"));
                try { t.created = LocalDateTime.parse(m.getOrDefault("created",
                        LocalDateTime.now().format(FMT)), FMT); } catch (Exception ignored) {}
                allTasks.add(t);
                taskQueue.offer(t);
            }
        } catch (Exception ex) { status("Load error: " + ex.getMessage()); }
        applyFilter();
    }

    /** Parses a flat JSON object (no nesting) into a String map. */
    private Map<String,String> parseSimpleJsonObject(String obj) {
        Map<String,String> map = new LinkedHashMap<>();
        // Regex-free: scan char by char
        int i = 0; int len = obj.length();
        while (i < len) {
            // find key
            while (i < len && obj.charAt(i) != '"') i++;
            if (i >= len) break; i++; // skip opening "
            int ks = i; while (i < len && obj.charAt(i) != '"') i++; String key = obj.substring(ks, i); i++;
            // find colon
            while (i < len && obj.charAt(i) != ':') i++; i++;
            // find value
            while (i < len && obj.charAt(i) == ' ') i++;
            String val;
            if (i < len && obj.charAt(i) == '"') {
                i++; int vs = i;
                while (i < len && obj.charAt(i) != '"') { if (obj.charAt(i)=='\\') i++; i++; }
                val = obj.substring(vs, i).replace("\\\"","\"").replace("\\n","\n").replace("\\\\","\\"); i++;
            } else {
                int vs = i; while (i < len && obj.charAt(i) != ',' && obj.charAt(i) != '\n') i++;
                val = obj.substring(vs, i).trim();
            }
            map.put(key, val);
        }
        return map;
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
    }

    // ═══════════════════════════════════════
    //  Table Model
    // ═══════════════════════════════════════
    static final String[] COLS = {"", "Title", "Priority", "Deadline", "Category", "Status"};

    class TaskTableModel extends AbstractTableModel {
        private List<Task> tasks = new ArrayList<>();
        void setTasks(List<Task> t)  { tasks = new ArrayList<>(t); fireTableDataChanged(); }
        Task getTask(int row)         { return tasks.get(row); }
        public int getRowCount()      { return tasks.size(); }
        public int getColumnCount()   { return COLS.length; }
        public String getColumnName(int c) { return COLS[c]; }
        public boolean isCellEditable(int r, int c) { return false; }
        public Object getValueAt(int r, int c) {
            Task t = tasks.get(r);
            switch (c) {
                case 0: return t.completed ? "✓" : " ";
                case 1: return t.title;
                case 2: return t.priority.label;
                case 3: return t.deadline != null ? t.deadline.format(FMT) : "—";
                case 4: return t.category.isEmpty() ? "—" : t.category;
                case 5: return t.completed ? "Done"
                             : (t.deadline != null && t.deadline.isBefore(LocalDateTime.now())) ? "Overdue"
                             : "Open";
                default: return "";
            }
        }
    }

    // ═══════════════════════════════════════
    //  Table Styling
    // ═══════════════════════════════════════
    private void styleTable(JTable t) {
        t.setBackground(BG); t.setForeground(TEXT); t.setGridColor(BORDER);
        t.setRowHeight(36); t.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        t.setSelectionBackground(ACCENT.darker()); t.setSelectionForeground(TEXT);
        t.setShowHorizontalLines(true); t.setShowVerticalLines(false);
        t.setFillsViewportHeight(true);

        JTableHeader h = t.getTableHeader();
        h.setBackground(SURFACE); h.setForeground(TEXT_DIM);
        h.setFont(new Font("Segoe UI", Font.BOLD, 12));
        h.setBorder(new MatteBorder(0, 0, 1, 0, BORDER));
        h.setPreferredSize(new Dimension(0, 32));

        t.getColumnModel().getColumn(0).setMaxWidth(28);
        t.getColumnModel().getColumn(2).setMaxWidth(120);
        t.getColumnModel().getColumn(3).setMaxWidth(170);
        t.getColumnModel().getColumn(5).setMaxWidth(90);

        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable tbl, Object value, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(tbl, value, sel, foc, row, col);
                setBackground(sel ? ACCENT.darker() : (row % 2 == 0 ? BG : CARD));
                setForeground(TEXT);
                setBorder(new EmptyBorder(0, 10, 0, 10));
                setFont(new Font("Segoe UI", Font.PLAIN, 13));
                Task task = tableModel.getTask(row);
                if (col == 2) { setForeground(priorityColor(task.priority)); setFont(new Font("Segoe UI", Font.BOLD, 12)); }
                if (col == 5) {
                    String v = value.toString();
                    setForeground("Done".equals(v) ? SUCCESS : "Overdue".equals(v) ? DANGER : TEXT_DIM);
                }
                if (task.completed && col != 0) {
                    setForeground(TEXT_DIM);
                    setText("<html><s>" + getText() + "</s></html>");
                }
                return this;
            }
        });

        t.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) editSelected(); }
        });
    }

    // ═══════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════
    private JLabel makeLabel(String text, int size, int style, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", style, size));
        l.setForeground(color);
        return l;
    }

    private JButton makeChip(String text, Color color, ActionListener al) {
        JButton b = new JButton(text) {
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed() ? color.darker() :
                            getModel().isRollover() ? color.brighter() : color);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                super.paintComponent(g2);
                g2.dispose();
            }
        };
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setPreferredSize(new Dimension(text.length() * 9 + 16, 34));
        b.setBorderPainted(false); b.setContentAreaFilled(false); b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(al);
        return b;
    }

    private JTextField makeDarkField(int cols) {
        JTextField f = cols > 0 ? new JTextField(cols) : new JTextField();
        f.setBackground(SURFACE); f.setForeground(TEXT); f.setCaretColor(ACCENT);
        f.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER), new EmptyBorder(4,8,4,8)));
        f.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        return f;
    }

    private <T> void styleCombo(JComboBox<T> c) {
        c.setBackground(SURFACE); c.setForeground(TEXT);
        c.setBorder(new LineBorder(BORDER));
        c.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    }

    private void addFormRow(JPanel p, GridBagConstraints gc, int row, String label, Component field) {
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        JLabel lbl = makeLabel(label, 12, Font.PLAIN, TEXT_DIM);
        lbl.setPreferredSize(new Dimension(100, 0));
        p.add(lbl, gc);
        gc.gridx = 1; gc.weightx = 1;
        p.add(field, gc);
    }

    private JPanel sideSection(String title) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        p.setBorder(new EmptyBorder(12, 12, 4, 12));
        p.add(makeLabel(title, 12, Font.BOLD, ACCENT));
        return p;
    }

    private JPanel miniCard(String title, java.util.function.Supplier<String> val, Color accent) {
        JPanel c = new JPanel(new BorderLayout(0, 2));
        c.setBackground(BG);
        c.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER), new EmptyBorder(6,8,6,8)));
        c.add(makeLabel(title, 10, Font.PLAIN, TEXT_DIM), BorderLayout.NORTH);
        JLabel vl = makeLabel(val.get(), 18, Font.BOLD, accent);
        c.add(vl, BorderLayout.CENTER);
        new javax.swing.Timer(2000, e -> vl.setText(val.get())).start();
        return c;
    }

    private void status(String msg) { statusLabel.setText("  " + msg); }

    private Color priorityColor(Priority p) {
        switch (p) {
            case CRITICAL: return DANGER;
            case HIGH:     return WARNING;
            case MEDIUM:   return ACCENT;
            default:       return SUCCESS;
        }
    }

    private boolean isToday(LocalDateTime dt) {
        return dt != null && dt.toLocalDate().equals(LocalDate.now());
    }

    private String count(java.util.function.Predicate<Task> pred) {
        return String.valueOf(allTasks.stream().filter(pred).count());
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max-1) + "…" : s;
    }

    private String shortDate(LocalDateTime dt) {
        if (dt == null) return "";
        return dt.format(DateTimeFormatter.ofPattern("MMM d"));
    }

    // ═══════════════════════════════════════
    //  Domain: Priority
    // ═══════════════════════════════════════
    enum Priority {
        LOW("Low"), MEDIUM("Medium"), HIGH("High"), CRITICAL("Critical");
        final String label;
        Priority(String l) { this.label = l; }
        public String toString() { return label; }
    }

    // ═══════════════════════════════════════
    //  Domain: Task
    // ═══════════════════════════════════════
    static class Task {
        String id, title, description, category;
        Priority priority;
        LocalDateTime deadline, created;
        boolean completed, reminded;

        Task(String title, String desc, Priority priority, LocalDateTime deadline, String category) {
            this.id          = UUID.randomUUID().toString().substring(0, 8);
            this.title       = title;
            this.description = desc;
            this.priority    = priority;
            this.deadline    = deadline;
            this.category    = category;
            this.created     = LocalDateTime.now();
        }

        int priorityScore() {
            if (completed) return 0;
            int base = (priority == Priority.CRITICAL) ? 400 :
                       (priority == Priority.HIGH)     ? 300 :
                       (priority == Priority.MEDIUM)   ? 200 : 100;
            if (deadline == null) return base;
            long h = java.time.temporal.ChronoUnit.HOURS.between(LocalDateTime.now(), deadline);
            if (h < 0)  return base + 600;
            if (h < 24) return base + 300;
            if (h < 72) return base + 100;
            return base;
        }
    }
}