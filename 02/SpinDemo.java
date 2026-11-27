import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;

/**
 * Step-by-step walkthrough of the {@code spin} method from JVMS 3.2
 * (Java Virtual Machine Specification, Java SE 27):
 *
 * <pre>
 * void spin() {
 *     int i;
 *     for (i = 0; i &lt; 100; i++) {
 *         ; // Loop body is empty
 *     }
 * }
 * </pre>
 *
 * javac 21 compiles it to:
 *
 * <pre>
 *   0 iconst_0      1 istore_1      2 iload_1       3 bipush 100
 *   5 if_icmpge 14  8 iinc 1 1     11 goto 2       14 return
 * </pre>
 *
 * (JVMS 3.2 shows an equivalent goto-first listing; the offsets differ.)
 *
 * max_stack = 2, max_locals = 2 (slot 0 = this, slot 1 = i).
 *
 * Run with:
 * javac SpinDemo.java
 * java SpinDemo
 */
public class SpinDemo {

    // ---- shared design language with the other demos in this workspace ----
    /** Blue for the language itself: highlighted source line and fresh values. */
    private static final Color CODE_COLOR = new Color(21, 101, 192);
    /** Dark gray for demo helper buttons. */
    private static final Color DEMO_COLOR = new Color(93, 93, 93);

    /** A value that already sits in a local slot or a stack depth unit. */
    private static final Color VALUE_FILL = new Color(232, 240, 254);
    /** A value written by the instruction executed in this step. */
    private static final Color FRESH_FILL = new Color(66, 133, 244);
    /** Amber marks the local slot read by the current instruction. */
    private static final Color READ_COLOR = new Color(244, 180, 0);
    private static final Color EMPTY_FILL = new Color(250, 250, 250);
    private static final Color GRID_LINE = new Color(174, 180, 186);
    private static final Color HIGHLIGHT_BG = new Color(232, 240, 254);
    private static final Color MUTED = new Color(117, 117, 117);
    private static final Color DONE_COLOR = new Color(46, 125, 50);

