import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;

/**
 * Swing demo for iconst_/iadd/isub: fixed methods, fixed bytecode.
 *
 * The user selects a small method (add1, sub1, calc, ...) and may edit only
 * its parameters. The instruction list is what javac produced and never
 * changes - this avoids the false impression that bytecode can be assembled
 * at will.
 *
 * Run with:
 * javac OperandStackDemo.java
 * java OperandStackDemo
 *
 * Headless figure export:
 * java OperandStackDemo --render img
 */
public class OperandStackDemo {

    // ---- shared design language with the other demos in this workspace ----
    private static final Color CODE_COLOR = new Color(21, 101, 192);
    private static final Color VALUE_FILL = new Color(232, 240, 254);
    private static final Color FRESH_FILL = new Color(66, 133, 244);
    private static final Color READ_COLOR = new Color(244, 180, 0);
    private static final Color READ_FILL = new Color(255, 243, 224);
    private static final Color EMPTY_FILL = new Color(250, 250, 250);
    private static final Color GRID_LINE = new Color(174, 180, 186);
    private static final Color MUTED = new Color(117, 117, 117);
    private static final Color RETURN_COLOR = new Color(46, 125, 50);
    private static final Color INK = new Color(33, 33, 33);
    private static final Color OPCODE_BORDER = new Color(25, 103, 210);

