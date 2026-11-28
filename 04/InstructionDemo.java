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
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;

/**
 * Step-by-step walkthrough of how JVM instructions are encoded as bytes
 * (JVMS 2.11): one opcode byte, optionally followed by operands that are
 * fetched and assembled at run time, including the big-endian multi-byte
 * operands of sipush.
 *
 * <pre>
 *   add(int, int)      : iload_1  iload_2  iadd  ireturn          4 bytes, no operands
 *   addTen(int)        : iload_1  bipush 10  iadd  ireturn        5 bytes, 1 signed operand byte
 *   addThousand(int)   : iload_1  sipush 1000  iadd  ireturn      6 bytes, 2 big-endian operand bytes
 *   inc(int)           : iinc 1, 1  return                           4 bytes, two operand bytes
 *   million()          : ldc #7  ireturn                            3 bytes, 1-byte pool index
 * </pre>
 *
 * The byte arrays and the constant-pool index are taken from real javap
 * output (JDK 21): class ShowBytes compiles to ldc #7 for 1000000.
 *
 * Run with:
 * javac InstructionDemo.java
 * java InstructionDemo
 *
 * Headless figure export for the article:
 * java InstructionDemo --render img
 */
public class InstructionDemo {

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
    private static final Font HEX_FONT = new Font(Font.MONOSPACED, Font.BOLD, 16);
    private static final Font SOURCE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font VAR_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    private static final Font VALUE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 15);
    private static final Font ITALIC_FONT = new Font(Font.SANS_SERIF, Font.ITALIC, 11);
    private static final Font CHIP_FONT = new Font(Font.MONOSPACED, Font.BOLD, 12);

    // ---- constant pool display ----

    /** One row of the constant pool view: a real entry, or an ellipsis for omitted entries. */
    private record CpRow(int index, String tag, String value, boolean filler) {
        static CpRow entry(int index, String tag, String value) {
            return new CpRow(index, tag, value, false);
        }

        static CpRow gap(int lo, int hi) {
            return new CpRow(lo, null, hi == lo ? "（略）" : " – #" + hi + "（其余 " + (hi - lo + 1) + " 项，略）", true);
        }
    }

    private enum Kind { LOAD1, LOAD2, ADD, RETURN, VOID_RETURN, PUSH_IMM, INC, LDC }

    /** One decoded instruction. */
    private record Insn(int offset, int length, Kind kind, String text, String effect,
                        String[] roles, String detail, int cpIndex, int imm, int localIndex, int localConst) {
    }

    /** One snapshot: everything the views need about one moment of execution. */
    private record Step(int active, int pc, int[] localVals, List<Integer> stack, boolean freshTop,
                        boolean alive, Integer returned, int readLocal, int writtenLocal, int cpIndex,
                        String description) {
    }

    private record LocVar(String name) {
    }

    private record Example(String label, String prefix, String signature, int bodyLine, byte[] code,
                           int maxStack, List<LocVar> locals, List<CpRow> cp, Map<Integer, Integer> cpValues,
                           List<Insn> insns, List<Step> steps, String footer) {
    }

    // ---- the five examples (bytes and indexes verified against javap -v, JDK 21) ----

    private static final String SOURCE_TITLE = "ShowBytes.java — class ShowBytes";

    private static final String[] SOURCE_LINES = {
            "class ShowBytes {",
            "    int add(int a, int b) { return a + b; }",
            "    int addTen(int i) { return i + 10; }",
            "    int addThousand(int i) { return i + 1000; }",
            "    void inc(int i) { i++; }",
            "    int million() { return 1000000; }",
            "}"
    };

    private static final List<LocVar> LOCALS_AB = List.of(new LocVar("this"), new LocVar("a"), new LocVar("b"));
    private static final List<LocVar> LOCALS_I = List.of(new LocVar("this"), new LocVar("i"));
    private static final List<LocVar> LOCALS_THIS = List.of(new LocVar("this"));

    private static final List<CpRow> CP_NONE = List.of();
    private static final List<CpRow> CP_SMALL = List.of(
            CpRow.gap(1, 6),
            CpRow.entry(7, "Integer", "1000000"),
            new CpRow(8, null, " – 类、方法名等条目（略）", true));

    private static final Example ADD = build("add(int, int)", "add", "add(int a, int b)", 2,
            new byte[]{0x1b, 0x1c, 0x60, (byte) 0xac}, 2,
            LOCALS_AB, new int[]{0, 1, 2}, CP_NONE, Map.of(),
            "4 instructions, 4 bytes — every instruction here is one opcode byte");

    private static final Example ADD_TEN = build("addTen(int)", "addten", "addTen(int i)", 3,
            new byte[]{0x1b, 0x10, 0x0a, 0x60, (byte) 0xac}, 2,
            LOCALS_I, new int[]{0, 1}, CP_NONE, Map.of(),
            "4 instructions, 5 bytes — bipush carries 1 operand byte inside the instruction");

    private static final Example ADD_THOUSAND = build("addThousand(int)", "addthousand", "addThousand(int i)", 4,
            new byte[]{0x1b, 0x11, 0x03, (byte) 0xe8, 0x60, (byte) 0xac}, 2,
            LOCALS_I, new int[]{0, 1}, CP_NONE, Map.of(),
            "4 instructions, 6 bytes — sipush carries 2 operand bytes, assembled big-endian");

    private static final Example INC = build("inc(int)", "inc", "inc(int i)", 5,
            new byte[]{(byte) 0x84, 0x01, 0x01, (byte) 0xb1}, 0,
            LOCALS_I, new int[]{0, 1}, CP_NONE, Map.of(),
            "2 instructions, 4 bytes — iinc carries 2 operand bytes; the stack is never touched");

    private static final Example MILLION = build("million()", "million", "million()", 6,
            new byte[]{0x12, 0x07, (byte) 0xac}, 1,
            LOCALS_THIS, new int[]{0}, CP_SMALL, Map.of(7, 1000000),
            "2 instructions, 3 bytes — ldc carries a 1-byte constant-pool index");

    private static final Example[] EXAMPLES = {ADD, ADD_TEN, ADD_THOUSAND, INC, MILLION};

    // ---- decoding: the interpreter loop from JVMS 2.11, in miniature ----

    private static Example build(String label, String prefix, String signature, int bodyLine,
                                 byte[] code, int maxStack, List<LocVar> locals, int[] initVals,
                                 List<CpRow> cp, Map<Integer, Integer> cpValues, String footer) {
        List<Insn> insns = new ArrayList<>();
        int pc = 0;
        while (pc < code.length) {
            int op = code[pc] & 0xff;
            Kind kind;
            String mnemonic;
            int len;
            String[] roles = new String[0];
            String operandText = "";
            String detail;
            int cpIndex = -1;
            int imm = 0;
            int localIndex = 0;
            int localConst = 0;
            switch (op) {
                case 0x1b -> {
                    kind = Kind.LOAD1;
                    mnemonic = "iload_1";
                    len = 1;
                    detail = "one byte: opcode only, no operands";
                }
                case 0x1c -> {
                    kind = Kind.LOAD2;
                    mnemonic = "iload_2";
                    len = 1;
                    detail = "one byte: opcode only, no operands";
                }
                case 0x60 -> {
                    kind = Kind.ADD;
                    mnemonic = "iadd";
                    len = 1;
                    detail = "one byte: opcode only, no operands";
                }
                case 0xac -> {
                    kind = Kind.RETURN;
                    mnemonic = "ireturn";
                    len = 1;
                    detail = "one byte: opcode only, no operands";
                }
                case 0xb1 -> {
                    kind = Kind.VOID_RETURN;
                    mnemonic = "return";
                    len = 1;
                    detail = "one byte: opcode only, no operands";
                }
                case 0x10 -> {
                    kind = Kind.PUSH_IMM;
                    mnemonic = "bipush";
                    len = 2;
                    imm = (int) (byte) code[pc + 1];
                    operandText = String.valueOf(imm);
                    roles = new String[]{"const"};
                    detail = "operand 0x" + hex(code[pc + 1]) + " = " + imm + " (signed byte)";
                }
                case 0x11 -> {
                    kind = Kind.PUSH_IMM;
                    mnemonic = "sipush";
                    len = 3;
                    int b1 = code[pc + 1] & 0xff;
                    int b2 = code[pc + 2] & 0xff;
                    imm = (short) ((b1 << 8) | b2);
                    operandText = String.valueOf(imm);
                    roles = new String[]{"hi", "lo"};
                    detail = "(0x" + hex(code[pc + 1]) + " << 8) | 0x" + hex(code[pc + 2]) + " = " + imm + " (big-endian)";
                }
                case 0x84 -> {
                    kind = Kind.INC;
                    mnemonic = "iinc";
                    len = 3;
                    localIndex = code[pc + 1] & 0xff;
                    localConst = (int) (byte) code[pc + 2];
                    operandText = localIndex + ", " + localConst;
                    roles = new String[]{"index", "const"};
                    detail = "operands: local slot " + localIndex + ", increment " + localConst;
                }
                case 0x12 -> {
                    kind = Kind.LDC;
                    mnemonic = "ldc";
                    len = 2;
                    cpIndex = code[pc + 1] & 0xff;
                    operandText = "#" + cpIndex;
                    roles = new String[]{"index"};
                    detail = "operand 0x" + hex(code[pc + 1]) + " → constant pool #" + cpIndex;
                }
                default -> throw new IllegalStateException("opcode not in demo subset: 0x" + hex(code[pc]));
            }
            insns.add(new Insn(pc, len, kind, operandText.isEmpty() ? mnemonic : mnemonic + " " + operandText,
                    effectOf(kind, imm, localIndex, localConst, cpIndex), roles, detail, cpIndex, imm, localIndex, localConst));
            pc += len;
        }

        List<Step> steps = simulate(code, initVals, locals, insns, cpValues, signature);
        return new Example(label, prefix, signature, bodyLine, code, maxStack, locals, cp, cpValues,
                List.copyOf(insns), List.copyOf(steps), footer);
    }

    /** Rebuild an example's execution snapshots with new initial local values. */
    private static Example withValues(Example ex, int[] initVals) {
        List<Step> steps = simulate(ex.code(), initVals, ex.locals(), ex.insns(), ex.cpValues(), ex.signature());
        return new Example(ex.label(), ex.prefix(), ex.signature(), ex.bodyLine(), ex.code(),
                ex.maxStack(), ex.locals(), ex.cp(), ex.cpValues(), ex.insns(), List.copyOf(steps), ex.footer());
    }

    /** Simulate one step per instruction, using the given initial local values. */
    private static List<Step> simulate(byte[] code, int[] initVals, List<LocVar> locals,
                                       List<Insn> insns, Map<Integer, Integer> cpValues, String signature) {
        int[] vals = initVals.clone();
        List<Integer> stack = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(-1, 0, vals.clone(), List.of(), false, true, null, -1, -1, -1,
                "Method " + signature + " — the code attribute holds " + code.length + " bytes. The interpreter loop "
                        + "(JVMS 2.11): fetch the opcode at pc, fetch its operands if any, execute, repeat."));
        for (int i = 0; i < insns.size(); i++) {
            Insn in = insns.get(i);
            int readLocal = -1;
            int writtenLocal = -1;
            boolean freshTop = false;
            boolean alive = true;
            Integer returned = null;
            String desc;
            switch (in.kind()) {
                case LOAD1 -> {
                    stack.add(vals[1]);
                    readLocal = 1;
                    freshTop = true;
                    desc = in.offset() + ": iload_1 — the opcode 0x1B is the whole instruction: one byte, no operands. "
                            + "It copies local slot 1 (" + locals.get(1).name() + " = " + vals[1] + ") onto the stack.";
                }
                case LOAD2 -> {
                    stack.add(vals[2]);
                    readLocal = 2;
                    freshTop = true;
                    desc = in.offset() + ": iload_2 — again one byte, no operands. Copies local slot 2 ("
                            + locals.get(2).name() + " = " + vals[2] + ") onto the stack.";
                }
                case PUSH_IMM -> {
                    stack.add(in.imm());
                    freshTop = true;
                    desc = in.offset() + ": " + in.text() + " — " + in.detail() + ". The value rides inside the "
                            + "instruction itself: no local read, no constant-pool lookup.";
                }
                case INC -> {
                    vals[in.localIndex()] += in.localConst();
                    writtenLocal = in.localIndex();
                    desc = in.offset() + ": iinc " + in.localIndex() + ", " + in.localConst() + " — two operand bytes: "
                            + "local slot " + in.localIndex() + " and the increment " + in.localConst() + ". Local "
                            + in.localIndex() + " goes " + (vals[in.localIndex()] - in.localConst()) + " → "
                            + vals[in.localIndex()] + " directly; the operand stack is never touched (max_stack = 0).";
                }
                case ADD -> {
                    int b = stack.remove(stack.size() - 1);
                    int a = stack.remove(stack.size() - 1);
                    stack.add(a + b);
                    freshTop = true;
                    desc = in.offset() + ": iadd — one byte again. Pop " + a + " and " + b + ", push " + (a + b) + ".";
                }
                case LDC -> {
                    int value = cpValues.get(in.cpIndex());
                    stack.add(value);
                    freshTop = true;
                    desc = in.offset() + ": " + in.text() + " — opcode 0x12 plus one operand byte 0x"
                            + hex(code[in.offset() + 1]) + ". The index points at pool entry #"
                            + in.cpIndex() + " (an Integer: " + value + "); the stack receives the value, never the index.";
                }
                case RETURN -> {
                    returned = stack.remove(stack.size() - 1);
                    alive = false;
                    desc = in.offset() + ": ireturn — pop " + returned + " and return it. pc = " + (in.offset() + in.length())
                            + " = code length: every byte has been fetched, there is nothing left to execute.";
                }
                case VOID_RETURN -> {
                    alive = false;
                    desc = in.offset() + ": return — one byte, and pc = " + (in.offset() + in.length())
                            + " = code length. The method ends with its stack never used.";
                }
                default -> throw new IllegalStateException();
            }
            steps.add(new Step(i, in.offset() + in.length(), vals.clone(), List.copyOf(stack), freshTop,
                    alive, returned, readLocal, writtenLocal, in.cpIndex(), desc));
        }
        return steps;
    }

    private static String effectOf(Kind kind, int imm, int localIndex, int localConst, int cpIndex) {
        return switch (kind) {
            case LOAD1 -> "push local 1";
            case LOAD2 -> "push local 2";
            case ADD -> "pop 2, push sum";
            case RETURN -> "return top of stack";
            case VOID_RETURN -> "return (void)";
            case PUSH_IMM -> "push " + imm;
            case INC -> "local " + localIndex + " += " + localConst + ", no stack";
            case LDC -> "push pool #" + cpIndex;
        };
    }

    private static String hex(byte b) {
        return String.format("%02X", b & 0xff);
    }

    /** Fill and outline one cell; restores the default stroke afterwards. */
    private static void box(Graphics2D g, int x, int y, int w, int h, Color fill, Color border, float stroke) {
        g.setColor(fill);
        g.fillRect(x, y, w, h);
        g.setColor(border);
        g.setStroke(new BasicStroke(stroke));
        g.drawRect(x, y, w, h);
        g.setStroke(new BasicStroke(1f));
    }

    /** Section heading inside a panel ("locals", "operand stack", ...). */
    private static void label(Graphics2D g, String text, int x, int y) {
        g.setFont(SANS_SMALL);
        g.setColor(MUTED);
        g.drawString(text, x, y);
    }

    // ---- application state ----

    private Example example = ADD;
    private int step;
    private JFrame frame;
    private JComboBox<Example> methodBox;
    private JButton stepButton;
    private JButton runButton;
    private JButton resetButton;
    private JLabel progressLabel;
    private JTextArea descriptionArea;
    private SourceView sourceView;
    private InsnView insnView;
    private ByteStreamView byteView;
    private RuntimeView runtimeView;
    private Timer timer;

    /** Current initial local values per method (slot 0 = this, followed by real parameters). */
    private final Map<String, int[]> paramValues = new HashMap<>();
    private JPanel paramPanel;
    private JTextField[] paramFields;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--render".equals(args[0])) {
            renderPreviews(args[1]);
            return;
        }
        SwingUtilities.invokeLater(InstructionDemo::new);
    }

    /**
     * Headless helper: writes one PNG per state (byte stream + runtime views),
     * handy for the article figures and for checking the layout without a display.
     */
    private static void renderPreviews(String dir) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File out = new File(dir);
        out.mkdirs();
        for (Example ex : EXAMPLES) {
            for (int i = 0; i < ex.steps().size(); i++) {
                ByteStreamView bv = new ByteStreamView();
                bv.setSize(560, 240);
                bv.setExample(ex);
                bv.setStep(i);
                writePng(bv, 560, 240, new File(out, ex.prefix() + "-step" + i + "-bytes.png"));

                RuntimeView rv = new RuntimeView();
                rv.setSize(500, 620);
                rv.setExample(ex);
                rv.setStep(i);
                writePng(rv, 500, 620, new File(out, ex.prefix() + "-step" + i + "-runtime.png"));
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

    private InstructionDemo() {
        for (Example ex : EXAMPLES) {
            int[] vals = new int[ex.locals().size()];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = ex.steps().get(0).localVals()[i];
            }
            paramValues.put(ex.prefix(), vals);
        }
        createAndShowGui();
    }

    private void createAndShowGui() {
        frame = new JFrame("JVM instruction bytes demo — opcode, operands, constant pool");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));
        control.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel methodRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        methodRow.setAlignmentX(0f);

        methodBox = new JComboBox<>(EXAMPLES);
        methodBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.label());
            label.setFont(MONO);
            label.setOpaque(true);
            label.setBackground(selected ? VALUE_FILL : Color.WHITE);
            return label;
        });

        stepButton = new JButton("Step ▶");
        stepButton.setFont(CODE_FONT);
        stepButton.setToolTipText("Fetch and execute the next instruction");
        runButton = new JButton("Run");
        runButton.setToolTipText("Execute the remaining instructions automatically");
        resetButton = new JButton("Reset");
        resetButton.setToolTipText("Back to pc = 0 with an empty stack");
        progressLabel = new JLabel();
        progressLabel.setFont(MONO);
        progressLabel.setForeground(MUTED);

        methodRow.add(new JLabel("method"));
        methodRow.add(methodBox);
        methodRow.add(stepButton);
        methodRow.add(runButton);
        methodRow.add(resetButton);
        methodRow.add(progressLabel);
        control.add(methodRow);

        paramPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        paramPanel.setAlignmentX(0f);
        control.add(paramPanel);

        sourceView = new SourceView();
        insnView = new InsnView();
        JPanel leftColumn = new JPanel();
        leftColumn.setLayout(new BoxLayout(leftColumn, BoxLayout.Y_AXIS));
        leftColumn.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        leftColumn.add(sourceView);
        leftColumn.add(insnView);

        byteView = new ByteStreamView();
        runtimeView = new RuntimeView();
        JPanel rightColumn = new JPanel();
        rightColumn.setLayout(new BoxLayout(rightColumn, BoxLayout.Y_AXIS));
        rightColumn.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        rightColumn.add(byteView);
        rightColumn.add(runtimeView);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftColumn, rightColumn);
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

        timer = new Timer(1100, e -> doStep());
        stepButton.addActionListener(e -> doStep());
        runButton.addActionListener(e -> startRun());
        resetButton.addActionListener(e -> resetDemo());
        methodBox.addActionListener(e -> applyExample((Example) methodBox.getSelectedItem()));

        applyExample(example);

        frame.setSize(1150, 950);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        split.setDividerLocation(0.42);
    }

    private void applyExample(Example ex) {
        int[] vals = paramValues.get(ex.prefix());
        example = vals == null ? ex : withValues(ex, vals);
        insnView.setExample(example);
        byteView.setExample(example);
        runtimeView.setExample(example);
        updateParamPanel();
        resetDemo();
    }

    /** Build the parameter input row for the currently selected method. */
    private void updateParamPanel() {
        paramPanel.removeAll();
        paramFields = new JTextField[0];
        int count = example.locals().size() - 1; // slot 0 is `this`
        if (count > 0) {
            paramPanel.add(new JLabel("parameters:"));
            paramFields = new JTextField[count];
            int[] vals = paramValues.get(example.prefix());
            for (int i = 0; i < count; i++) {
                String name = example.locals().get(i + 1).name();
                paramPanel.add(new JLabel(name + " ="));
                paramFields[i] = new JTextField(String.valueOf(vals[i + 1]), 6);
                paramFields[i].setFont(MONO);
                paramFields[i].setToolTipText("Enter an int value for parameter " + name);
                paramFields[i].addActionListener(e -> applyParameterValues());
                paramPanel.add(paramFields[i]);
            }
            JButton applyButton = new JButton("Apply");
            applyButton.setToolTipText("Rebuild the demo with these parameter values");
            applyButton.addActionListener(e -> applyParameterValues());
            paramPanel.add(applyButton);
            paramPanel.add(new JLabel("  (or press Enter in a field)"));
        } else {
            paramPanel.add(new JLabel("parameters: none (this method takes no arguments)"));
        }
        paramPanel.revalidate();
        paramPanel.repaint();
    }

    /** Read the text fields, rebuild the example, and refresh all views. */
    private void applyParameterValues() {
        int[] vals = paramValues.get(example.prefix()).clone();
        for (int i = 0; i < paramFields.length; i++) {
            String text = paramFields[i].getText().trim();
            try {
                vals[i + 1] = Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame,
                        "Invalid integer for parameter " + example.locals().get(i + 1).name() + ": " + text,
                        "Invalid parameter", JOptionPane.ERROR_MESSAGE);
                paramFields[i].setText(String.valueOf(paramValues.get(example.prefix())[i + 1]));
                return;
            }
        }
        paramValues.put(example.prefix(), vals);
        example = withValues(example, vals);
        byteView.setExample(example);
        runtimeView.setExample(example);
        resetDemo();
    }

    // ---- execution ----

    private void doStep() {
        if (step >= example.steps().size() - 1) {
            return;
        }
        step++;
        refreshView();
        if (step == example.steps().size() - 1) {
            timer.stop();
            stepButton.setEnabled(false);
            runButton.setEnabled(false);
        }
    }

    private void startRun() {
        if (step >= example.steps().size() - 1) {
            return;
        }
        stepButton.setEnabled(false);
        runButton.setEnabled(false);
        timer.start();
    }

    private void resetDemo() {
        timer.stop();
        step = 0;
        stepButton.setEnabled(true);
        runButton.setEnabled(true);
        refreshView();
    }

    private void refreshView() {
        Step snapshot = example.steps().get(step);
        sourceView.setActiveLine(example.bodyLine());
        insnView.setActive(step == 0 ? -1 : snapshot.active());
        byteView.setStep(step);
        runtimeView.setStep(step);
        progressLabel.setText("   step " + step + " / " + (example.steps().size() - 1)
                + "    pc = " + snapshot.pc() + " / " + example.code().length);
        descriptionArea.setText(snapshot.description());
        descriptionArea.setCaretPosition(0);
    }

    // ---- source panel ----

    private static final class SourceView extends JPanel {
        private int activeLine = 2;

        SourceView() {
            setBackground(Color.WHITE);
            int h = Math.max(160, 26 * SOURCE_LINES.length + 44);
            setPreferredSize(new Dimension(430, h));
            setMinimumSize(new Dimension(380, h));
            setBorder(new TitledBorder(SOURCE_TITLE));
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
            for (int i = 0; i < SOURCE_LINES.length; i++) {
                int lineNo = 1 + i;
                int y = top + i * lineHeight;
                if (lineNo == activeLine) {
                    g.setColor(VALUE_FILL);
                    g.fillRect(8, y - 3, getWidth() - 16, lineHeight);
                    g.setColor(CODE_COLOR);
                    g.fillRect(8, y - 3, 3, lineHeight);
                }
                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                String num = String.valueOf(lineNo);
                g.drawString(num, 26 - g.getFontMetrics().stringWidth(num), y + 15);
                g.setFont(SOURCE_FONT);
                g.setColor(INK);
                g.drawString(SOURCE_LINES[i], 44, y + 15);
            }
        }
    }

    // ---- decoded instructions panel ----

    private static final class InsnView extends JPanel {
        private static final int ROW_H = 30;
        private List<Insn> insns = ADD.insns();
        private String footer = ADD.footer();
        private int active = -1;

        InsnView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(430, 190));
            setMinimumSize(new Dimension(380, 190));
            setBorder(new TitledBorder("decoded instructions"));
        }

        void setExample(Example ex) {
            insns = ex.insns();
            footer = ex.footer();
            int h = Math.max(120, 10 + insns.size() * ROW_H + 34);
            setPreferredSize(new Dimension(430, h));
            setMinimumSize(new Dimension(380, h));
            revalidate();
            repaint();
        }

        void setActive(int index) {
            active = index;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int top = 8;
            for (int i = 0; i < insns.size(); i++) {
                Insn in = insns.get(i);
                int y = top + i * ROW_H;
                if (i == active) {
                    g.setColor(VALUE_FILL);
                    g.fillRect(8, y, getWidth() - 16, ROW_H - 2);
                    g.setColor(CODE_COLOR);
                    g.fillRect(8, y, 3, ROW_H - 2);
                }
                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                g.drawString(String.valueOf(in.offset()), 26, y + 20);
                g.setFont(CODE_FONT);
                g.setColor(i == active ? CODE_COLOR : INK);
                g.drawString(in.text(), 48, y + 20);
                g.setFont(SANS_SMALL);
                g.setColor(MUTED);
                g.drawString(in.effect(), 190, y + 20);
            }
            g.setFont(SANS_SMALL);
            g.setColor(new Color(150, 150, 150));
            g.drawString(footer, 48, top + insns.size() * ROW_H + 14);
        }
    }

    // ---- the raw byte stream panel ----

    private static final class ByteStreamView extends JPanel {
        private static final int CELL_W = 54;
        private static final int CELL_H = 62;
        private static final int GAP = 6;
        private static final int LEFT = 20;
        private Example ex = ADD;
        private int stepIdx = 0;

        ByteStreamView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(560, 240));
            setMinimumSize(new Dimension(520, 240));
            setBorder(new TitledBorder("code bytes"));
        }

        void setExample(Example ex) {
            this.ex = ex;
            setStep(0);
        }

        void setStep(int idx) {
            this.stepIdx = idx;
            Step s = ex.steps().get(idx);
            setBorder(new TitledBorder("code bytes — " + ex.code().length + " bytes, pc = " + s.pc()));
            repaint();
        }

        private int cellX(int byteIndex) {
            return LEFT + byteIndex * (CELL_W + GAP);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Step step = ex.steps().get(stepIdx);
            int activeIdx = step.active();

            // pc marker
            int pcY = 26;
            g.setFont(MONO_SMALL);
            g.setColor(MUTED);
            if (step.alive()) {
                int x = cellX(step.pc()) + CELL_W / 2;
                g.drawString("pc", x - 24, pcY + 4);
                Path2D tri = new Path2D.Double();
                tri.moveTo(x - 5, pcY);
                tri.lineTo(x + 5, pcY);
                tri.lineTo(x, pcY + 8);
                tri.closePath();
                g.fill(tri);
            } else {
                g.drawString("pc = " + step.pc() + " (end of code)", LEFT, pcY + 4);
            }

            // per-instruction brackets + mnemonics
            int bracketY = pcY + 20;
            for (int i = 0; i < ex.insns().size(); i++) {
                Insn in = ex.insns().get(i);
                int x1 = cellX(in.offset());
                int x2 = cellX(in.offset() + in.length() - 1) + CELL_W;
                boolean done = i < activeIdx;
                boolean isActive = i == activeIdx;
                g.setColor(isActive ? CODE_COLOR : done ? GRID_LINE : new Color(210, 210, 210));
                g.setStroke(new BasicStroke(isActive ? 1.6f : 1f));
                g.drawLine(x1, bracketY + 12, x2, bracketY + 12);
                g.drawLine(x1, bracketY + 8, x1, bracketY + 12);
                g.drawLine(x2, bracketY + 8, x2, bracketY + 12);
                g.setStroke(new BasicStroke(1f));
                g.setFont(isActive ? CODE_FONT : MONO_SMALL);
                g.setColor(isActive ? CODE_COLOR : done ? new Color(120, 120, 120) : new Color(170, 170, 170));
                String text = in.text();
                int tx = x1 + (x2 - x1 - g.getFontMetrics().stringWidth(text)) / 2;
                g.drawString(text, tx, bracketY + 2);
            }

            // byte cells
            int cellY = bracketY + 22;
            for (int b = 0; b < ex.code().length; b++) {
                int x = cellX(b);
                String role = roleOf(b);
                boolean consumed = consumedBy(b, activeIdx);
                boolean activeOpcode = activeIdx >= 0 && ex.insns().get(activeIdx).offset() == b;
                boolean activeOperand = activeIdx >= 0 && !activeOpcode && consumedBy(b, activeIdx + 1);

                box(g, x, cellY, CELL_W, CELL_H,
                        activeOpcode ? FRESH_FILL : consumed ? VALUE_FILL : Color.WHITE,
                        activeOperand ? READ_COLOR : activeOpcode ? OPCODE_BORDER : GRID_LINE,
                        activeOperand ? 2.5f : activeOpcode ? 2f : 1f);

                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                g.drawString(String.valueOf(b), x + 5, cellY + 13);

                g.setFont(HEX_FONT);
                g.setColor(activeOpcode ? Color.WHITE : consumed ? new Color(90, 120, 170) : INK);
                String hexText = hex(ex.code()[b]);
                FontMetrics fm = g.getFontMetrics();
                g.drawString(hexText, x + (CELL_W - fm.stringWidth(hexText)) / 2, cellY + 38);

                g.setFont(SANS_SMALL);
                g.setColor(activeOperand ? new Color(200, 130, 0) : MUTED);
                g.drawString(role, x + (CELL_W - g.getFontMetrics().stringWidth(role)) / 2, cellY + 56);
            }

            // annotation for the active instruction
            int annoY = cellY + CELL_H + 24;
            g.setFont(MONO);
            if (activeIdx >= 0) {
                Insn in = ex.insns().get(activeIdx);
                g.setColor(INK);
                g.drawString(in.text() + " — " + in.detail(), LEFT, annoY);
            } else {
                g.setColor(INK);
                g.drawString("interpreter loop: fetch the opcode at pc, fetch operands, execute, repeat", LEFT, annoY);
            }

            // legend
            int ly = annoY + 20;
            legend(g, LEFT, ly, VALUE_FILL, GRID_LINE, "fetched");
            legend(g, LEFT + 110, ly, FRESH_FILL, OPCODE_BORDER, "opcode of this step");
            legend(g, LEFT + 300, ly, Color.WHITE, READ_COLOR, "operands of this step");
        }

        private String roleOf(int b) {
            for (Insn in : ex.insns()) {
                if (b >= in.offset() && b < in.offset() + in.length()) {
                    if (b == in.offset()) {
                        return "opcode";
                    }
                    return in.roles()[b - in.offset() - 1];
                }
            }
            return "";
        }

        /** True if byte b belongs to instruction {@code insnIdx}. */
        private boolean consumedBy(int b, int insnIdx) {
            if (insnIdx <= 0 || insnIdx > ex.insns().size()) {
                return false;
            }
            Insn in = ex.insns().get(insnIdx - 1);
            return b >= in.offset() && b < in.offset() + in.length();
        }

        private void legend(Graphics2D g, int x, int y, Color fill, Color border, String text) {
            g.setColor(fill);
            g.fillRect(x, y - 12, 14, 14);
            g.setColor(border);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(x, y - 12, 14, 14);
            g.setStroke(new BasicStroke(1f));
            g.setFont(SANS_SMALL);
            g.setColor(MUTED);
            g.drawString(text, x + 22, y);
        }
    }

    // ---- runtime panel: locals + operand stack + constant pool ----

    private static final class RuntimeView extends JPanel {
        private static final int LOCAL_W = 78;
        private static final int LOCAL_H = 46;
        private static final int STACK_W = 120;
        private static final int STACK_H = 36;
        private static final int STACK_GAP = 5;

        private Example ex = ADD;
        private int stepIdx = 0;

        RuntimeView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(560, 620));
            setMinimumSize(new Dimension(520, 620));
            setBorder(new TitledBorder("frame & constant pool"));
        }

        void setExample(Example ex) {
            this.ex = ex;
            setStep(0);
        }

        void setStep(int idx) {
            this.stepIdx = idx;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Step step = ex.steps().get(stepIdx);
            int w = getWidth();

            // locals
            label(g, "locals", 20, 28);
            int lx = 20;
            for (int i = 0; i < ex.locals().size(); i++) {
                LocVar var = ex.locals().get(i);
                boolean read = i == step.readLocal();
                boolean written = i == step.writtenLocal();
                int x = lx + i * (LOCAL_W + 8);
                int y = 36;
                box(g, x, y, LOCAL_W, LOCAL_H,
                        written ? FRESH_FILL : read ? READ_FILL : VALUE_FILL,
                        written ? OPCODE_BORDER : read ? READ_COLOR : GRID_LINE,
                        read || written ? 2.2f : 1f);

                g.setFont(VAR_FONT);
                g.setColor(new Color(55, 71, 79));
                g.drawString(var.name(), x + 7, y + 15);
                g.setFont(VALUE_FONT);
                g.setColor(INK);
                String value = String.valueOf(step.localVals()[i]);
                g.drawString(value, x + 7, y + 36);
                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                g.drawString("slot " + i, x + 7, y + LOCAL_H + 13);
            }

            // operand stack
            int stackTop = 36 + LOCAL_H + 40;
            label(g, "operand stack", 20, stackTop - 8);
            int stackBottom = stackTop + ex.maxStack() * (STACK_H + STACK_GAP);
            if (ex.maxStack() == 0) {
                g.setFont(MONO);
                g.setColor(MUTED);
                g.drawString("max_stack = 0 — no instruction here touches the stack", 20, stackTop + 8);
            } else {
                for (int u = 0; u < ex.maxStack(); u++) {
                    int y = stackBottom - (u + 1) * (STACK_H + STACK_GAP);
                    g.setColor(EMPTY_FILL);
                    g.fillRect(20, y, STACK_W, STACK_H);
                    g.setColor(new Color(189, 189, 189));
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                            10f, new float[]{5f, 4f}, 0f));
                    g.drawRect(20, y, STACK_W, STACK_H);
                    g.setStroke(new BasicStroke(1f));
                }
                int unit = 0;
                List<Integer> stack = step.stack();
                for (int i = 0; i < stack.size(); i++) {
                    boolean fresh = step.freshTop() && i == stack.size() - 1;
                    int y = stackBottom - (unit + 1) * (STACK_H + STACK_GAP);
                    box(g, 20, y, STACK_W, STACK_H,
                            fresh ? FRESH_FILL : VALUE_FILL,
                            fresh ? OPCODE_BORDER : GRID_LINE,
                            fresh ? 2f : 1f);
                    g.setFont(VALUE_FONT);
                    g.setColor(fresh ? Color.WHITE : INK);
                    String value = String.valueOf(stack.get(i));
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(value, 20 + (STACK_W - fm.stringWidth(value)) / 2, y + STACK_H / 2 + 5);
                    unit++;
                }
                g.setFont(MONO_SMALL);
                g.setColor(MUTED);
                for (int u = 0; u < ex.maxStack(); u++) {
                    int y = stackBottom - (u + 1) * (STACK_H + STACK_GAP);
                    g.drawString("unit " + u, 20 + STACK_W + 10, y + STACK_H / 2 + 4);
                }
                g.setFont(MONO);
                g.drawString("depth = " + step.stack().size() + " / " + ex.maxStack() + " units",
                        20, stackBottom + 16);
            }

            // constant pool
            int cpTop = stackBottom + (ex.maxStack() > 0 ? 52 : 36);
            label(g, "constant pool", 20, cpTop - 8);
            if (ex.cp().isEmpty()) {
                g.setFont(MONO);
                g.setColor(MUTED);
                g.drawString("no instruction in this method references the pool", 20, cpTop + 10);
            } else {
                int y = cpTop + 2;
                for (CpRow row : ex.cp()) {
                    boolean hit = !row.filler() && row.index() == step.cpIndex();
                    if (row.filler()) {
                        g.setFont(ITALIC_FONT);
                        g.setColor(new Color(165, 165, 165));
                        g.drawString("#" + row.index() + row.value(), 24, y + 16);
                    } else {
                        if (hit) {
                            box(g, 20, y, w - 44, 24, READ_FILL, READ_COLOR, 2f);
                        }
                        g.setFont(MONO);
                        g.setColor(hit ? new Color(160, 100, 0) : INK);
                        String idx = "#" + row.index();
                        g.drawString(idx, 28, y + 17);
                        g.setColor(MUTED);
                        g.drawString(row.tag(), 72, y + 17);
                        g.setFont(CODE_FONT);
                        g.setColor(hit ? new Color(160, 100, 0) : INK);
                        g.drawString(row.value(), 150, y + 17);
                    }
                    y += 26;
                }
            }

            // returned chip
            if (!step.alive()) {
                String text = "returned " + step.returned() + " to the caller";
                g.setFont(CHIP_FONT);
                FontMetrics fm = g.getFontMetrics();
                int cw = fm.stringWidth(text) + 28;
                int cx = Math.min(w - 28 - cw, 20);
                int cy = getHeight() - 58;
                g.setColor(new Color(232, 245, 233));
                g.fillRoundRect(cx, cy, cw, 40, 14, 14);
                g.setColor(RETURN_COLOR);
                g.setStroke(new BasicStroke(1.5f));
                g.drawRoundRect(cx, cy, cw, 40, 14, 14);
                g.setStroke(new BasicStroke(1f));
                g.setColor(RETURN_COLOR);
                g.drawString(text, cx + 14, cy + 25);
            }
        }
    }
}
