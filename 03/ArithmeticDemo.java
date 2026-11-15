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
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.imageio.ImageIO;
import javax.swing.border.TitledBorder;

/**
 * Step-by-step walkthrough of three JVM frames:
 *
 * <pre>
 *   add(int, int)     : iload_1  iload_2  iadd  ireturn                 (max_stack 2, max_locals 3)
 *   add(long, long)   : lload_1  lload_3  ladd  lreturn                 (max_stack 4, max_locals 5)
 *   align2grain       : 12 instructions, depth peaks at 3               (max_stack 3, max_locals 3)
 * </pre>
 *
 * The first two show that the instruction prefix carries the operand type
 * (i / l) and that the stack depth follows that type — an int is one unit deep,
 * a long is two. The third is the example from JVMS 3.3: two sub-expressions
 * take turns on the stack, and one of the values pushed is a constant that
 * never appears in the local variable table.
 *
 * Run with:
 * javac ArithmeticDemo.java
 * java ArithmeticDemo
 */
public class ArithmeticDemo {

    // ---- shared design language with the other demos in this workspace ----
    private static final Color CODE_COLOR = new Color(21, 101, 192);
    private static final Color DEMO_COLOR = new Color(93, 93, 93);
    private static final Color VALUE_FILL = new Color(232, 240, 254);
    private static final Color FRESH_FILL = new Color(66, 133, 244);
    private static final Color READ_COLOR = new Color(244, 180, 0);
    private static final Color EMPTY_FILL = new Color(250, 250, 250);
    private static final Color GRID_LINE = new Color(174, 180, 186);
    private static final Color HIGHLIGHT_BG = new Color(232, 240, 254);
    private static final Color MUTED = new Color(117, 117, 117);
    private static final Color RETURN_COLOR = new Color(46, 125, 50);