    private static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font MONO_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 10);
    private static final Font SANS_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final Font VALUE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 15);
    private static final Font SOURCE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    // ---- model ----

    private enum Kind { ILOAD, ICONST, IADD, ISUB, IRETURN }

    private record Insn(int offset, int length, Kind kind, String text, String bytes,
                        String effect, String detail, int imm, int slot) {}

    private record Step(int active, int pc, int[] locals, List<Integer> stack, boolean freshTop,
                        boolean alive, Integer returned, int readLocal, int writtenLocal,
                        Integer value1, Integer value2, Integer result, String description) {}

    private record Example(String label, String prefix, String signature, int bodyLine,
                           byte[] code, int maxStack, List<String> localNames, String footer,
                           List<Insn> insns, List<Step> steps) {
        int paramCount() { return localNames().size() - 1; }
    }

    private static final String[] SOURCE_LINES = {
            "class ShowOps {",
            "    int addM1(int a) { return a + (-1); }",
            "    int add0(int a)  { return a + 0; }",
            "    int add1(int a)  { return a + 1; }",
            "    int add2(int a)  { return a + 2; }",
            "    int add3(int a)  { return a + 3; }",
            "    int add4(int a)  { return a + 4; }",
            "    int add5(int a)  { return a + 5; }",
            "    int sub1(int a)  { return a - 1; }",
            "    int sub2(int a)  { return a - 2; }",
            "    int sub3(int a)  { return a - 3; }",
            "    int calc(int a, int b, int c) { return a - (b + c); }",
            "}"
    };

    // ---- build examples from verified javac output ----

    private static Example add(String name, String prefix, int bodyLine, int iconstOp, String footer) {
        byte[] code = {0x1b, (byte) iconstOp, 0x60, (byte) 0xac};
        return build(name, prefix, name + "(int a)", bodyLine, code, 2, List.of("this", "a"), footer);
    }

    private static Example sub(String name, String prefix, int bodyLine, int iconstOp, String footer) {
        byte[] code = {0x1b, (byte) iconstOp, 0x64, (byte) 0xac};
        return build(name, prefix, name + "(int a)", bodyLine, code, 2, List.of("this", "a"), footer);
    }

    private static Example build(String label, String prefix, String signature, int bodyLine,
                                 byte[] code, int maxStack, List<String> localNames, String footer) {
        List<Insn> insns = decode(code, localNames);
        int[] vals = new int[localNames.size()];
        for (int i = 1; i < vals.length; i++) vals[i] = prefix.equals("calc") ? new int[]{2, 3, 4}[i - 1] : 10;
        List<Step> steps = simulate(insns, code, localNames, vals, signature);
        return new Example(label, prefix, signature, bodyLine, code, maxStack, localNames, footer,
                List.copyOf(insns), List.copyOf(steps));
    }

    private static Example[] allExamples() {
        return new Example[]{
                add("addM1", "addm1", 2, 0x02, "iload_1 -> iconst_m1 -> iadd -> ireturn"),
                add("add0",  "add0",  3, 0x03, "iload_1 -> iconst_0 -> iadd -> ireturn"),
                add("add1",  "add1",  4, 0x04, "iload_1 -> iconst_1 -> iadd -> ireturn"),
                add("add2",  "add2",  5, 0x05, "iload_1 -> iconst_2 -> iadd -> ireturn"),
                add("add3",  "add3",  6, 0x06, "iload_1 -> iconst_3 -> iadd -> ireturn"),
                add("add4",  "add4",  7, 0x07, "iload_1 -> iconst_4 -> iadd -> ireturn"),
                add("add5",  "add5",  8, 0x08, "iload_1 -> iconst_5 -> iadd -> ireturn"),
                sub("sub1",  "sub1",  9, 0x04, "iload_1 -> iconst_1 -> isub -> ireturn"),
                sub("sub2",  "sub2", 10, 0x05, "iload_1 -> iconst_2 -> isub -> ireturn"),
                sub("sub3",  "sub3", 11, 0x06, "iload_1 -> iconst_3 -> isub -> ireturn"),
                build("calc", "calc", "calc(int a, int b, int c)", 12,
                        new byte[]{0x1b, 0x1c, 0x1d, 0x60, 0x64, (byte) 0xac}, 3,
                        List.of("this", "a", "b", "c"),
                        "iload_1 -> iload_2 -> iload_3 -> iadd -> isub -> ireturn (stack=3)"),
        };
    }

    private static List<Insn> decode(byte[] code, List<String> locals) {
        List<Insn> out = new ArrayList<>();
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xff;
            Kind kind;
            String mn;
            int len;
            int imm = 0;
            int slot = 0;
            switch (op) {
                case 0x1a, 0x1b, 0x1c, 0x1d -> {
                    kind = Kind.ILOAD; mn = "iload_" + (op - 0x1a); len = 1;
                    slot = op - 0x1a;
                }
                case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 -> {
                    kind = Kind.ICONST; mn = op == 0x02 ? "iconst_m1" : "iconst_" + (op - 0x03);
                    len = 1; imm = op == 0x02 ? -1 : op - 0x03;
                }
                case 0x60 -> { kind = Kind.IADD; mn = "iadd"; len = 1; }
                case 0x64 -> { kind = Kind.ISUB; mn = "isub"; len = 1; }
                case 0xac -> { kind = Kind.IRETURN; mn = "ireturn"; len = 1; }
                default -> throw new IllegalStateException("opcode not in demo subset: " + hex(code[pc]));
            }
            StringBuilder bytes = new StringBuilder();
            for (int k = 0; k < len; k++) {
                if (k > 0) bytes.append(' ');
                bytes.append(hex(code[pc + k]));
            }
            String text = mn;
            if (kind == Kind.ICONST) text = mn + " " + imm;
            String effect = switch (kind) {
                case ILOAD -> "push " + (slot < locals.size() ? locals.get(slot) : "slot " + slot);
                case ICONST -> "push " + imm;
                case IADD -> "pop 2, push sum";
                case ISUB -> "pop 2, push difference";
                case IRETURN -> "return int";
            };
            String detail = switch (kind) {
                case ILOAD -> "one byte: opcode 0x" + hex(code[pc]) + " already encodes slot " + slot;
                case ICONST -> "the constant " + imm + " is encoded in opcode 0x" + hex(code[pc]);
                case IADD -> "pop value2 then value1, push value1 + value2";
                case ISUB -> "pop value2 then value1, push value1 - value2";
                case IRETURN -> "pop the result and return to the caller";
            };
            out.add(new Insn(pc, len, kind, text, bytes.toString(), effect, detail, imm, slot));
            pc += len;
        }
        return out;
    }

    /** Default parameters; slot 0 is `this` (displayed only, never used). */
    private static int[] initialVals(Example ex) {
        int[] vals = new int[ex.localNames().size()];
        for (int i = 1; i < vals.length; i++) vals[i] = ex.prefix().equals("calc") ? new int[]{2, 3, 4}[i - 1] : 10;
        return vals;
    }

    private static List<Step> simulate(List<Insn> insns, byte[] code, List<String> locals, int[] vals,
                                       String signature) {
        List<Integer> stack = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(-1, 0, vals.clone(), List.of(), false, true, null, -1, -1, null, null, null,
                "Method " + signature + " — the instruction list is fixed: javac decided it at compile time. "
                        + "You can only change the parameter values. Use Step to watch each instruction move "
                        + "values between the locals and the operand stack."));
        for (int i = 0; i < insns.size(); i++) {
            Insn in = insns.get(i);
            int readLocal = -1, writtenLocal = -1;
            boolean freshTop = false, alive = true;
            Integer returned = null, value1 = null, value2 = null, result = null;
            String desc = "";
            switch (in.kind()) {
                case ILOAD -> {
                    int v = vals[in.slot()];
                    stack.add(v); readLocal = in.slot(); freshTop = true;
                    desc = in.offset() + ": " + in.text() + " — " + in.detail()
                            + ". Local " + locals.get(in.slot()) + " = " + v + " is pushed (this is value1).";
                }
                case ICONST -> {
                    stack.add(in.imm()); freshTop = true;
                    desc = in.offset() + ": " + in.text() + " — " + in.detail()
                            + ". Push " + in.imm() + " (this is value2).";
                }
                case IADD -> {
                    value2 = stack.remove(stack.size() - 1);
                    value1 = stack.remove(stack.size() - 1);
                    result = value1 + value2;
                    stack.add(result); freshTop = true;
                    desc = in.offset() + ": iadd — pop value2 = " + value2 + " from the top, then value1 = " + value1
                            + " below it; push value1 + value2 = " + result + ".";
                }
                case ISUB -> {
                    value2 = stack.remove(stack.size() - 1);
                    value1 = stack.remove(stack.size() - 1);
                    result = value1 - value2;
                    stack.add(result); freshTop = true;
                    desc = in.offset() + ": isub — pop value2 = " + value2 + " from the top, then value1 = " + value1
                            + " below it; push value1 - value2 = " + result + ". Order matters.";
                }
                case IRETURN -> {
                    returned = stack.remove(stack.size() - 1);
                    alive = false;
                    desc = in.offset() + ": ireturn — pop " + returned + " and return it to the caller.";
                }
            }
            steps.add(new Step(i, in.offset() + in.length(), vals.clone(), List.copyOf(stack), freshTop,
                    alive, returned, readLocal, writtenLocal, value1, value2, result, desc));
        }
        return steps;
    }

    private static Example withValues(Example ex, int[] vals) {
        List<Step> steps = simulate(ex.insns(), ex.code(), ex.localNames(), vals, ex.signature());
        return new Example(ex.label(), ex.prefix(), ex.signature(), ex.bodyLine(), ex.code(),
                ex.maxStack(), ex.localNames(), ex.footer(), ex.insns(), List.copyOf(steps));
    }

    private static String hex(byte b) { return String.format("%02X", b & 0xff); }

    private static void box(Graphics2D g, int x, int y, int w, int h, Color fill, Color border, float stroke) {
        g.setColor(fill); g.fillRect(x, y, w, h);
        g.setColor(border); g.setStroke(new BasicStroke(stroke)); g.drawRect(x, y, w, h);
        g.setStroke(new BasicStroke(1f));
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        g.setFont(SANS_SMALL); g.setColor(MUTED); g.drawString(text, x, y);
    }

    // ---- application state ----

    private Example example;
    private int[] vals;
    private int step;
    private int speed = 700;
    private JFrame frame;
    private JComboBox<Example> methodBox;
    private JButton stepButton, runButton, resetButton;
    private JLabel progressLabel;
    private JTextArea statusArea;
    private SourceView sourceView;
    private InsnView insnView;
    private FrameView frameView;
    private JPanel paramPanel;
    private JTextField[] paramFields;
    private Timer timer;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--render".equals(args[0])) {
            renderPreviews(args[1]);
            return;
        }
        SwingUtilities.invokeLater(OperandStackDemo::new);
    }

    private static void renderPreviews(String dir) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File out = new File(dir);
        out.mkdirs();
        for (Example ex : allExamples()) {
            int[] vals = initialVals(ex);
            Example withVals = withValues(ex, vals);
            int last = withVals.steps().size() - 1;
            int[] picks = {0, Math.min(1, last), Math.min(2, last), Math.min(3, last),
                    Math.max(0, last - 2), Math.max(0, last - 1), last};
            for (int i : picks) {
                FrameView fv = new FrameView();
                fv.setExample(withVals);
                fv.setStep(i);
                Dimension fs = fv.getPreferredSize();
                fv.setSize(fs);
                writePng(fv, fs.width, fs.height, new File(out, ex.prefix() + "-step" + i + "-frame.png"));
            }
        }
        System.out.println("rendered to " + out.getAbsolutePath());
    }

    private static void writePng(JPanel panel, int w, int h, File file) throws Exception {
        int scale = 2;
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(scale, scale);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        panel.paint(g);
        g.dispose();
        ImageIO.write(img, "png", file);
    }

    private OperandStackDemo() { createAndShowGui(); }

    private void createAndShowGui() {
        frame = new JFrame("Operand stack — fixed bytecode, chosen methods (iconst / iadd / isub)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));
        control.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel methodRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        methodRow.setAlignmentX(0f);
        methodBox = new JComboBox<>(allExamples());
        methodBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel l = new JLabel(value == null ? "" : value.label() + "  —  " + value.signature());
            l.setFont(MONO);
            l.setOpaque(true);
            l.setBackground(selected ? VALUE_FILL : Color.WHITE);
            return l;
        });
        stepButton = new JButton("Step");
        stepButton.setFont(CODE_FONT);
        runButton = new JButton("Run");
        resetButton = new JButton("Reset");
        progressLabel = new JLabel();
        progressLabel.setFont(MONO);
        progressLabel.setForeground(MUTED);
        methodRow.add(new JLabel("method"));
        methodRow.add(methodBox);
        methodRow.add(stepButton);
        methodRow.add(runButton);
        methodRow.add(resetButton);
        methodRow.add(new JLabel("  speed"));
        JSlider speedSlider = new JSlider(150, 1400, speed);
        speedSlider.setPreferredSize(new Dimension(110, 24));
        speedSlider.addChangeListener(e -> { speed = speedSlider.getValue(); timer.setDelay(speed); });
        methodRow.add(speedSlider);
        methodRow.add(progressLabel);
        control.add(methodRow);

        paramPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        paramPanel.setAlignmentX(0f);
        control.add(paramPanel);

        sourceView = new SourceView();
        insnView = new InsnView();
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        left.add(sourceView);
        left.add(insnView);

        frameView = new FrameView();
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        right.add(frameView);

        JScrollPane leftScroll = new JScrollPane(left);
        leftScroll.setBorder(null);
        leftScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollPane rightScroll = new JScrollPane(right);
        rightScroll.setBorder(null);
        rightScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, rightScroll);
        split.setBorder(null);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.44);

        statusArea = new JTextArea(2, 0);
        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setFont(MONO);
        statusArea.setBackground(Color.WHITE);
        statusArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(224, 224, 224)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        frame.add(control, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(statusArea, BorderLayout.SOUTH);

        timer = new Timer(speed, e -> doStep());
        stepButton.addActionListener(e -> doStep());
        runButton.addActionListener(e -> startRun());
        resetButton.addActionListener(e -> resetDemo());
        methodBox.addActionListener(e -> {
            Example ex = (Example) methodBox.getSelectedItem();
            example = ex;
            vals = initialVals(ex);
            apply();
        });

        example = (Example) methodBox.getSelectedItem();
        vals = initialVals(example);
        apply();

        frame.setSize(1180, 960);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        split.setDividerLocation(0.44);
    }

    private void apply() {
        Example withVals = withValues(example, vals);
        example = withVals;
        sourceView.setExample(example);
        insnView.setExample(example);
        frameView.setExample(example);
        updateParamPanel();
        resetDemo();
    }

    private void updateParamPanel() {
        paramPanel.removeAll();
        paramPanel.add(new JLabel("Parameters (only these change; bytecode stays fixed):"));
        paramFields = new JTextField[example.paramCount()];
        for (int i = 0; i < paramFields.length; i++) {
            paramPanel.add(new JLabel(example.localNames().get(i + 1) + " ="));
            paramFields[i] = new JTextField(String.valueOf(vals[i + 1]), 5);
            paramFields[i].setFont(MONO);
            paramFields[i].addActionListener(e -> applyParams());
            paramPanel.add(paramFields[i]);
        }
        JButton applyButton = new JButton("Apply");
        applyButton.setToolTipText("Re-simulate with new arguments; the instruction list will not change");
        applyButton.addActionListener(e -> applyParams());
        paramPanel.add(applyButton);
        paramPanel.revalidate();
        paramPanel.repaint();
    }

    private void applyParams() {
        int[] next = vals.clone();
        for (int i = 0; i < paramFields.length; i++) {
            try { next[i + 1] = Integer.parseInt(paramFields[i].getText().trim()); }
            catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(frame, "Parameters must be integers", "Invalid parameter",
                        JOptionPane.ERROR_MESSAGE);
                paramFields[i].setText(String.valueOf(vals[i + 1]));
                return;
            }
        }
        vals = next;
        apply();
    }

    private void doStep() {
        if (step >= example.steps().size() - 1) return;
        step++;
        refresh();
        if (step == example.steps().size() - 1) {
            timer.stop();
            stepButton.setEnabled(false);
            runButton.setEnabled(false);
        }
    }

    private void startRun() {
        if (step >= example.steps().size() - 1) return;
        stepButton.setEnabled(false);
        runButton.setEnabled(false);
        timer.start();
    }

    private void resetDemo() {
        timer.stop();
        step = 0;
        stepButton.setEnabled(true);
        runButton.setEnabled(true);
        refresh();
    }

    private void refresh() {
        Step s = example.steps().get(step);
        sourceView.setActiveLine(example.bodyLine());
        insnView.setActive(s.active());
        frameView.setStep(step);
        progressLabel.setText("   step " + step + " / " + (example.steps().size() - 1)
                + "    pc = " + s.pc() + " / " + example.code().length);
        statusArea.setText(s.description());
        statusArea.setCaretPosition(0);
    }

    // ---- source panel ----

    private static final class SourceView extends JPanel {
        private int activeLine = 4;
        SourceView() {
            setBackground(Color.WHITE);
            int h = 26 * SOURCE_LINES.length + 44;
            setPreferredSize(new Dimension(500, h));
            setMinimumSize(new Dimension(420, h));
            setBorder(new TitledBorder("ShowOps.java — bytecode is fixed after compilation"));
        }
        void setExample(Example ex) { setActiveLine(ex.bodyLine()); }
        void setActiveLine(int line) { activeLine = line; repaint(); }
        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int lineHeight = 26, top = 10;
            for (int i = 0; i < SOURCE_LINES.length; i++) {
                int lineNo = 1 + i, y = top + i * lineHeight;
                if (lineNo == activeLine) {
                    g.setColor(VALUE_FILL); g.fillRect(8, y - 3, getWidth() - 16, lineHeight);
                    g.setColor(CODE_COLOR); g.fillRect(8, y - 3, 3, lineHeight);
                }
                g.setFont(MONO_SMALL); g.setColor(MUTED);
                String num = String.valueOf(lineNo);
                g.drawString(num, 26 - g.getFontMetrics().stringWidth(num), y + 15);
                g.setFont(SOURCE_FONT); g.setColor(INK);
                g.drawString(SOURCE_LINES[i], 44, y + 15);
            }
        }
    }

    // ---- decoded instructions panel ----

    private static final class InsnView extends JPanel {
        private static final int ROW_H = 28;
        private Example ex = null;
        private int active = -1;
        InsnView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(500, 160));
            setMinimumSize(new Dimension(420, 160));
            setBorder(new TitledBorder("decoded instructions — javac output, not editable"));
        }
        void setExample(Example ex) {
            this.ex = ex;
            int h = Math.max(120, 10 + ex.insns().size() * ROW_H + 34);
            setPreferredSize(new Dimension(500, h));
            setMinimumSize(new Dimension(420, h));
            revalidate(); repaint();
        }
        void setActive(int index) { active = index; repaint(); }
        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (ex == null) return;
            int top = 8;
            for (int i = 0; i < ex.insns().size(); i++) {
                Insn in = ex.insns().get(i);
                int y = top + i * ROW_H;
                if (i == active) {
                    g.setColor(VALUE_FILL); g.fillRect(8, y, getWidth() - 16, ROW_H - 2);
                    g.setColor(CODE_COLOR); g.fillRect(8, y, 3, ROW_H - 2);
                }
                g.setFont(MONO_SMALL); g.setColor(MUTED);
                g.drawString(String.valueOf(in.offset()), 22, y + 19);
                g.setFont(MONO_SMALL); g.setColor(new Color(120, 120, 120));
                g.drawString(in.bytes(), 44, y + 19);
                g.setFont(i == active ? CODE_FONT : MONO);
                g.setColor(i == active ? CODE_COLOR : INK);
                g.drawString(in.text(), 96, y + 19);
                g.setFont(SANS_SMALL); g.setColor(MUTED);
                g.drawString(in.effect(), 214, y + 19);
            }
            g.setFont(SANS_SMALL); g.setColor(new Color(150, 150, 150));
            g.drawString(ex.footer(), 22, top + ex.insns().size() * ROW_H + 14);
        }
    }

    // ---- frame panel: locals + operand stack ----

    private static final class FrameView extends JPanel {
        private static final int LOCAL_W = 92, LOCAL_H = 48;
        private static final int STACK_W = 132, STACK_H = 38, GAP = 5;
        private Example ex = null;
        private int stepIdx = 0;
        FrameView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(520, 420));
            setMinimumSize(new Dimension(460, 360));
            setBorder(new TitledBorder("frame — locals & operand stack"));
        }
        void setExample(Example ex) { this.ex = ex; repaint(); }
        void setStep(int idx) { stepIdx = idx; repaint(); }
        private int neededHeight() {
            int stackTop = 36 + LOCAL_H + 46;
            int bottom = stackTop + ex.maxStack() * (STACK_H + GAP);
            return Math.max(360, bottom + 70);
        }
        @Override
        public Dimension getPreferredSize() {
            if (ex == null) return super.getPreferredSize();
            return new Dimension(Math.max(520, super.getPreferredSize().width), neededHeight());
        }
        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (ex == null) return;
            Step s = ex.steps().get(stepIdx);
            label(g, "locals", 20, 26);
            int lx = 20;
            for (int i = 0; i < ex.localNames().size(); i++) {
                boolean read = i == s.readLocal();
                int x = lx, y = 34;
                box(g, x, y, LOCAL_W, LOCAL_H,
                        read ? READ_FILL : VALUE_FILL,
                        read ? READ_COLOR : GRID_LINE, read ? 2.2f : 1f);
                g.setFont(VAR_FONT()); g.setColor(new Color(55, 71, 79));
                g.drawString(ex.localNames().get(i), x + 8, y + 16);
                g.setFont(VALUE_FONT); g.setColor(INK);
                g.drawString(String.valueOf(s.locals()[i]), x + 8, y + 37);
                g.setFont(MONO_SMALL); g.setColor(MUTED);
                g.drawString("slot " + i, x + 8, y + LOCAL_H + 13);
                lx += LOCAL_W + 10;
            }
            int stackTop = 36 + LOCAL_H + 46;
            label(g, "operand stack (bottom -> top)", 20, stackTop - 10);
            int bottom = stackTop + ex.maxStack() * (STACK_H + GAP);
            for (int u = 0; u < ex.maxStack(); u++) {
                int y = bottom - (u + 1) * (STACK_H + GAP);
                g.setColor(EMPTY_FILL); g.fillRect(20, y, STACK_W, STACK_H);
                g.setColor(new Color(189, 189, 189));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{5f, 4f}, 0f));
                g.drawRect(20, y, STACK_W, STACK_H);
                g.setStroke(new BasicStroke(1f));
            }
            for (int i = 0; i < s.stack().size(); i++) {
                boolean fresh = s.freshTop() && i == s.stack().size() - 1;
                int y = bottom - (i + 1) * (STACK_H + GAP);
                box(g, 20, y, STACK_W, STACK_H,
                        fresh ? FRESH_FILL : VALUE_FILL,
                        fresh ? OPCODE_BORDER : GRID_LINE, fresh ? 2f : 1f);
                g.setFont(VALUE_FONT); g.setColor(fresh ? Color.WHITE : INK);
                String v = String.valueOf(s.stack().get(i));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(v, 20 + (STACK_W - fm.stringWidth(v)) / 2, y + STACK_H / 2 + 5);
                g.setFont(MONO_SMALL);
                g.setColor(fresh ? new Color(220, 235, 255) : MUTED);
                g.drawString(i == s.stack().size() - 1 ? "top" : "depth " + i, 24, y + 13);
            }
            g.setFont(MONO); g.setColor(MUTED);
            g.drawString("depth = " + s.stack().size() + " / " + ex.maxStack(), 20 + STACK_W + 18, bottom - STACK_H / 2 + 5);
            if (!s.alive()) {
                String text = "returned " + s.returned() + " to the caller";
                g.setFont(CHIP_FONT()); FontMetrics fm = g.getFontMetrics();
                int cw = fm.stringWidth(text) + 28;
                int cx = Math.min(getWidth() - 28 - cw, 20), cy = getHeight() - 46;
                g.setColor(new Color(232, 245, 233)); g.fillRoundRect(cx, cy, cw, 38, 14, 14);
                g.setColor(RETURN_COLOR); g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(cx, cy, cw, 38, 14, 14); g.setStroke(new BasicStroke(1f));
                g.setColor(RETURN_COLOR); g.drawString(text, cx + 14, cy + 24);
            }
        }
        private static Font VAR_FONT() { return new Font(Font.SANS_SERIF, Font.BOLD, 11); }
        private static Font CHIP_FONT() { return new Font(Font.MONOSPACED, Font.BOLD, 12); }
    }
}