    private static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font MONO_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 10);
    private static final Font SANS_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    // ---- bytecode produced by javac 21 for spin() ----
    private record Instruction(int offset, String mnemonic, String effect) {
    }

    private static final List<Instruction> BYTECODE = List.of(
            new Instruction(0, "iconst_0", "push int constant 0"),
            new Instruction(1, "istore_1", "pop → local 1 (i)"),
            new Instruction(2, "iload_1", "push local 1 (i)"),
            new Instruction(3, "bipush 100", "push int constant 100"),
            new Instruction(5, "if_icmpge 14", "pop 2; i ≥ 100 → 14"),
            new Instruction(8, "iinc 1 1", "i = i + 1"),
            new Instruction(11, "goto 2", "jump back to 2"),
            new Instruction(14, "return", "return void"));

    /** One operand-stack depth unit; fresh means "pushed by the current instruction". */
    private record StackCell(String value, boolean fresh) {
    }

    /**
     * Everything the view needs about one moment of the execution.
     * freshLocal is the slot written by this step (-1 when none).
     */
    private record Snapshot(int activeInstruction, String[] locals, List<StackCell> stack,
                            boolean frameAlive, int freshLocal, String note, String description) {
    }

    private final List<Snapshot> snapshots = new ArrayList<>();
    /** 0 = frame pushed but nothing executed; last = return done. */
    private int step;

    private JButton stepButton;
    private JButton runButton;
    private JButton resetButton;
    private JLabel progressLabel;
    private JTextArea descriptionArea;

    private JFrame frame;
    private JSplitPane split;

    private SourceView sourceView;
    private BytecodeView bytecodeView;
    private FrameView frameView;

    private Timer timer;

    public static void main(String[] args) {
        if (args.length == 2 && "--export".equals(args[0])) {
            SwingUtilities.invokeLater(() -> new SpinDemo(args[1]));
            return;
        }
        SwingUtilities.invokeLater(SpinDemo::new);
    }

    private SpinDemo() {
        createAndShowGui();
    }

    /** Development helper: render a few states to PNG files, then close the window. */
    private SpinDemo(String exportDir) {
        createAndShowGui();
        SwingUtilities.invokeLater(() -> exportImages(exportDir));
    }

    private void exportImages(String dir) {
        try {
            Files.createDirectories(Path.of(dir));
            split.setDividerLocation(0.45);
            frame.validate();
            int last = snapshots.size() - 1;
            int[] steps = {0, 4, 5, 6, 7, last};
            String[] names = {"spin-frame", "spin-bipush", "spin-if", "spin-iinc",
                    "spin-goto", "spin-return"};
            for (int k = 0; k < steps.length; k++) {
                step = steps[k];
                refreshView();
                frame.validate();
                int w = frame.getContentPane().getWidth();
                int h = frame.getContentPane().getHeight();
                BufferedImage image = new BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics();
                g.scale(2, 2);
                frame.getContentPane().paint(g);
                g.dispose();
                ImageIO.write(image, "png", Path.of(dir, names[k] + ".png").toFile());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            frame.dispose();
        }
    }

    private void createAndShowGui() {
        frame = new JFrame("JVM frame demo — Spin.spin()");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        timer = new Timer(20, e -> doStep());

        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));
        control.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ---------- row 0: execution controls ----------
        stepButton = new JButton("Step ▶");
        stepButton.setFont(CODE_FONT);
        stepButton.setToolTipText("Execute the next bytecode instruction");

        runButton = new JButton("Run");
        runButton.setToolTipText("Execute the remaining instructions automatically");

        resetButton = new JButton("Reset");
        resetButton.setToolTipText("Demo control, not a bytecode instruction: back to the initial frame");

        JComboBox<String> speedBox = new JComboBox<>(new String[]{"Slow", "Normal", "Fast"});
        speedBox.setSelectedItem("Fast");
        speedBox.setToolTipText("Delay between two instructions while running");
        speedBox.addActionListener(e -> timer.setDelay(delayFor((String) speedBox.getSelectedItem())));

        progressLabel = new JLabel();
        progressLabel.setFont(MONO);
        progressLabel.setForeground(MUTED);

        control.add(row("execution", DEMO_COLOR, stepButton, runButton, resetButton,
                new JLabel("speed"), speedBox, progressLabel));

        // ---------- left column: source and bytecode ----------
        sourceView = new SourceView();
        bytecodeView = new BytecodeView();

        JPanel leftColumn = new JPanel();
        leftColumn.setLayout(new BoxLayout(leftColumn, BoxLayout.Y_AXIS));
        leftColumn.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        leftColumn.add(sourceView);
        leftColumn.add(bytecodeView);

        frameView = new FrameView();

        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftColumn, frameView);
        split.setBorder(null);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.42);

        // ---------- bottom: what the current step does ----------
        descriptionArea = new JTextArea(3, 0);
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setFont(MONO);
        descriptionArea.setBackground(Color.WHITE);
        descriptionArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(224, 224, 224)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        frame.add(control, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(descriptionArea, BorderLayout.SOUTH);

        // ---------- listeners ----------
        stepButton.addActionListener(e -> doStep());
        runButton.addActionListener(e -> toggleRun());
        resetButton.addActionListener(e -> resetDemo());

        resetDemo();

        frame.setSize(1120, 740);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        split.setDividerLocation(0.45);
    }

    private static int delayFor(String speed) {
        return switch (speed) {
            case "Slow" -> 600;
            case "Normal" -> 150;
            default -> 20;
        };
    }

    // ---- execution ----

    private void doStep() {
        if (step >= snapshots.size() - 1) {
            stopRun();
            return;
        }
        step++;
        refreshView();
        if (step == snapshots.size() - 1) {
            stopRun();
        }
    }

    private void toggleRun() {
        if (timer.isRunning()) {
            stopRun();
            return;
        }
        if (step >= snapshots.size() - 1) {
            return;
        }
        stepButton.setEnabled(false);
        runButton.setText("Pause");
        timer.start();
    }

    private void stopRun() {
        timer.stop();
        runButton.setText("Run");
        boolean more = step < snapshots.size() - 1;
        stepButton.setEnabled(more);
        runButton.setEnabled(more);
    }

    private void resetDemo() {
        timer.stop();
        snapshots.clear();
        snapshots.addAll(buildSnapshots());
        step = 0;
        runButton.setText("Run");
        stepButton.setEnabled(true);
        runButton.setEnabled(true);
        refreshView();
    }

    private void refreshView() {
        Snapshot snapshot = snapshots.get(step);
        sourceView.setActiveLine(8);
        bytecodeView.setActiveInstruction(snapshot.activeInstruction());
        frameView.setSnapshot(snapshot);
        progressLabel.setText("   step " + step + " / " + (snapshots.size() - 1)
                + "    i = " + snapshot.locals()[1]);
        descriptionArea.setText(snapshot.description());
        descriptionArea.setCaretPosition(0);
    }

    private static List<Snapshot> buildSnapshots() {
        List<Snapshot> list = new ArrayList<>();
        String[] locals = {"this", "—"};

        list.add(new Snapshot(-1, locals.clone(), List.of(), true, -1, "",
                "Frame pushed for spin(): slot 0 = this, slot 1 = i (not assigned yet), "
                        + "operand stack empty. Click Step to run 0: iconst_0."));

        // 0: iconst_0
        list.add(new Snapshot(0, locals.clone(), List.of(new StackCell("0", true)), true, -1, "",
                "0: iconst_0 — push int constant 0. The constant is implicit in the opcode, "
                        + "so no operand byte is needed. depth 0 → 1."));

        // 1: istore_1
        locals[1] = "0";
        list.add(new Snapshot(1, locals.clone(), List.of(), true, 1, "i = 0",
                "1: istore_1 — pop 0 and store it into local variable 1 (i = 0). depth 1 → 0."));

        // loop body: 2: iload_1 → 3: bipush 100 → 5: if_icmpge → 8: iinc → 11: goto 2
        int i = 0;
        int checks = 0;
        while (true) {
            checks++;

            // 2: iload_1
            list.add(new Snapshot(2, locals.clone(), List.of(new StackCell(String.valueOf(i), true)),
                    true, -1, "", "2: iload_1 — push local variable 1 (i = " + i + "). depth 0 → 1."));

            // 3: bipush 100
            list.add(new Snapshot(3, locals.clone(), List.of(
                    new StackCell(String.valueOf(i), false),
                    new StackCell("100", true)), true, -1, "",
                    "3: bipush 100 — push int constant 100, which is stored as an immediate operand. "
                            + "depth 1 → 2, the maximum for this frame."));

            // 5: if_icmpge 14
            boolean exit = i >= 100;
            String note = "i = " + i + (exit ? " ≥ 100 → true, jump to 14" : " ≥ 100 → false, continue at 8");
            list.add(new Snapshot(4, locals.clone(), List.of(), true, -1, note,
                    "5: if_icmpge 14 — pop 100 and i, then compare: " + i
                            + (exit ? " is not less than 100, so pc jumps to 14: return."
                            : " < 100, so execution continues at 8: iinc.")
                            + " depth 2 → 0."));
            if (exit) {
                break;
            }

            // 8: iinc 1 1
            i++;
            locals[1] = String.valueOf(i);
            list.add(new Snapshot(5, locals.clone(), List.of(), true, 1,
                    "i = " + (i - 1) + " → " + i,
                    "8: iinc 1, 1 — increment local variable 1 in place: i = " + (i - 1)
                            + " → " + i + ". There is no operand-stack traffic."));

            // 11: goto 2
            list.add(new Snapshot(6, locals.clone(), List.of(), true, -1,
                    "target 2: next iteration",
                    "11: goto 2 — unconditional jump back to offset 2: iload_1. "
                            + "The stack is empty, so the next iteration starts from a clean stack."));
        }

        // 14: return
        list.add(new Snapshot(7, locals.clone(), List.of(), false, -1, "",
                "14: return — after " + checks + " condition checks i = " + i
                        + ", so the frame is popped and spin returns void to its caller."));
        return list;
    }

    // ---- layout helpers ----

    private static JLabel rowCaption(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 11));
        label.setForeground(color);
        label.setPreferredSize(new Dimension(96, 18));
        return label;
    }

    private static JPanel row(String caption, Color color, Component... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(rowCaption(caption, color));
        for (Component c : items) {
            p.add(c);
        }
        return p;
    }

    // ---- source panel ----

    /** Spin.java excerpt; the loop line stays highlighted for every instruction. */
    private static final class SourceView extends JPanel {
        private static final String[] LINES = {
                "    void spin() {",
                "        int i;",
                "        for (i = 0; i < 100; i++) {",
                "            ; // Loop body is empty",
                "        }",
                "    }"
        };
        private static final int FIRST_LINE = 6;
        private int activeLine = 8;

        SourceView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(480, 200));
            setMinimumSize(new Dimension(420, 200));
            setBorder(new TitledBorder("Spin.java — class Spin"));
        }

        void setActiveLine(int line) {
            activeLine = line;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int lineHeight = 24;
            int top = 10;
            for (int i = 0; i < LINES.length; i++) {
                int lineNo = FIRST_LINE + i;
                int y = top + i * lineHeight;
                if (lineNo == activeLine) {
                    g.setColor(HIGHLIGHT_BG);
                    g.fillRect(8, y - 3, getWidth() - 16, lineHeight);
                    g.setColor(CODE_COLOR);
                    g.fillRect(8, y - 3, 3, lineHeight);
                }
                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                String num = String.valueOf(lineNo);
                g.drawString(num, 26 - g.getFontMetrics().stringWidth(num), y + 15);

                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                g.setColor(new Color(33, 33, 33));
                g.drawString(LINES[i], 44, y + 15);
            }

            g.setFont(SANS_SMALL);
            g.setColor(MUTED);
            g.drawString("executed method: Spin.spin()", 44, top + LINES.length * lineHeight + 6);
        }
    }

    // ---- bytecode panel ----

    /** The eight instructions emitted by javac 21, with the current one highlighted. */
    private static final class BytecodeView extends JPanel {
        private static final int ROW_H = 31;
        private int active = -1;

        BytecodeView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(480, 344));
            setMinimumSize(new Dimension(420, 344));
            setBorder(new TitledBorder("bytecode — max_stack = 2, max_locals = 2"));
        }

        void setActiveInstruction(int index) {
            active = index;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int top = 8;
            for (int i = 0; i < BYTECODE.size(); i++) {
                Instruction ins = BYTECODE.get(i);
                int y = top + i * ROW_H;
                if (i == active) {
                    g.setColor(HIGHLIGHT_BG);
                    g.fillRect(8, y, getWidth() - 16, ROW_H - 2);
                    g.setColor(CODE_COLOR);
                    g.fillRect(8, y, 3, ROW_H - 2);
                }

                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                g.drawString(String.valueOf(ins.offset()), 24, y + 20);

                g.setFont(CODE_FONT);
                g.setColor(i == active ? CODE_COLOR : new Color(33, 33, 33));
                g.drawString(ins.mnemonic(), 46, y + 20);

                g.setFont(SANS_SMALL);
                g.setColor(MUTED);
                g.drawString(ins.effect(), 178, y + 20);
            }

            g.setFont(SANS_SMALL);
            g.setColor(new Color(150, 150, 150));
            int footY = top + BYTECODE.size() * ROW_H + 18;
            g.drawString("javac 21 output for Spin.spin(); goto / if_icmpge are 3 bytes, bipush 2 bytes",
                    46, footY);
            g.drawString("JVMS 3.2 prints an equivalent goto-first listing", 46, footY + 16);
        }
    }

    // ---- frame panel: local variable table + operand stack ----

    private static final class FrameView extends JPanel {
        private static final int CELL_W = 120;
        private static final int CELL_H = 64;
        private static final int LVT_X = 30;
        private static final int CELL_TOP = 78;
        private static final int STACK_X = 370;
        private static final int STACK_W = 144;
        private static final int STACK_H = 58;
        private static final int STACK_GAP = 6;
        private static final int STACK_BOTTOM = 210;
        private static final int FRAME_BOTTOM = 306;

        private Snapshot snapshot;

        FrameView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(700, 390));
            setMinimumSize(new Dimension(640, 380));
        }

        void setSnapshot(Snapshot s) {
            snapshot = s;
            repaint();
        }

        private static int stackY(int index) {
            return STACK_BOTTOM - STACK_H - index * (STACK_H + STACK_GAP);
        }

        /** Largest monospaced bold size (maxSize down to 10) whose text fits maxWidth. */
        private Font fittedFont(String text, int maxWidth, int maxSize) {
            int size = maxSize;
            while (size > 10 && getFontMetrics(new Font(Font.MONOSPACED, Font.BOLD, size))
                    .stringWidth(text) > maxWidth) {
                size--;
            }
            return new Font(Font.MONOSPACED, Font.BOLD, size);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (snapshot == null) {
                return;
            }

            paintFrameBox(g);

            g.setFont(SANS_SMALL);
            g.setColor(MUTED);
            g.drawString("local variable table", LVT_X, 66);
            g.drawString("operand stack (depth units)", STACK_X, 66);

            boolean readI = snapshot.frameAlive()
                    && (snapshot.activeInstruction() == 2 || snapshot.activeInstruction() == 4);
            for (int slot = 0; slot < 2; slot++) {
                paintLocalCell(g, slot, snapshot.locals()[slot],
                        slot == 1 && readI,
                        slot == 1 && snapshot.freshLocal() == 1);
            }

            paintStack(g);

            int depth = snapshot.stack().size();
            g.setFont(MONO);
            g.setColor(MUTED);
            g.drawString("depth = " + depth + " / 2 units", STACK_X, 232);

            if (!snapshot.note().isEmpty()) {
                g.setFont(MONO);
                g.setColor(snapshot.frameAlive() ? new Color(33, 33, 33) : DONE_COLOR);
                g.drawString(snapshot.note(), LVT_X, 232);
            }

            if (snapshot.activeInstruction() == 2 && snapshot.frameAlive()) {
                paintPushArrow(g, 1, 0);
            }

            if (!snapshot.frameAlive()) {
                paintDoneChip(g);
            }

            paintLegend(g);
        }

        private void paintFrameBox(Graphics2D g) {
            boolean alive = snapshot.frameAlive();
            g.setColor(alive ? new Color(207, 216, 220) : new Color(200, 200, 200));
            if (alive) {
                g.setStroke(new BasicStroke(1.2f));
            } else {
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{6f, 5f}, 0f));
            }
            g.drawRect(14, 10, getWidth() - 28, FRAME_BOTTOM - 10);

            g.setFont(MONO);
            g.setColor(MUTED);
            String header = alive
                    ? "spin()    max_stack = 2    max_locals = 2"
                    : "spin() — frame popped";
            g.drawString(header, 28, 36);
        }

        private void paintLocalCell(Graphics2D g, int slot, String value, boolean read, boolean fresh) {
            int x = LVT_X + slot * CELL_W;
            int y = CELL_TOP;
            boolean alive = snapshot.frameAlive();

            g.setColor(!alive ? new Color(245, 245, 245) : fresh ? FRESH_FILL : VALUE_FILL);
            g.fillRect(x, y, CELL_W, CELL_H);
            g.setColor(read ? READ_COLOR : fresh ? new Color(25, 103, 210) : GRID_LINE);
            g.setStroke(new BasicStroke(read || fresh ? 2.5f : 1f));
            g.drawRect(x, y, CELL_W, CELL_H);

            // variable name, top-left
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(fresh ? Color.WHITE : alive ? new Color(55, 71, 79) : MUTED);
            g.drawString(slot == 0 ? "this" : "i", x + 8, y + 17);

            // declared type, top-right ("this" has no room and its value says "ref")
            if (slot != 0) {
                g.setFont(MONO_SMALL);
                g.setColor(fresh ? Color.WHITE : MUTED);
                String type = "int";
                g.drawString(type, x + CELL_W - 8 - g.getFontMetrics().stringWidth(type), y + 17);
            }

            // value, centered
            Font valueFont = fittedFont(value, CELL_W - 12, slot == 0 ? 13 : 20);
            g.setFont(valueFont);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(fresh ? Color.WHITE : alive ? (slot == 0 ? MUTED : new Color(33, 33, 33)) : MUTED);
            g.drawString(value, x + (CELL_W - fm.stringWidth(value)) / 2,
                    y + 45 - (20 - valueFont.getSize()) / 2);

            if (slot == 0) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
                g.setColor(new Color(150, 150, 150));
                String note = "not read by spin";
                g.drawString(note, x + (CELL_W - g.getFontMetrics().stringWidth(note)) / 2, y + 59);
            }

            // slot number below the cell, like the axis label in the article
            g.setFont(MONO_SMALL);
            g.setColor(MUTED);
            String label = "slot " + slot;
            g.drawString(label, x + (CELL_W - g.getFontMetrics().stringWidth(label)) / 2,
                    CELL_TOP + CELL_H + 16);
        }

        private void paintStack(Graphics2D g) {
            List<StackCell> stack = snapshot.stack();
            for (int i = 0; i < 2; i++) {
                int y = stackY(i);
                if (i < stack.size()) {
                    StackCell cell = stack.get(i);
                    g.setColor(cell.fresh() ? FRESH_FILL : VALUE_FILL);
                    g.fillRect(STACK_X, y, STACK_W, STACK_H);
                    g.setColor(cell.fresh() ? new Color(25, 103, 210) : GRID_LINE);
                    g.setStroke(new BasicStroke(cell.fresh() ? 2f : 1f));
                    g.drawRect(STACK_X, y, STACK_W, STACK_H);

                    Font valueFont = fittedFont(cell.value(), STACK_W - 12, 20);
                    g.setFont(valueFont);
                    FontMetrics fm = g.getFontMetrics();
                    g.setColor(cell.fresh() ? Color.WHITE : new Color(33, 33, 33));
                    g.drawString(cell.value(), STACK_X + (STACK_W - fm.stringWidth(cell.value())) / 2,
                            y + STACK_H / 2 + valueFont.getSize() / 3);
                } else {
                    g.setColor(EMPTY_FILL);
                    g.fillRect(STACK_X, y, STACK_W, STACK_H);
                    g.setColor(new Color(189, 189, 189));
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                            10f, new float[]{5f, 4f}, 0f));
                    g.drawRect(STACK_X, y, STACK_W, STACK_H);

                    g.setFont(SANS_SMALL);
                    g.setColor(new Color(158, 158, 158));
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString("empty", STACK_X + (STACK_W - fm.stringWidth("empty")) / 2,
                            y + STACK_H / 2 + 4);
                }

                // depth-unit index to the right of each cell: 0 is the bottom
                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                g.drawString("unit " + i, STACK_X + STACK_W + 10, y + STACK_H / 2 + 4);
            }
        }

        /** Dashed amber arrow: a local slot is copied to a fresh stack depth unit. */
        private void paintPushArrow(Graphics2D g, int localSlot, int stackIndex) {
            // start below the slot numbers so the arrow never crosses another unit
            int x1 = LVT_X + localSlot * CELL_W + CELL_W / 2;
            int y1 = CELL_TOP + CELL_H + 26;
            int x2 = STACK_X - 7;
            int y2 = stackY(stackIndex) + STACK_H / 2;

            g.setColor(READ_COLOR);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    10f, new float[]{6f, 4f}, 0f));
            g.drawLine(x1, y1, x2, y2);

            // anchor dot at the read slot
            g.setStroke(new BasicStroke(1f));
            g.fillOval(x1 - 3, y1 - 3, 6, 6);

            // arrowhead, aligned with the line direction
            double angle = Math.atan2(y2 - y1, x2 - x1);
            double size = 8;
            Path2D head = new Path2D.Double();
            head.moveTo(x2, y2);
            head.lineTo(x2 - size * Math.cos(angle - 0.4), y2 - size * Math.sin(angle - 0.4));
            head.lineTo(x2 - size * Math.cos(angle + 0.4), y2 - size * Math.sin(angle + 0.4));
            head.closePath();
            g.fill(head);
        }

        private void paintDoneChip(Graphics2D g) {
            String text = "return: spin() finished with i = 100";
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text) + 28;
            int x = Math.min(STACK_X - 20, getWidth() - 28 - w);
            int y = 252;
            int h = 42;

            g.setColor(new Color(232, 245, 233));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(DONE_COLOR);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(x, y, w, h, 14, 14);
            g.drawString(text, x + 14, y + 26);
        }

        private void paintLegend(Graphics2D g) {
            int y = FRAME_BOTTOM + 30;
            legendItem(g, 26, y, FRESH_FILL, null, "just written / pushed");
            legendItem(g, 188, y, VALUE_FILL, GRID_LINE, "value in local slot / stack unit");
            legendItem(g, 366, y, Color.WHITE, READ_COLOR, "read by this instruction");
            legendItem(g, 540, y, EMPTY_FILL, new Color(189, 189, 189), "empty");
        }

        private void legendItem(Graphics2D g, int x, int y, Color fill, Color border, String text) {
            g.setColor(fill);
            g.fillRect(x, y - 12, 14, 14);
            if (border != null) {
                g.setColor(border);
                g.setStroke(new BasicStroke(border.equals(READ_COLOR) ? 2f : 1f));
                g.drawRect(x, y - 12, 14, 14);
            } else {
                g.setColor(new Color(25, 103, 210));
                g.setStroke(new BasicStroke(1f));
                g.drawRect(x, y - 12, 14, 14);
            }
            g.setFont(SANS_SMALL);
            g.setColor(MUTED);
            g.drawString(text, x + 22, y);
        }
    }
}