    private static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font MONO_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 10);
    private static final Font SANS_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

    // ---- the two methods the demo can execute ----
    private record Instruction(int offset, String mnemonic, String effect) {
    }

    /** One value in the local variable table; size is 1 for int, 2 for long. */
    private record LocalSlot(String name, String value, String type, int size) {
    }

    /** One value on the operand stack; size is 1 for int, 2 for long. */
    private record StackValue(String value, int size, boolean fresh) {
    }

    /**
     * Everything the views need about one moment of the execution.
     *
     * <p>{@code readLocal} and {@code pushFromLocal} index into {@code locals};
     * -1 means "no local involved" — a constant push has no source slot, so no
     * arrow is drawn. {@code caption} is the short arithmetic remark shown in
     * front of the bottom of the stack, if the instruction deserves one.
     */
    private record Snapshot(int active, List<LocalSlot> locals, List<StackValue> stack,
                            boolean alive, String returned, int readLocal, int pushFromLocal,
                            String caption, String description) {
    }

    private enum Kind { ADD, ALIGN }

    private record MethodSpec(String label, Kind kind, String type, int maxStack, int maxLocals,
                              int callLine, int bodyLine, List<Instruction> bytecode) {

        boolean wide() {
            return "long".equals(type);
        }

        String signature() {
            return kind == Kind.ADD
                    ? "add(" + type + " a, " + type + " b)"
                    : "align2grain(int i, int grain)";
        }

        String sourceTitle() {
            return kind == Kind.ADD ? "Demo.java — class SimpleAdder" : "Demo.java — class Align";
        }

        String footer() {
            return kind == Kind.ADD
                    ? "the prefix carries the type: i = int, l = long"
                    : "12 instructions: two sub-expressions take turns on the stack";
        }

        String[] sourceLines() {
            if (kind == Kind.ALIGN) {
                return new String[]{
                        "class Align {",
                        "    int align2grain(int i, int grain) {",
                        "        return ((i + grain - 1) & ~(grain - 1));",
                        "    }",
                        "}"
                };
            }
            return new String[]{
                    "class SimpleAdder {",
                    "    public " + type + " add(" + type + " a, " + type + " b) {",
                    "        return a + b;",
                    "    }",
                    "}"
            };
        }
    }

    private static final MethodSpec INT_ADD = new MethodSpec("add(int, int)", Kind.ADD, "int", 2, 3, 10, 11,
            List.of(
                    new Instruction(0, "iload_1", "push local 1 (a)        depth 0 → 1"),
                    new Instruction(1, "iload_2", "push local 2 (b)        depth 1 → 2"),
                    new Instruction(2, "iadd", "pop 2, push sum         depth 2 → 1"),
                    new Instruction(3, "ireturn", "return sum              depth 1 → 0")));

    private static final MethodSpec LONG_ADD = new MethodSpec("add(long, long)", Kind.ADD, "long", 4, 5, 10, 11,
            List.of(
                    new Instruction(0, "lload_1", "push local 1+2 (a)      depth 0 → 2"),
                    new Instruction(1, "lload_3", "push local 3+4 (b)      depth 2 → 4"),
                    new Instruction(2, "ladd", "pop 4, push sum         depth 4 → 2"),
                    new Instruction(3, "lreturn", "return sum              depth 2 → 0")));

    /** JVMS 3.3: ((i + grain - 1) & ~(grain - 1)), twelve instructions, instance method. */
    private static final MethodSpec ALIGN = new MethodSpec("align2grain(int, int)", Kind.ALIGN, "int", 3, 3, 10, 11,
            List.of(
                    new Instruction(0, "iload_1", "push local 1 (i)        depth 0 → 1"),
                    new Instruction(1, "iload_2", "push local 2 (grain)    depth 1 → 2"),
                    new Instruction(2, "iadd", "pop 2, push i+grain     depth 2 → 1"),
                    new Instruction(3, "iconst_1", "push constant 1         depth 1 → 2"),
                    new Instruction(4, "isub", "pop 2, push i+grain-1   depth 2 → 1"),
                    new Instruction(5, "iload_2", "push local 2 again      depth 1 → 2"),
                    new Instruction(6, "iconst_1", "push constant 1         depth 2 → 3"),
                    new Instruction(7, "isub", "pop 2, push grain-1     depth 3 → 2"),
                    new Instruction(8, "iconst_m1", "push constant -1        depth 2 → 3"),
                    new Instruction(9, "ixor", "~x is -1 ^ x            depth 3 → 2"),
                    new Instruction(10, "iand", "pop 2, push result      depth 2 → 1"),
                    new Instruction(11, "ireturn", "return result           depth 1 → 0")));

    private final List<Snapshot> snapshots = new ArrayList<>();
    private int step;

    private JComboBox<MethodSpec> methodBox;
    private JLabel arg1Label;
    private JLabel arg2Label;
    private JSpinner aSpinner;
    private JSpinner bSpinner;
    private JButton stepButton;
    private JButton runButton;
    private JButton resetButton;
    private JLabel progressLabel;
    private JTextArea descriptionArea;
    private SourceView sourceView;
    private BytecodeView bytecodeView;
    private FrameView frameView;
    private Timer timer;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--render".equals(args[0])) {
            renderPreviews(args[1]);
            return;
        }
        SwingUtilities.invokeLater(ArithmeticDemo::new);
    }

    /**
     * Headless helper: writes one PNG per state of all three methods, which is
     * handy for the article figures and for checking the layout without a display.
     */
    private static void renderPreviews(String dir) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File out = new File(dir);
        out.mkdirs();
        for (MethodSpec spec : new MethodSpec[]{INT_ADD, LONG_ADD, ALIGN}) {
            boolean align = spec.kind() == Kind.ALIGN;
            List<Snapshot> snaps = buildSnapshots(spec, align ? 5 : 1, align ? 4 : 2);
            String prefix = align ? "align" : spec.type();
            for (int i = 0; i < snaps.size(); i++) {
                FrameView view = new FrameView();
                view.setSize(787, 366);
                view.setSnapshot(snaps.get(i), spec);
                int scale = 2;
                BufferedImage img = new BufferedImage(787 * scale, 366 * scale,
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.scale(scale, scale);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, 787, 366);
                view.paint(g);
                g.dispose();
                ImageIO.write(img, "png",
                        new File(out, prefix + "-step" + i + ".png"));
            }
        }
        System.out.println("rendered to " + out.getAbsolutePath());
    }

    private ArithmeticDemo() {
        createAndShowGui();
    }

    private void createAndShowGui() {
        JFrame frame = new JFrame("JVM arithmetic demo — the operand stack in three methods");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));
        control.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        methodBox = new JComboBox<>(new MethodSpec[]{INT_ADD, LONG_ADD, ALIGN});
        methodBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.label());
            label.setFont(MONO);
            label.setOpaque(true);
            label.setBackground(selected ? HIGHLIGHT_BG : Color.WHITE);
            return label;
        });

        aSpinner = new JSpinner(new SpinnerNumberModel(1, -1000, 1000, 1));
        bSpinner = new JSpinner(new SpinnerNumberModel(2, -1000, 1000, 1));
        for (JSpinner spinner : new JSpinner[]{aSpinner, bSpinner}) {
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "0"));
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(6);
        }

        JPanel argGroup = new JPanel();
        argGroup.setBorder(BorderFactory.createEtchedBorder());
        arg1Label = new JLabel("a =");
        arg2Label = new JLabel("b =");
        argGroup.add(new JLabel("method"));
        argGroup.add(methodBox);
        argGroup.add(arg1Label);
        argGroup.add(aSpinner);
        argGroup.add(arg2Label);
        argGroup.add(bSpinner);

        control.add(row("method", DEMO_COLOR, argGroup));

        stepButton = new JButton("Step ▶");
        stepButton.setFont(CODE_FONT);
        stepButton.setToolTipText("Execute the next bytecode instruction");

        runButton = new JButton("Run");
        runButton.setToolTipText("Execute the remaining instructions automatically");

        resetButton = new JButton("Reset");
        resetButton.setToolTipText("Back to the frame right after the call, with an empty stack");
        progressLabel = new JLabel();
        progressLabel.setFont(MONO);
        progressLabel.setForeground(MUTED);

        control.add(row("execution", DEMO_COLOR, stepButton, runButton, resetButton, progressLabel));

        sourceView = new SourceView();
        bytecodeView = new BytecodeView();

        JPanel leftColumn = new JPanel();
        leftColumn.setLayout(new BoxLayout(leftColumn, BoxLayout.Y_AXIS));
        leftColumn.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        leftColumn.add(sourceView);
        leftColumn.add(bytecodeView);

        frameView = new FrameView();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftColumn, frameView);
        split.setBorder(null);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.42);

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

        timer = new Timer(900, e -> doStep());
        stepButton.addActionListener(e -> doStep());
        runButton.addActionListener(e -> startRun());
        resetButton.addActionListener(e -> resetDemo());
        methodBox.addActionListener(e -> {
            MethodSpec spec = currentSpec();
            boolean align = spec.kind() == Kind.ALIGN;
            if (align) {
                // the 3.3 example reads better with a value that actually moves: 5 rounds up to 8
                aSpinner.setValue(5);
                bSpinner.setValue(4);
            }
            arg1Label.setText(align ? "i =" : "a =");
            arg2Label.setText(align ? "grain =" : "b =");
            sourceView.setMethod(spec);
            bytecodeView.setMethod(spec);
            resetDemo();
        });
        aSpinner.addChangeListener(e -> resetDemo());
        bSpinner.addChangeListener(e -> resetDemo());

        sourceView.setMethod(INT_ADD);
        bytecodeView.setMethod(INT_ADD);
        resetDemo();

        frame.setSize(1320, 800);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        split.setDividerLocation(0.4);
    }

    private MethodSpec currentSpec() {
        return (MethodSpec) methodBox.getSelectedItem();
    }

    // ---- execution ----

    private void doStep() {
        if (step >= snapshots.size() - 1) {
            return;
        }
        step++;
        refreshView();
        if (step == snapshots.size() - 1) {
            timer.stop();
            stepButton.setEnabled(false);
            runButton.setEnabled(false);
        }
    }

    private void startRun() {
        if (step >= snapshots.size() - 1) {
            return;
        }
        stepButton.setEnabled(false);
        runButton.setEnabled(false);
        timer.start();
    }

    private void resetDemo() {
        timer.stop();
        MethodSpec spec = currentSpec();
        int a = ((Number) aSpinner.getValue()).intValue();
        int b = ((Number) bSpinner.getValue()).intValue();
        snapshots.clear();
        snapshots.addAll(buildSnapshots(spec, a, b));
        step = 0;
        stepButton.setEnabled(true);
        runButton.setEnabled(true);
        refreshView();
    }

    private void refreshView() {
        Snapshot snapshot = snapshots.get(step);
        MethodSpec spec = currentSpec();
        sourceView.setActiveLine(step == 0 ? spec.callLine() : spec.bodyLine());
        bytecodeView.setActiveInstruction(snapshot.active());
        frameView.setSnapshot(snapshot, spec);
        progressLabel.setText("   step " + step + " / " + (snapshots.size() - 1));
        descriptionArea.setText(snapshot.description());
        descriptionArea.setCaretPosition(0);
    }

    private static List<Snapshot> buildSnapshots(MethodSpec spec, int a, int b) {
        if (spec.kind() == Kind.ALIGN) {
            return buildAlignSnapshots(a, b);
        }
        boolean wide = spec.wide();
        int sum = a + b;
        List<LocalSlot> locals = List.of(
                new LocalSlot("this", "this", "ref", 1),
                new LocalSlot("a", String.valueOf(a), spec.type(), wide ? 2 : 1),
                new LocalSlot("b", String.valueOf(b), spec.type(), wide ? 2 : 1));

        int size = wide ? 2 : 1;
        String aText = String.valueOf(a);
        String bText = String.valueOf(b);
        String sumText = String.valueOf(sum);

        String load = wide ? "lload" : "iload";
        String add = wide ? "ladd" : "iadd";
        String ret = wide ? "lreturn" : "ireturn";
        String unitPhrase = wide ? "two units (a long takes two)" : "one unit (an int takes one)";

        List<Snapshot> list = new ArrayList<>();
        list.add(new Snapshot(-1, locals, List.of(), true, null, -1, -1, null,
                "Frame pushed for add(" + spec.type() + " a, " + spec.type() + " b): local slots hold this, a and b; "
                        + "the operand stack is empty. max_stack = " + spec.maxStack()
                        + ", max_locals = " + spec.maxLocals() + "."));
        list.add(new Snapshot(0, locals, List.of(new StackValue(aText, size, true)), true, null, 1, 1, null,
                "0: " + load + "_1 — copy a (" + a + ") onto the operand stack. It takes " + unitPhrase
                        + ", so depth 0 → " + (wide ? 2 : 1) + "."));
        list.add(new Snapshot(1, locals, List.of(new StackValue(aText, size, false),
                        new StackValue(bText, size, true)), true, null, 2, 2, null,
                "1: " + load + "_" + (wide ? 3 : 2) + " — copy b (" + b + ") onto the stack. Depth "
                        + (wide ? 2 : 1) + " → " + (wide ? 4 : 2) + ", which is max_stack."));
        list.add(new Snapshot(2, locals, List.of(new StackValue(sumText, size, true)), true, null, -1, -1,
                "a + b = " + aText + " + " + bText + " = " + sumText,
                "2: " + add + " — pop both operands, add them, and push " + sum + ". Depth "
                        + (wide ? 4 : 2) + " → " + (wide ? 2 : 1) + "."));
        list.add(new Snapshot(3, locals, List.of(), false, String.valueOf(sum), -1, -1, null,
                "3: " + ret + " — pop " + sum + " and return it. The frame is destroyed, so its locals "
                        + "and operand stack disappear."));
        return list;
    }

    /**
     * The 3.3 example, simulated instruction by instruction. Two sub-expressions
     * take turns: (i + grain - 1) stays at the bottom while (grain - 1) is built
     * on top of it, which is what pushes the depth to 3.
     */
    private static List<Snapshot> buildAlignSnapshots(int i, int grain) {
        List<LocalSlot> locals = List.of(
                new LocalSlot("this", "this", "ref", 1),
                new LocalSlot("i", String.valueOf(i), "int", 1),
                new LocalSlot("grain", String.valueOf(grain), "int", 1));

        int total = i + grain;
        int high = total - 1;
        int low = grain - 1;
        int flipped = ~low;
        int result = high & flipped;

        List<Snapshot> list = new ArrayList<>();
        list.add(new Snapshot(-1, locals, List.of(), true, null, -1, -1, null,
                "Frame pushed for align2grain(" + i + ", " + grain + "): locals hold this, i and grain; the stack is "
                        + "empty. max_stack = 3, max_locals = 3."));
        list.add(new Snapshot(0, locals, stackOf(String.valueOf(i)), true, null, 1, 1, null,
                "0: iload_1 — push local 1 (i = " + i + "). An int is one unit deep: depth 0 → 1."));
        list.add(new Snapshot(1, locals, stackOf(String.valueOf(i), String.valueOf(grain)), true, null, 2, 2, null,
                "1: iload_2 — push local 2 (grain = " + grain + "). depth 1 → 2."));
        list.add(new Snapshot(2, locals, stackOf(String.valueOf(total)), true, null, -1, -1,
                "i + grain = " + i + " + " + grain + " = " + total,
                "2: iadd — pop " + i + " and " + grain + ", push " + total + ". depth 2 → 1."));
        list.add(new Snapshot(3, locals, stackOf(String.valueOf(total), "1"), true, null, -1, -1, null,
                "3: iconst_1 — push the constant 1. Nothing comes from the local table here: "
                        + "the value is part of the instruction itself. depth 1 → 2."));
        list.add(new Snapshot(4, locals, stackOf(String.valueOf(high)), true, null, -1, -1,
                total + " - 1 = " + high,
                "4: isub — pop " + total + " and 1, push " + high + ". The partial sum stays on the stack."));
        list.add(new Snapshot(5, locals, stackOf(String.valueOf(high), String.valueOf(grain)), true, null, 2, 2, null,
                "5: iload_2 — push grain (" + grain + ") again; the " + high
                        + " underneath is untouched. depth 1 → 2."));
        list.add(new Snapshot(6, locals, stackOf(String.valueOf(high), String.valueOf(grain), "1"), true, null, -1, -1, null,
                "6: iconst_1 — push 1. depth 2 → 3, which is max_stack."));
        list.add(new Snapshot(7, locals, stackOf(String.valueOf(high), String.valueOf(low)), true, null, -1, -1,
                grain + " - 1 = " + low,
                "7: isub — pop " + grain + " and 1, push " + low + ". depth 3 → 2."));
        list.add(new Snapshot(8, locals, stackOf(String.valueOf(high), String.valueOf(low), "-1"), true, null, -1, -1, null,
                "8: iconst_m1 — push -1. It is a shorthand for iconst with the value -1. depth 2 → 3."));
        list.add(new Snapshot(9, locals, stackOf(String.valueOf(high), String.valueOf(flipped)), true, null, -1, -1,
                low + " ^ -1 = " + flipped,
                "9: ixor — " + low + " ^ -1 = " + flipped + ", which is the same as ~" + low
                        + ". depth 3 → 2."));
        list.add(new Snapshot(10, locals, stackOf(String.valueOf(result)), true, null, -1, -1,
                high + " & " + flipped + " = " + result,
                "10: iand — " + high + " & " + flipped + " = " + result + ". depth 2 → 1."));
        list.add(new Snapshot(11, locals, List.of(), false, String.valueOf(result), -1, -1, null,
                "11: ireturn — pop " + result + " and return it. The frame is destroyed."));
        return list;
    }

    /** A stack of int values, one unit each; the last one is the value just pushed. */
    private static List<StackValue> stackOf(String... values) {
        List<StackValue> stack = new ArrayList<>();
        for (int k = 0; k < values.length; k++) {
            stack.add(new StackValue(values[k], 1, k == values.length - 1));
        }
        return stack;
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

    private static final class SourceView extends JPanel {
        private static final int FIRST_LINE = 9;
        private String[] lines = INT_ADD.sourceLines();
        private int activeLine = 10;

        SourceView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(480, 186));
            setMinimumSize(new Dimension(420, 186));
            setBorder(new TitledBorder(INT_ADD.sourceTitle()));
        }

        void setMethod(MethodSpec spec) {
            lines = spec.sourceLines();
            setBorder(new TitledBorder(spec.sourceTitle()));
            repaint();
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

            int lineHeight = 26;
            int top = 10;
            for (int i = 0; i < lines.length; i++) {
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
                g.drawString(lines[i], 44, y + 15);
            }
        }
    }

    // ---- bytecode panel ----

    private static final class BytecodeView extends JPanel {
        private static final int ROW_H = 32;
        private List<Instruction> bytecode = INT_ADD.bytecode();
        private String footer = INT_ADD.footer();
        private int active = -1;

        BytecodeView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(480, 190));
            setMinimumSize(new Dimension(420, 190));
            setBorder(new TitledBorder("bytecode — max_stack = 2, max_locals = 3"));
        }

        void setMethod(MethodSpec spec) {
            bytecode = spec.bytecode();
            footer = spec.footer();
            setBorder(new TitledBorder("bytecode — max_stack = " + spec.maxStack()
                    + ", max_locals = " + spec.maxLocals()));
            // 12 instructions need more rows than 4 do, so the panel grows with the listing
            int height = Math.max(190, 8 + bytecode.size() * ROW_H + 30);
            setPreferredSize(new Dimension(480, height));
            setMinimumSize(new Dimension(420, height));
            revalidate();
            repaint();
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
            for (int i = 0; i < bytecode.size(); i++) {
                Instruction ins = bytecode.get(i);
                int y = top + i * ROW_H;
                if (i == active) {
                    g.setColor(HIGHLIGHT_BG);
                    g.fillRect(8, y, getWidth() - 16, ROW_H - 2);
                    g.setColor(CODE_COLOR);
                    g.fillRect(8, y, 3, ROW_H - 2);
                }

                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                g.drawString(String.valueOf(ins.offset()), 26, y + 21);

                g.setFont(CODE_FONT);
                g.setColor(i == active ? CODE_COLOR : new Color(33, 33, 33));
                g.drawString(ins.mnemonic(), 46, y + 21);

                g.setFont(SANS_SMALL);
                g.setColor(MUTED);
                g.drawString(ins.effect(), 152, y + 21);
            }

            g.setFont(SANS_SMALL);
            g.setColor(new Color(150, 150, 150));
            g.drawString(footer, 46, top + bytecode.size() * ROW_H + 14);
        }
    }

    // ---- frame panel: local variable table + operand stack ----

    private static final class FrameView extends JPanel {
        /** One 32-bit slot; a long fills two of them side by side. */
        private static final int CELL_W = 88;
        private static final int CELL_H = 64;
        private static final int LVT_X = 30;
        private static final int CELL_TOP = 78;
        private static final int STACK_X = 520;
        private static final int STACK_W = 128;
        private static final int STACK_H = 33;
        private static final int STACK_GAP = 6;
        private static final int STACK_BOTTOM = 240;
        private static final int FRAME_BOTTOM = 306;

        private Snapshot snapshot;
        private MethodSpec spec;

        FrameView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(787, 366));
            setMinimumSize(new Dimension(700, 340));
        }

        void setSnapshot(Snapshot s, MethodSpec m) {
            snapshot = s;
            spec = m;
            repaint();
        }

        /** Top of the stack unit at {@code index}; 0 is the bottom of the stack. */
        private static int stackUnitTop(int index) {
            return STACK_BOTTOM - (index + 1) * STACK_H - index * STACK_GAP;
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
            g.drawString("operand stack", STACK_X, 66);

            paintLocals(g);
            paintStack(g);

            g.setFont(MONO);
            g.setColor(MUTED);
            g.drawString("depth = " + depth() + " / " + spec.maxStack(), STACK_X, STACK_BOTTOM + 22);

            if (snapshot.alive() && snapshot.pushFromLocal() >= 0) {
                paintPushArrow(g, snapshot.pushFromLocal(), snapshot.stack().size() - 1);
            } else if (snapshot.alive() && snapshot.caption() != null) {
                g.setFont(MONO);
                g.setColor(new Color(33, 33, 33));
                g.drawString(snapshot.caption(), LVT_X, STACK_BOTTOM + 22);
            }

            if (!snapshot.alive()) {
                paintReturnedChip(g);
            }

            paintLegend(g);
        }

        private void paintFrameBox(Graphics2D g) {
            boolean alive = snapshot.alive();
            g.setColor(alive ? new Color(207, 216, 220) : new Color(200, 200, 200));
            if (alive) {
                g.setStroke(new BasicStroke(1.2f));
            } else {
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{6f, 5f}, 0f));
            }
            g.drawRect(14, 10, getWidth() - 28, FRAME_BOTTOM - 10);
            g.setStroke(new BasicStroke(1f));

            g.setFont(MONO);
            g.setColor(MUTED);
            String signature = spec.signature();
            g.drawString((alive ? signature : signature + " — frame popped")
                    + "    max_stack = " + spec.maxStack()
                    + "    max_locals = " + spec.maxLocals(), 28, 36);
        }

        /** The local variable table, one cell per 32-bit slot, left to right. */
        private void paintLocals(Graphics2D g) {
            int unit = 0;
            int index = 0;
            for (LocalSlot slot : snapshot.locals()) {
                paintLocalCell(g, unit, slot, index == snapshot.readLocal());
                unit += slot.size();
                index++;
            }
        }

        private void paintLocalCell(Graphics2D g, int unit, LocalSlot slot, boolean read) {
            int x = LVT_X + unit * CELL_W;
            int w = slot.size() * CELL_W;
            int y = CELL_TOP;
            boolean alive = snapshot.alive();
            boolean self = "this".equals(slot.name());

            g.setColor(alive ? VALUE_FILL : new Color(245, 245, 245));
            g.fillRect(x, y, w, CELL_H);
            g.setColor(read ? READ_COLOR : GRID_LINE);
            g.setStroke(new BasicStroke(read ? 2.5f : 1f));
            g.drawRect(x, y, w, CELL_H);
            g.setStroke(new BasicStroke(1f));

            // variable name, top-left
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(alive ? new Color(55, 71, 79) : MUTED);
            g.drawString(slot.name(), x + 8, y + 17);

            // declared type, top-right
            g.setFont(MONO_SMALL);
            g.setColor(MUTED);
            String type = slot.type();
            g.drawString(type, x + w - 8 - g.getFontMetrics().stringWidth(type), y + 17);

            // a long covers two slots: show where the boundary is (before the value, so the digits stay readable)
            if (slot.size() > 1) {
                g.setColor(new Color(189, 189, 189));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{4f, 4f}, 0f));
                g.drawLine(x + CELL_W, y + 1, x + CELL_W, y + CELL_H - 1);
                g.setStroke(new BasicStroke(1f));
            }

            // value, centered over the whole value width
            Font valueFont = fittedFont(slot.value(), w - 12, self ? 13 : 20);
            FontMetrics fm = getFontMetrics(valueFont);
            g.setFont(valueFont);
            g.setColor(alive ? (self ? MUTED : new Color(33, 33, 33)) : MUTED);
            g.drawString(slot.value(), x + (w - fm.stringWidth(slot.value())) / 2,
                    y + 45 - (20 - valueFont.getSize()) / 2);

            if (self) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
                g.setColor(new Color(150, 150, 150));
                String note = "not read";
                g.drawString(note, x + (w - g.getFontMetrics().stringWidth(note)) / 2, y + 59);
            }

            // slot numbers below the cells, like tick labels
            g.setFont(MONO_SMALL);
            g.setColor(MUTED);
            for (int i = 0; i < slot.size(); i++) {
                String label = "slot " + (unit + i);
                g.drawString(label, x + i * CELL_W + (CELL_W - g.getFontMetrics().stringWidth(label)) / 2,
                        CELL_TOP + CELL_H + 16);
            }
        }

        private void paintStack(Graphics2D g) {
            // empty units first, so a long can be painted across two of them
            for (int u = 0; u < spec.maxStack(); u++) {
                g.setColor(EMPTY_FILL);
                g.fillRect(STACK_X, stackUnitTop(u), STACK_W, STACK_H);
                g.setColor(new Color(189, 189, 189));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{5f, 4f}, 0f));
                g.drawRect(STACK_X, stackUnitTop(u), STACK_W, STACK_H);

                g.setFont(SANS_SMALL);
                g.setColor(new Color(158, 158, 158));
                FontMetrics fm = g.getFontMetrics();
                g.drawString("empty", STACK_X + (STACK_W - fm.stringWidth("empty")) / 2,
                        stackUnitTop(u) + STACK_H / 2 + 4);
            }
            g.setStroke(new BasicStroke(1f));

            int unit = 0;
            for (StackValue value : snapshot.stack()) {
                int h = value.size() * STACK_H + (value.size() - 1) * STACK_GAP;
                int y = stackUnitTop(unit + value.size() - 1);

                g.setColor(value.fresh() ? FRESH_FILL : VALUE_FILL);
                g.fillRect(STACK_X, y, STACK_W, h);
                g.setColor(value.fresh() ? new Color(25, 103, 210) : GRID_LINE);
                g.setStroke(new BasicStroke(value.fresh() ? 2f : 1f));
                g.drawRect(STACK_X, y, STACK_W, h);
                g.setStroke(new BasicStroke(1f));

                Font valueFont = fittedFont(value.value(), STACK_W - 12, 20);
                FontMetrics fm = getFontMetrics(valueFont);
                g.setFont(valueFont);
                g.setColor(value.fresh() ? Color.WHITE : new Color(33, 33, 33));
                g.drawString(value.value(), STACK_X + (STACK_W - fm.stringWidth(value.value())) / 2,
                        y + h / 2 + valueFont.getSize() / 3);

                if (value.size() > 1) {
                    g.setColor(new Color(189, 189, 189));
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                            10f, new float[]{4f, 4f}, 0f));
                    g.drawLine(STACK_X + 1, y + STACK_H + STACK_GAP / 2,
                            STACK_X + STACK_W - 1, y + STACK_H + STACK_GAP / 2);
                    g.setStroke(new BasicStroke(1f));
                }
                unit += value.size();
            }

            // stack slot numbers, 0 at the bottom
            g.setFont(MONO_SMALL);
            g.setColor(MUTED);
            for (int u = 0; u < spec.maxStack(); u++) {
                g.drawString("slot " + u, STACK_X + STACK_W + 10, stackUnitTop(u) + STACK_H / 2 + 4);
            }
        }

        /** Dashed amber arrow: a local slot is copied to a fresh stack value. */
        private void paintPushArrow(Graphics2D g, int localIndex, int stackValueIndex) {
            LocalSlot slot = snapshot.locals().get(localIndex);
            int unit = 0;
            for (int i = 0; i < localIndex; i++) {
                unit += snapshot.locals().get(i).size();
            }
            int x1 = LVT_X + unit * CELL_W + slot.size() * CELL_W / 2;
            int y1 = CELL_TOP + CELL_H + 26;

            StackValue fresh = snapshot.stack().get(stackValueIndex);
            int freshUnit = 0;
            for (int i = 0; i < stackValueIndex; i++) {
                freshUnit += snapshot.stack().get(i).size();
            }
            int x2 = STACK_X - 7;
            int y2 = stackUnitTop(freshUnit + fresh.size() - 1)
                    + (fresh.size() * STACK_H + (fresh.size() - 1) * STACK_GAP) / 2;

            // elbow: run below the slot labels first, then turn towards the stack
            int bendX = x2 - 40;
            g.setColor(READ_COLOR);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                    10f, new float[]{6f, 4f}, 0f));
            g.drawLine(x1, y1, bendX, y1);
            g.drawLine(bendX, y1, x2, y2);

            g.setStroke(new BasicStroke(1f));
            g.fillOval(x1 - 3, y1 - 3, 6, 6);

            double angle = Math.atan2(y2 - y1, x2 - x1);
            double size = 8;
            Path2D head = new Path2D.Double();
            head.moveTo(x2, y2);
            head.lineTo(x2 - size * Math.cos(angle - 0.4), y2 - size * Math.sin(angle - 0.4));
            head.lineTo(x2 - size * Math.cos(angle + 0.4), y2 - size * Math.sin(angle + 0.4));
            head.closePath();
            g.fill(head);
        }

        private void paintReturnedChip(Graphics2D g) {
            String text = (spec.wide() ? "lreturn" : "ireturn") + ": returned "
                    + snapshot.returned() + " to Demo.main";
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text) + 28;
            int x = Math.min(STACK_X - 20, getWidth() - 28 - w);
            int y = 252;
            int h = 42;

            g.setColor(new Color(232, 245, 233));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(RETURN_COLOR);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(x, y, w, h, 14, 14);
            g.setStroke(new BasicStroke(1f));
            g.drawString(text, x + 14, y + 26);
        }

        private void paintLegend(Graphics2D g) {
            int y = FRAME_BOTTOM + 30;
            legendItem(g, 30, y, FRESH_FILL, null, "just pushed");
            legendItem(g, 190, y, VALUE_FILL, GRID_LINE, "existing value");
            legendItem(g, 410, y, Color.WHITE, READ_COLOR, "read by this instruction");
            legendItem(g, 630, y, EMPTY_FILL, new Color(189, 189, 189), "empty");
        }

        private void legendItem(Graphics2D g, int x, int y, Color fill, Color border, String text) {
            g.setColor(fill);
            g.fillRect(x, y - 12, 14, 14);
            g.setColor(border == null ? new Color(25, 103, 210) : border);
            g.setStroke(new BasicStroke(border != null && border.equals(READ_COLOR) ? 2f : 1f));
            g.drawRect(x, y - 12, 14, 14);
            g.setStroke(new BasicStroke(1f));
            g.setFont(SANS_SMALL);
            g.setColor(MUTED);
            g.drawString(text, x + 22, y);
        }

        /** Total depth of the operand stack: a long contributes two units, an int one. */
        private int depth() {
            int d = 0;
            for (StackValue v : snapshot.stack()) {
                d += v.size();
            }
            return d;
        }
    }
}
