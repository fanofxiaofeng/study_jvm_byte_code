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
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;

/**
 * A step-by-step visualisation of JVMS 3.5 "More Control Examples".
 *
 * <p>The demo shows five examples:
 * <ul>
 *   <li>whileInt in the spec layout (test at the bottom: goto + if_icmplt),
 *   <li>whileInt in the javac layout (test at the top: if_icmpge + goto),
 *   <li>whileDouble (no dinc; dcmpg + iflt),
 *   <li>lessThan100 (dcmpg + ifge),
 *   <li>greaterThan100 (dcmpl + ifle).
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   javac ControlFlowDemo.java
 *   java ControlFlowDemo
 * </pre>
 * Headless figure export for the article:
 * <pre>
 *   java ControlFlowDemo --render img
 * </pre>
 *
 * <p>Structure of this file:
 * <ol>
 *   <li>colours, fonts and model records</li>
 *   <li>example construction (bytecode arrays) and decoding</li>
 *   <li>simulation: execute instructions and record a snapshot per step</li>
 *   <li>image export ({@code --render})</li>
 *   <li>application state, controls and event handling</li>
 *   <li>the four view panels: source, instructions, control flow, runtime frame</li>
 * </ol>
 */
public class ControlFlowDemo {

    // =====================================================================
    // 1. Visual language and model
    // =====================================================================

    // Colours shared by all demos in this series.
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
    private static final Color BRANCH_YES = new Color(46, 125, 50);
    private static final Color BRANCH_NO = new Color(190, 190, 190);
    private static final Color PAIR_COLOR = new Color(123, 31, 162);

    private static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 13);
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Font MONO_SMALL = new Font(Font.MONOSPACED, Font.PLAIN, 10);
    private static final Font SANS_SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final Font SOURCE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font VAR_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    private static final Font VALUE_FONT = new Font(Font.MONOSPACED, Font.BOLD, 14);
    private static final Font CHIP_FONT = new Font(Font.MONOSPACED, Font.BOLD, 12);

    /** A value on the operand stack or in a local: 'I' int, 'D' double, 'R' reference. */
    private record Val(char type, double number) {
        static Val i(int value) { return new Val('I', value); }
        static Val d(double value) { return new Val('D', value); }
        static Val ref() { return new Val('R', 0); }

        /** Number of frame slots / stack depth units this value occupies. */
        int slots() { return type == 'D' ? 2 : 1; }

        int asInt() { return (int) number; }
        double asDouble() { return number; }

        String text() {
            return switch (type) {
                case 'R' -> "ref";
                case 'I' -> String.valueOf((int) number);
                default -> Double.isNaN(number) ? "NaN" : Double.toString(number);
            };
        }
    }

    /** One local variable: its visible name, slot count and JVM type tag. */
    private record LocalDef(String name, int slots, char type) {}

    /** The instruction subset used by the demo, keyed by opcode. */
    private enum Op {
        ICONST_M1(0x02, "iconst_m1"), ICONST_0(0x03, "iconst_0"),
        ICONST_1(0x04, "iconst_1"), BIPUSH(0x10, "bipush"),
        ILOAD_1(0x1b, "iload_1"), ISTORE_1(0x3c, "istore_1"),
        IINC(0x84, "iinc"),
        DCONST_0(0x0e, "dconst_0"), DCONST_1(0x0f, "dconst_1"),
        DLOAD_1(0x27, "dload_1"), DSTORE_1(0x39, "dstore_1"),
        DADD(0x63, "dadd"), LDC2_W(0x14, "ldc2_w"),
        DCMPL(0x97, "dcmpl"), DCMPG(0x98, "dcmpg"),
        IF_ICMPLT(0xa1, "if_icmplt"), IF_ICMPGE(0xa2, "if_icmpge"),
        IFLT(0x9b, "iflt"), IFGE(0x9c, "ifge"), IFLE(0x9e, "ifle"),
        GOTO(0xa7, "goto"), IRETURN(0xac, "ireturn"), RETURN(0xb1, "return");

        final int code;
        final String mnemonic;

        Op(int code, String mnemonic) {
            this.code = code;
            this.mnemonic = mnemonic;
        }

        private static final Op[] BY_CODE = buildLookup();

        private static Op[] buildLookup() {
            Op[] table = new Op[256];
            for (Op op : values()) table[op.code] = op;
            return table;
        }

        static Op of(int opcode) {
            Op op = opcode >= 0 && opcode < BY_CODE.length ? BY_CODE[opcode] : null;
            if (op == null) throw new IllegalStateException("opcode not in demo subset: " + hexCode(opcode));
            return op;
        }

        boolean isBranch() {
            return switch (this) {
                case IF_ICMPLT, IF_ICMPGE, IFLT, IFGE, IFLE, GOTO -> true;
                default -> false;
            };
        }
    }

    /** A decoded instruction: where it is, how wide it is, and what it says. */
    private record Insn(int offset, int length, Op op, String text, String effect,
                        int target, String detail, String bytes) {}

    /**
     * One GUI frame: what is executing, what the frame looks like afterwards,
     * and which branch / slot should be highlighted.
     */
    private record Step(int active, int pc, List<Val> locals, List<Val> stack,
                        boolean freshTop, boolean alive, Val returned,
                        int readLocal, int writtenLocal,
                        Integer target, Boolean taken, int loopCount,
                        String description) {}

    /** Everything needed to render one of the five examples. */
    private record Example(int which, String label, String prefix, String signature,
                           int bodyLine, String paramLabel, String paramText,
                           boolean paramIsInt, byte[] code, Map<Integer, Double> cp,
                           List<Insn> insns, List<LocalDef> locals, int maxStack,
                           List<Step> steps, String footer) {}

    private static final int WHILE_INT_SPEC = 0;
    private static final int WHILE_INT_JAVAC = 1;
    private static final int WHILE_DOUBLE = 2;
    private static final int LESS_THAN = 3;
    private static final int GREATER_THAN = 4;

    private static final int STEP_GUARD = 5000;

    /** ShowControl.java, with one source line highlighted per method. */
    private static final String[] SOURCE_LINES = {
            "class ShowControl {",
            "    void whileInt() {",
            "        int i = 0;",
            "        while (i < N) {",
            "            i++;",
            "        }",
            "    }",
            "",
            "    void whileDouble() {",
            "        double i = 0.0;",
            "        while (i < N) {",
            "            i++;",
            "        }",
            "    }",
            "",
            "    int lessThan100(double d) {",
            "        if (d < 100.0) return 1;",
            "        else return -1;",
            "    }",
            "",
            "    int greaterThan100(double d) {",
            "        if (d > 100.0) return 1;",
            "        else return -1;",
            "    }",
            "}"
    };

    // =====================================================================
    // 2. Building and decoding bytecode
    // =====================================================================

    /** Build all model data for one example and the given parameter text. */
    private static Example build(int which, String paramText) {
        int n = 0;
        double d = 0;
        byte[] code;
        Map<Integer, Double> cp = new LinkedHashMap<>();
        List<LocalDef> locals;
        int maxStack;
        int bodyLine;
        String label;
        String prefix;
        String signature;
        String paramLabel;
        boolean paramIsInt;
        String footer;

        if (which == WHILE_INT_SPEC || which == WHILE_INT_JAVAC) {
            paramIsInt = true;
            paramLabel = "bound N";
            try {
                n = Integer.parseInt(paramText.trim());
            } catch (NumberFormatException e) {
                n = 3;
            }
            n = Math.max(-128, Math.min(127, n));  // bipush has a signed byte operand
            paramText = String.valueOf(n);
            locals = List.of(new LocalDef("this", 1, 'R'), new LocalDef("i", 1, 'I'));
            maxStack = 2;
            bodyLine = 4;
            if (which == WHILE_INT_SPEC) {
                label = "whileInt — spec 3.5 (test at bottom)";
                prefix = "whileint-spec";
                signature = "void whileInt()";
                code = new byte[]{0x03, 0x3c, (byte) 0xa7, 0x00, 0x03,
                        (byte) 0x84, 0x01, 0x01,
                        0x1b, 0x10, (byte) n, (byte) 0xa1, (byte) 0xff, (byte) 0xf7,
                        (byte) 0xb1};
                footer = "goto skips ahead to the test; if_icmplt jumps back — 4 instructions per iteration";
            } else {
                label = "whileInt — javac style (test at top)";
                prefix = "whileint-javac";
                signature = "void whileInt()";
                code = new byte[]{0x03, 0x3c, 0x1b, 0x10, (byte) n,
                        (byte) 0xa2, 0x00, 0x06,
                        (byte) 0x84, 0x01, 0x01,
                        (byte) 0xa7, (byte) 0xff, (byte) 0xf4,
                        (byte) 0xb1};
                footer = "test first, then increment — the back edge is the trailing goto 2";
            }
        } else if (which == WHILE_DOUBLE) {
            paramIsInt = false;
            paramLabel = "bound N";
            try {
                d = Double.parseDouble(paramText.trim());
            } catch (NumberFormatException e) {
                d = 2.5;
            }
            paramText = Double.toString(d);
            cp.put(4, d);
            locals = List.of(new LocalDef("this", 1, 'R'), new LocalDef("i", 2, 'D'));
            maxStack = 4;
            bodyLine = 11;
            label = "whileDouble — spec 3.5";
            prefix = "whiledouble";
            signature = "void whileDouble()";
            code = new byte[]{0x0e, 0x39, (byte) 0xa7, 0x00, 0x04,
                    0x27, 0x0f, 0x63, 0x39,
                    0x27, 0x14, 0x00, 0x04,
                    (byte) 0x98, (byte) 0x9b, (byte) 0xff, (byte) 0xf4,
                    (byte) 0xb1};
            footer = "no dinc / if_dcmplt: 4 instructions for i++, 2 for the test (dcmpg + iflt)";
        } else {
            paramIsInt = false;
            paramLabel = "d (NaN allowed)";
            try {
                d = Double.parseDouble(paramText.trim());
            } catch (NumberFormatException e) {
                d = which == LESS_THAN ? 99.0 : 101.0;
            }
            paramText = Double.toString(d);
            cp.put(4, 100.0);
            locals = List.of(new LocalDef("this", 1, 'R'), new LocalDef("d", 2, 'D'));
            maxStack = 4;
            if (which == LESS_THAN) {
                bodyLine = 17;
                label = "lessThan100(d) — dcmpg + ifge";
                prefix = "lessthan";
                signature = "int lessThan100(double)";
                code = new byte[]{0x27, 0x14, 0x00, 0x04, (byte) 0x98,
                        (byte) 0x9c, 0x00, 0x02, 0x04, (byte) 0xac, 0x02, (byte) 0xac};
                footer = "dcmpg pushes 1 for NaN → ifge jumps → returns -1";
            } else {
                bodyLine = 22;
                label = "greaterThan100(d) — dcmpl + ifle";
                prefix = "greaterthan";
                signature = "int greaterThan100(double)";
                code = new byte[]{0x27, 0x14, 0x00, 0x04, (byte) 0x97,
                        (byte) 0x9e, 0x00, 0x02, 0x04, (byte) 0xac, 0x02, (byte) 0xac};
                footer = "dcmpl pushes -1 for NaN → ifle jumps → returns -1";
            }
        }

        List<Insn> insns = decode(code, cp);
        List<Step> steps = simulate(insns, cp, locals, which, paramText);
        return new Example(which, label, prefix, signature, bodyLine, paramLabel, paramText,
                paramIsInt, code, cp, insns, locals, maxStack, steps, footer);
    }

    /** Decode the raw byte array, resolving branch targets and pool indices. */
    private static List<Insn> decode(byte[] code, Map<Integer, Double> cp) {
        List<Insn> out = new ArrayList<>();
        int pc = 0;
        while (pc < code.length) {
            Op op = Op.of(code[pc] & 0xff);
            int length = switch (op) {
                case BIPUSH -> 2;
                case IINC, LDC2_W, IF_ICMPLT, IF_ICMPGE, IFLT, IFGE, IFLE, GOTO -> 3;
                default -> 1;
            };

            String operand = "";
            int target = -1;
            String detail = switch (op) {
                case BIPUSH -> {
                    int value = code[pc + 1];
                    operand = String.valueOf(value);
                    yield "operand " + hex(code[pc + 1]) + " = " + value + " (signed byte)";
                }
                case IINC -> {
                    int slot = code[pc + 1] & 0xff;
                    int increment = code[pc + 2];
                    operand = slot + ", " + increment;
                    yield "operands: slot " + slot + ", increment " + increment + " — no stack";
                }
                case LDC2_W -> {
                    int pool = ((code[pc + 1] & 0xff) << 8) | (code[pc + 2] & 0xff);
                    operand = "#" + pool;
                    yield "operand 0x" + hex(code[pc + 2]) + " → pool #" + pool
                            + " = " + cp.get(pool) + " (double, 2 units)";
                }
                case IF_ICMPLT, IF_ICMPGE, IFLT, IFGE, IFLE, GOTO -> {
                    int offset = signedShort(code[pc + 1], code[pc + 2]);
                    target = pc + 3 + offset;
                    operand = String.valueOf(target);
                    String kind = op == Op.GOTO ? "unconditional branch" : "branch offset";
                    yield kind + " " + offset + " → target " + target;
                }
                default -> defaultDetail(op);
            };

            String text = operand.isEmpty() ? op.mnemonic : op.mnemonic + " " + operand;
            out.add(new Insn(pc, length, op, text, effectOf(op, operand),
                    target, detail, bytesOf(code, pc, length)));
            pc += length;
        }
        return out;
    }

    /** Static one-line explanations for instructions without operands. */
    private static String defaultDetail(Op op) {
        return switch (op) {
            case ICONST_M1 -> "push int -1, encoded in the opcode";
            case ICONST_0 -> "push int 0, encoded in the opcode";
            case ICONST_1 -> "push int 1, encoded in the opcode";
            case ILOAD_1 -> "push local slot 1 (i)";
            case ISTORE_1 -> "pop int → local slot 1 (i)";
            case DCONST_0 -> "push double 0.0 (2 depth units)";
            case DCONST_1 -> "push double 1.0 (2 depth units)";
            case DLOAD_1 -> "push local slots 1,2 (double i)";
            case DSTORE_1 -> "pop double → local slots 1,2";
            case DADD -> "pop 2 doubles, push sum (2 depth units)";
            case DCMPL -> "compare doubles → int -1/0/1; NaN gives -1";
            case DCMPG -> "compare doubles → int -1/0/1; NaN gives 1";
            case IRETURN -> "pop int and return to caller";
            case RETURN -> "return void, stack is empty at this point";
            default -> "";
        };
    }

    /** Short "what it does" text shown next to each decoded instruction. */
    private static String effectOf(Op op, String operand) {
        return switch (op) {
            case ICONST_0 -> "push 0";
            case ICONST_1 -> "push 1";
            case ICONST_M1 -> "push -1";
            case BIPUSH -> "push " + operand;
            case ILOAD_1 -> "push i";
            case ISTORE_1 -> "pop → i";
            case IINC -> "i += " + operand.split(",")[1].trim() + " (no stack)";
            case IF_ICMPLT -> "if a < b → " + operand;
            case IF_ICMPGE -> "if a >= b → " + operand;
            case GOTO -> "jump → " + operand;
            case IRETURN -> "return int";
            case RETURN -> "return void";
            case DCONST_0 -> "push 0.0";
            case DCONST_1 -> "push 1.0";
            case DLOAD_1 -> "push i (2 slots)";
            case DSTORE_1 -> "pop → i (2 slots)";
            case DADD -> "pop 2, push sum";
            case LDC2_W -> "push pool " + operand;
            case DCMPG, DCMPL -> "compare → int";
            case IFLT -> "if < 0 → " + operand;
            case IFGE -> "if >= 0 → " + operand;
            case IFLE -> "if <= 0 → " + operand;
        };
    }

    // =====================================================================
    // 3. Simulation
    // =====================================================================

    /**
     * Mutable execution state. One instance runs one example; each instruction
     * produces a {@link Step} snapshot appended to the result list.
     */
    private static final class Machine {
        final List<Val> locals;
        final List<Val> stack = new ArrayList<>();
        int pc;
        int loopCount;

        Machine(int which, List<LocalDef> defs, String paramText) {
            locals = new ArrayList<>();
            for (LocalDef def : defs) {
                if (def.type() == 'R') {
                    locals.add(Val.ref());
                } else if (def.type() == 'D') {
                    locals.add(Val.d(which == WHILE_DOUBLE ? 0.0 : Double.parseDouble(paramText)));
                } else {
                    locals.add(Val.i(0));
                }
            }
        }

        Val pop() {
            return stack.remove(stack.size() - 1);
        }
    }

    /** Accumulates the visible effects of a single instruction. */
    private static final class StepDraft {
        final int active;
        int pc;
        boolean freshTop;
        boolean alive = true;
        Val returned;
        int readLocal = -1;
        int writtenLocal = -1;
        Integer target;
        Boolean taken;
        String description = "";

        StepDraft(int active, int nextPc) {
            this.active = active;
            this.pc = nextPc;
        }

        Step toStep(Machine m) {
            return new Step(active, pc, List.copyOf(m.locals), List.copyOf(m.stack),
                    freshTop, alive, returned, readLocal, writtenLocal,
                    target, taken, m.loopCount, description);
        }
    }

    private static List<Step> simulate(List<Insn> insns, Map<Integer, Double> cp,
                                       List<LocalDef> locals, int which, String paramText) {
        Map<Integer, Insn> byOffset = new LinkedHashMap<>();
        for (Insn in : insns) byOffset.put(in.offset(), in);

        Machine m = new Machine(which, locals, paramText);
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(-1, 0, List.copyOf(m.locals), List.of(),
                false, true, null, -1, -1, null, null, 0,
                "Method " + methodName(which) + " — pc starts at 0." + openingHint(which)));

        int guard = 0;
        while (guard++ < STEP_GUARD) {
            Insn in = byOffset.get(m.pc);
            if (in == null) throw new IllegalStateException("no instruction at pc " + m.pc);
            StepDraft draft = execute(in, m, cp, which, indexOfOffset(insns, m.pc));
            steps.add(draft.toStep(m));
            if (!draft.alive) break;
            m.pc = draft.pc;
        }
        return steps;
    }

    /** Execute one instruction, mutating the machine and describing the effect. */
    private static StepDraft execute(Insn in, Machine m, Map<Integer, Double> cp,
                                     int which, int activeIndex) {
        StepDraft d = new StepDraft(activeIndex, in.offset() + in.length());
        String at = in.offset() + ": ";

        switch (in.op()) {
            // ---- load / store / constants (int) ----
            case ICONST_0 -> {
                m.stack.add(Val.i(0));
                d.freshTop = true;
                d.description = at + "iconst_0 — push 0. The value 0 is encoded in the opcode 0x03 itself.";
            }
            case ICONST_1 -> {
                m.stack.add(Val.i(1));
                d.freshTop = true;
                d.description = at + "iconst_1 — push 1 (opcode 0x04, no operands).";
            }
            case ICONST_M1 -> {
                m.stack.add(Val.i(-1));
                d.freshTop = true;
                d.description = at + "iconst_m1 — push -1 (opcode 0x02, no operands).";
            }
            case BIPUSH -> {
                int value = Integer.parseInt(in.effect().substring(5));
                m.stack.add(Val.i(value));
                d.freshTop = true;
                d.description = at + "bipush " + value + " — " + in.detail()
                        + ". The bound rides inside the instruction.";
            }
            case ILOAD_1 -> {
                Val value = m.locals.get(1);
                m.stack.add(value);
                d.freshTop = true;
                d.readLocal = 1;
                d.description = at + "iload_1 — copy slot 1 (i = " + value.text() + ") onto the stack.";
            }
            case ISTORE_1 -> {
                Val value = m.pop();
                m.locals.set(1, value);
                d.writtenLocal = 1;
                d.description = at + "istore_1 — pop " + value.text() + " from the stack into slot 1 (i).";
            }
            case IINC -> {
                int increment = Integer.parseInt(in.effect().split("i \\+= ")[1].split(" ")[0]);
                int before = m.locals.get(1).asInt();
                m.locals.set(1, Val.i(before + increment));
                d.writtenLocal = 1;
                d.description = at + "iinc 1, " + increment + " — slot 1 goes " + before + " → "
                        + (before + increment) + " directly; the operand stack is not touched.";
            }

            // ---- load / store / constants (double) ----
            case DCONST_0 -> {
                m.stack.add(Val.d(0.0));
                d.freshTop = true;
                d.description = at + "dconst_0 — push double 0.0. A double takes 2 depth units on the stack.";
            }
            case DCONST_1 -> {
                m.stack.add(Val.d(1.0));
                d.freshTop = true;
                d.description = at + "dconst_1 — push double 1.0 (2 units).";
            }
            case DLOAD_1 -> {
                Val value = m.locals.get(1);
                m.stack.add(value);
                d.freshTop = true;
                d.readLocal = 1;
                d.description = at + "dload_1 — push the double in slots 1,2 (i = "
                        + value.text() + "), 2 units.";
            }
            case DSTORE_1 -> {
                Val value = m.pop();
                m.locals.set(1, value);
                d.writtenLocal = 1;
                d.description = at + "dstore_1 — pop a double into slots 1,2 (i = "
                        + value.text() + "). One instruction writes both slots.";
            }
            case DADD -> {
                Val b = m.pop();
                Val a = m.pop();
                double sum = a.asDouble() + b.asDouble();
                m.stack.add(Val.d(sum));
                d.freshTop = true;
                d.description = at + "dadd — pop " + a.text() + " and " + b.text() + ", push " + sum
                        + ". This is why i++ needs 4 instructions — there is no dinc.";
            }
            case LDC2_W -> {
                int pool = Integer.parseInt(in.effect().substring(9).trim().replace("#", ""));
                double value = cp.get(pool);
                m.stack.add(Val.d(value));
                d.freshTop = true;
                d.description = at + "ldc2_w #" + pool + " — " + in.detail()
                        + ". The stack receives the value " + value + ", not the index.";
            }

            // ---- compare + branch ----
            case DCMPG, DCMPL -> describeCompare(in, m, d);
            case IF_ICMPLT, IF_ICMPGE -> describeIntBranch(in, m, d);
            case IFLT, IFGE, IFLE -> describeResultBranch(in, m, d);
            case GOTO -> {
                d.taken = true;
                d.target = in.target();
                d.pc = in.target();
                if (in.target() <= in.offset()) m.loopCount++;
                d.description = at + "goto " + in.target()
                        + " — unconditional jump, stack unchanged. "
                        + (in.target() > in.offset()
                        ? "First entry: skip past the body to the test."
                        : "End of a loop iteration: jump back to " + in.target() + ".");
            }

            // ---- return ----
            case IRETURN -> {
                Val value = m.pop();
                d.returned = value;
                d.alive = false;
                d.description = at + "ireturn — pop " + value.text() + " and return it to the caller.";
            }
            case RETURN -> {
                d.alive = false;
                String peak = peakDepthNote(which);
                d.description = at + "return — method ends. Final i = " + m.locals.get(1).text()
                        + "; the stack is empty here — " + peak;
            }
        }
        return d;
    }

    /** dcmpg / dcmpl: pop two doubles, push one int (-1 / 0 / 1). */
    private static void describeCompare(Insn in, Machine m, StepDraft d) {
        double b = m.pop().asDouble();
        double a = m.pop().asDouble();
        boolean nan = Double.isNaN(a) || Double.isNaN(b);
        // JVM comparison treats -0.0 and +0.0 as equal; Double.compare does not.
        int result = nan ? (in.op() == Op.DCMPG ? 1 : -1)
                : (a > b ? 1 : (a == b ? 0 : -1));
        m.stack.add(Val.i(result));
        d.freshTop = true;
        String mnemonic = in.op().mnemonic;
        d.description = in.offset() + ": " + mnemonic + " — pop " + a + " and " + b
                + ", push " + result + ". " + (nan
                ? "A NaN operand is unordered: " + mnemonic + " pushes " + result
                    + ", chosen so the following branch treats NaN like a failed comparison."
                : "Compare " + a + " with " + b
                    + ": -1 / 0 / 1. There is no if_dcmplt, so compare and branch are two instructions.");
    }

    /** if_icmplt / if_icmpge: pop two ints and possibly jump. */
    private static void describeIntBranch(Insn in, Machine m, StepDraft d) {
        int b = m.pop().asInt();
        int a = m.pop().asInt();
        boolean taken = in.op() == Op.IF_ICMPLT ? a < b : a >= b;
        d.taken = taken;
        d.target = in.target();
        if (taken) {
            d.pc = in.target();
            if (in.target() <= in.offset()) m.loopCount++;
        }
        boolean atBottom = in.op() == Op.IF_ICMPLT;
        d.description = in.offset() + ": " + in.op().mnemonic + " " + in.target()
                + " — pop " + a + " and " + b + "; " + a + (atBottom ? " < " : " >= ") + b
                + " is " + taken + " → " + (taken
                    ? (atBottom ? "jump back to " + in.target() : "jump to return")
                    : (atBottom ? "fall through to return" : "fall through to the body"))
                + (atBottom ? ". This is the loop test, at the BOTTOM of the loop."
                            : ". javac 21 puts the test at the TOP of the loop.");
    }

    /** iflt / ifge / ifle: pop the int produced by dcmpg/dcmpl and possibly jump. */
    private static void describeResultBranch(Insn in, Machine m, StepDraft d) {
        int result = m.pop().asInt();
        boolean taken = switch (in.op()) {
            case IFLT -> result < 0;
            case IFGE -> result >= 0;
            default -> result <= 0;
        };
        d.taken = taken;
        d.target = in.target();
        if (taken) {
            d.pc = in.target();
            if (in.target() <= in.offset()) m.loopCount++;
        }
        String condition = switch (in.op()) {
            case IFLT -> "r < 0";
            case IFGE -> "r >= 0";
            default -> "r <= 0";
        };
        d.description = in.offset() + ": " + in.op().mnemonic + " " + in.target()
                + " — the compare pushed " + result + "; " + condition + " is " + taken
                + " → " + (taken ? "jump to " + in.target() : "fall through") + ".";
    }

    private static String peakDepthNote(int which) {
        if (which == WHILE_DOUBLE) {
            return "javap reports stack=4 as the peak depth, between ldc2_w and dcmpg.";
        }
        if (which == WHILE_INT_JAVAC) {
            return "javap reports stack=2 as the peak depth, between bipush and if_icmpge.";
        }
        return "javap reports stack=2 as the peak depth, between bipush and if_icmplt.";
    }

    private static String methodName(int which) {
        return switch (which) {
            case WHILE_INT_SPEC, WHILE_INT_JAVAC -> "whileInt";
            case WHILE_DOUBLE -> "whileDouble";
            case LESS_THAN -> "lessThan100";
            default -> "greaterThan100";
        };
    }

    private static String openingHint(int which) {
        return switch (which) {
            case LESS_THAN -> " Follow pc through dcmpg and ifge.";
            case GREATER_THAN -> " Follow pc through dcmpl and ifle.";
            default -> " A loop changes pc: goto jumps once, a conditional branch jumps back many times.";
        };
    }

    private static int indexOfOffset(List<Insn> insns, int pc) {
        for (int i = 0; i < insns.size(); i++) {
            if (insns.get(i).offset() == pc) return i;
        }
        return -1;
    }

    // =====================================================================
    // 4. Small helpers (formatting + painting primitives)
    // =====================================================================

    private static String bytesOf(byte[] code, int from, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(' ');
            out.append(hex(code[from + i]));
        }
        return out.toString();
    }

    private static int signedShort(byte high, byte low) {
        return (short) (((high & 0xff) << 8) | (low & 0xff));
    }

    private static String hex(byte b) {
        return hexCode(b & 0xff);
    }

    private static String hexCode(int value) {
        return String.format("%02X", value & 0xff);
    }

    private static void box(Graphics2D g, int x, int y, int w, int h,
                            Color fill, Color border, float stroke) {
        g.setColor(fill);
        g.fillRect(x, y, w, h);
        g.setColor(border);
        g.setStroke(new BasicStroke(stroke));
        g.drawRect(x, y, w, h);
        g.setStroke(new BasicStroke(1f));
    }

    /** Dashed purple frame marking two adjacent slots as one long/double value. */
    private static void dashedPair(Graphics2D g, int x, int y, int w, int h) {
        Color oldColor = g.getColor();
        Stroke oldStroke = g.getStroke();
        g.setColor(PAIR_COLOR);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{5f, 3f}, 0f));
        g.drawRoundRect(x, y, w, h, 8, 8);
        g.setColor(oldColor);
        g.setStroke(oldStroke);
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        g.setFont(SANS_SMALL);
        g.setColor(MUTED);
        g.drawString(text, x, y);
    }

    // =====================================================================
    // 5. Application state, controls and event handling
    // =====================================================================

    private Example example;
    private int step;
    private int speed = 700;
    private JFrame frame;
    private JComboBox<Example> methodBox;
    private JButton stepButton;
    private JButton runButton;
    private JLabel progressLabel;
    private JTextArea descriptionArea;
    private SourceView sourceView;
    private InsnView insnView;
    private FlowView flowView;
    private RuntimeView runtimeView;
    private Timer timer;
    private JPanel paramPanel;
    private JTextField paramField;

    private static final String[] PARAMS = {"3", "3", "2.5", "99.0", "101.0"};

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--render".equals(args[0])) {
            renderPreviews(args[1]);
            return;
        }
        SwingUtilities.invokeLater(ControlFlowDemo::new);
    }

    private ControlFlowDemo() {
        createAndShowGui();
    }

    private void createAndShowGui() {
        frame = new JFrame("JVM control flow demo — JVMS 3.5: goto, if_icmplt, dcmpg/dcmpl");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        Example[] initial = new Example[PARAMS.length];
        for (int i = 0; i < PARAMS.length; i++) initial[i] = build(i, PARAMS[i]);

        methodBox = new JComboBox<>(initial);
        methodBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel cell = new JLabel(value == null ? "" : value.label());
            cell.setFont(MONO);
            cell.setOpaque(true);
            cell.setBackground(selected ? VALUE_FILL : Color.WHITE);
            return cell;
        });

        stepButton = new JButton("Step ▶");
        stepButton.setFont(CODE_FONT);
        stepButton.setToolTipText("Fetch and execute the next instruction (follow pc)");
        runButton = new JButton("Run");
        runButton.setToolTipText("Execute the remaining instructions automatically");
        JButton resetButton = new JButton("Reset");
        resetButton.setToolTipText("Back to pc = 0 with initial locals and empty stack");
        progressLabel = new JLabel();
        progressLabel.setFont(MONO);
        progressLabel.setForeground(MUTED);

        JSlider speedSlider = new JSlider(150, 1400, speed);
        speedSlider.setPreferredSize(new Dimension(120, 24));
        speedSlider.addChangeListener(e -> {
            speed = speedSlider.getValue();
            timer.setDelay(speed);
        });

        JPanel methodRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        methodRow.setAlignmentX(0f);
        methodRow.add(new JLabel("method"));
        methodRow.add(methodBox);
        methodRow.add(stepButton);
        methodRow.add(runButton);
        methodRow.add(resetButton);
        methodRow.add(new JLabel("  speed"));
        methodRow.add(speedSlider);
        methodRow.add(progressLabel);

        paramPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        paramPanel.setAlignmentX(0f);

        JPanel control = new JPanel();
        control.setLayout(new BoxLayout(control, BoxLayout.Y_AXIS));
        control.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        control.add(methodRow);
        control.add(paramPanel);

        sourceView = new SourceView();
        insnView = new InsnView();
        flowView = new FlowView();
        runtimeView = new RuntimeView();

        JPanel leftColumn = new JPanel();
        leftColumn.setLayout(new BoxLayout(leftColumn, BoxLayout.Y_AXIS));
        leftColumn.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        leftColumn.add(sourceView);
        leftColumn.add(insnView);

        JPanel rightColumn = new JPanel();
        rightColumn.setLayout(new BoxLayout(rightColumn, BoxLayout.Y_AXIS));
        rightColumn.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        rightColumn.add(flowView);
        rightColumn.add(runtimeView);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                scrollPane(leftColumn), scrollPane(rightColumn));
        split.setBorder(null);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.44);

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

        timer = new Timer(speed, e -> doStep());
        stepButton.addActionListener(e -> doStep());
        runButton.addActionListener(e -> startRun());
        resetButton.addActionListener(e -> resetDemo());
        methodBox.addActionListener(e -> applyExample((Example) methodBox.getSelectedItem()));

        example = initial[0];
        applyExample(example);

        frame.setSize(1240, 1040);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        split.setDividerLocation(0.44);
    }

    private static JScrollPane scrollPane(JPanel content) {
        JScrollPane pane = new JScrollPane(content);
        pane.setBorder(null);
        pane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return pane;
    }

    private void applyExample(Example ex) {
        example = ex;
        sourceView.setExample(ex);
        insnView.setExample(ex);
        flowView.setExample(ex);
        runtimeView.setExample(ex);
        updateParamPanel();
        resetDemo();
    }

    /** Rebuild the second control row (bound input + Apply) for the current example. */
    private void updateParamPanel() {
        paramPanel.removeAll();
        paramPanel.add(new JLabel(example.paramLabel() + " ="));

        paramField = new JTextField(example.paramText(), 8);
        paramField.setFont(MONO);
        paramField.setToolTipText(paramTooltip(example));
        paramField.addActionListener(e -> applyParam());
        paramPanel.add(paramField);

        JButton applyButton = new JButton("Apply");
        applyButton.setToolTipText("Rebuild the demo with this bound / input value");
        applyButton.addActionListener(e -> applyParam());
        paramPanel.add(applyButton);
        paramPanel.add(new JLabel("   (Enter also applies; loops use a small bound so you can step through)"));
        paramPanel.revalidate();
        paramPanel.repaint();
    }

    private static String paramTooltip(Example ex) {
        if (ex.paramIsInt()) {
            return "Integer bound, -128..127 (bipush operand)";
        }
        String base = "A finite double — try 100.0 or NaN; Infinity is not allowed";
        return ex.which() == WHILE_DOUBLE ? base + "; for whileDouble, |N| ≤ 500" : base;
    }

    /** Validate the parameter text, then rebuild every view from the new value. */
    private void applyParam() {
        String text = paramField.getText().trim();
        String error = validateParam(example, text);
        if (error != null) {
            JOptionPane.showMessageDialog(frame, error, "Invalid parameter", JOptionPane.ERROR_MESSAGE);
            paramField.setText(example.paramText());
            return;
        }
        example = build(example.which(), text);
        sourceView.setExample(example);
        insnView.setExample(example);
        flowView.setExample(example);
        runtimeView.setExample(example);
        resetDemo();
    }

    /** Returns null when the parameter is acceptable, otherwise an error message. */
    private static String validateParam(Example ex, String text) {
        if (ex.paramIsInt()) {
            try {
                int value = Integer.parseInt(text);
                if (value < -128 || value > 127) throw new NumberFormatException();
                return null;
            } catch (NumberFormatException e) {
                return "Bound N must be an integer in -128..127 (bipush has a one-byte operand).";
            }
        }
        try {
            double value = Double.parseDouble(text);
            if (Double.isInfinite(value)) throw new NumberFormatException();
            if (ex.which() == WHILE_DOUBLE && Math.abs(value) > 500) throw new NumberFormatException();
            return null;
        } catch (NumberFormatException e) {
            return ex.which() == WHILE_DOUBLE
                    ? "Bound N must be a finite number in -500..500, or NaN (Infinity not allowed)."
                    : "d must be a finite number or NaN (Infinity not allowed).";
        }
    }

    private void doStep() {
        if (step >= example.steps().size() - 1) return;
        step++;
        refreshView();
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
        refreshView();
    }

    private void refreshView() {
        Step snapshot = example.steps().get(step);
        insnView.setActive(snapshot.active());
        flowView.setStep(step);
        runtimeView.setStep(step);
        progressLabel.setText("   step " + step + " / " + (example.steps().size() - 1)
                + "    pc = " + snapshot.pc() + " / " + example.code().length
                + "    loop × " + snapshot.loopCount());
        descriptionArea.setText(snapshot.description());
        descriptionArea.setCaretPosition(0);
    }

    // =====================================================================
    // 6. Image export (headless)
    // =====================================================================

    private static void renderPreviews(String dir) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File out = new File(dir);
        out.mkdirs();

        Example spec = build(WHILE_INT_SPEC, PARAMS[WHILE_INT_SPEC]);
        render(spec, stepOfKind(spec, Op.GOTO, false), true,
                new File(out, "whileint-spec-step3-flow.png"));
        render(spec, stepOfKind(spec, Op.IF_ICMPLT, false), true,
                new File(out, "whileint-spec-step6-flow.png"));

        Example wd = build(WHILE_DOUBLE, PARAMS[WHILE_DOUBLE]);
        int dadd = stepOfKind(wd, Op.DADD, false);
        render(wd, Math.max(0, dadd - 1), false,
                new File(out, "whiledouble-dadd-before-runtime.png"));
        render(wd, stepOfKind(wd, Op.DCMPG, true), false,
                new File(out, "whiledouble-step30-runtime.png"));

        Example nan = build(LESS_THAN, "NaN");
        render(nan, stepOfKind(nan, Op.DCMPG, false), false,
                new File(out, "lessthan-step3-runtime.png"));

        System.out.println("rendered to " + out.getAbsolutePath());
    }

    /** Index of the first (or last) step whose active instruction is {@code op}. */
    private static int stepOfKind(Example ex, Op op, boolean last) {
        int found = -1;
        for (int i = 0; i < ex.steps().size(); i++) {
            Step s = ex.steps().get(i);
            if (s.active() < 0) continue;
            if (ex.insns().get(s.active()).op() == op) {
                found = i;
                if (!last) return i;
            }
        }
        return Math.max(found, 0);
    }

    private static void render(Example ex, int step, boolean flow, File file) throws Exception {
        JPanel panel = flow ? new FlowView() : new RuntimeView();
        if (flow) {
            ((FlowView) panel).setExample(ex);
            ((FlowView) panel).setStep(step);
        } else {
            ((RuntimeView) panel).setExample(ex);
            ((RuntimeView) panel).setStep(step);
        }
        Dimension size = panel.getPreferredSize();
        size.width = Math.max(size.width, flow ? 460 : 520);
        panel.setSize(size);
        writePng(panel, size.width, size.height, file);
    }

    private static void writePng(JPanel panel, int width, int height, File file) throws Exception {
        int scale = 2;
        BufferedImage image = new BufferedImage(width * scale, height * scale,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.scale(scale, scale);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        panel.paint(g);
        g.dispose();
        ImageIO.write(image, "png", file);
    }

    // =====================================================================
    // 7. View panels
    // =====================================================================

    /** ShowControl.java with the current method's body line highlighted. */
    private static final class SourceView extends JPanel {
        private static final int LINE_HEIGHT = 26;
        private int activeLine = 4;
        private String paramText = "";

        SourceView() {
            setBackground(Color.WHITE);
            int height = Math.max(160, LINE_HEIGHT * SOURCE_LINES.length + 44);
            setPreferredSize(new Dimension(460, height));
            setMinimumSize(new Dimension(400, height));
            setBorder(new TitledBorder("ShowControl.java — source"));
        }

        void setExample(Example ex) {
            paramText = ex.paramText();
            activeLine = ex.bodyLine();
            repaint();
        }

        private static String sourceParam(String text) {
            return "NaN".equals(text) ? "Double.NaN" : text;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = 0; i < SOURCE_LINES.length; i++) {
                int lineNumber = i + 1;
                int y = 10 + i * LINE_HEIGHT;
                if (lineNumber == activeLine) paintHighlight(g, y);
                paintLineNumber(g, lineNumber, y);
                paintSourceLine(g, i, lineNumber, y);
            }
        }

        private void paintHighlight(Graphics2D g, int y) {
            g.setColor(VALUE_FILL);
            g.fillRect(8, y - 3, getWidth() - 16, LINE_HEIGHT);
            g.setColor(CODE_COLOR);
            g.fillRect(8, y - 3, 3, LINE_HEIGHT);
        }

        private void paintLineNumber(Graphics2D g, int lineNumber, int y) {
            g.setFont(MONO_SMALL);
            g.setColor(MUTED);
            String text = String.valueOf(lineNumber);
            g.drawString(text, 26 - g.getFontMetrics().stringWidth(text), y + 15);
        }

        private void paintSourceLine(Graphics2D g, int index, int lineNumber, int y) {
            String line = SOURCE_LINES[index];
            if (lineNumber == activeLine) {
                line = line.replace("while (i < N)", "while (i < " + sourceParam(paramText) + ")");
            }
            g.setFont(SOURCE_FONT);
            g.setColor(INK);
            g.drawString(line, 44, y + 15);
        }
    }

    /** Decoded instructions: offset, raw bytes, mnemonic and short effect. */
    private static final class InsnView extends JPanel {
        private static final int ROW_HEIGHT = 28;
        private List<Insn> insns = List.of();
        private String footer = "";
        private int active = -1;

        InsnView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(460, 160));
            setMinimumSize(new Dimension(400, 160));
            setBorder(new TitledBorder("decoded instructions (offset · bytes · mnemonic)"));
        }

        void setExample(Example ex) {
            insns = ex.insns();
            footer = ex.footer();
            int height = Math.max(120, 10 + insns.size() * ROW_HEIGHT + 34);
            setPreferredSize(new Dimension(460, height));
            setMinimumSize(new Dimension(400, height));
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
            for (int i = 0; i < insns.size(); i++) paintRow(g, insns.get(i), i);
            g.setFont(SANS_SMALL);
            g.setColor(new Color(150, 150, 150));
            g.drawString(footer, 22, 8 + insns.size() * ROW_HEIGHT + 14);
        }

        private void paintRow(Graphics2D g, Insn in, int index) {
            int y = 8 + index * ROW_HEIGHT;
            if (index == active) {
                g.setColor(VALUE_FILL);
                g.fillRect(8, y, getWidth() - 16, ROW_HEIGHT - 2);
                g.setColor(CODE_COLOR);
                g.fillRect(8, y, 3, ROW_HEIGHT - 2);
            }
            g.setFont(MONO_SMALL);
            g.setColor(MUTED);
            g.drawString(String.valueOf(in.offset()), 22, y + 19);
            g.setColor(new Color(120, 120, 120));
            g.drawString(in.bytes(), 44, y + 19);
            g.setFont(CODE_FONT);
            g.setColor(index == active ? CODE_COLOR : INK);
            g.drawString(in.text(), 148, y + 19);
            g.setFont(SANS_SMALL);
            g.setColor(MUTED);
            g.drawString(in.effect(), 300, y + 19);
        }
    }

    /** Control-flow graph: one node per instruction, arcs for jumps. */
    private static final class FlowView extends JPanel {
        private static final int ROW_HEIGHT = 34;
        private static final int LEFT = 78;

        private Example ex = build(WHILE_INT_SPEC, "3");
        private int stepIndex = 0;

        FlowView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(460, 520));
            setMinimumSize(new Dimension(420, 520));
            setBorder(new TitledBorder("control flow — jump arcs"));
        }

        void setExample(Example ex) {
            this.ex = ex;
            int rows = ex.insns().size();
            setBorder(new TitledBorder("control flow — " + rows + " instructions, " + rows + " nodes"));
            int height = Math.max(280, 24 + (rows + 1) * ROW_HEIGHT + 40);
            setPreferredSize(new Dimension(460, height));
            setMinimumSize(new Dimension(420, 280));
            revalidate();
            repaint();
            setStep(0);
        }

        void setStep(int index) {
            stepIndex = index;
            repaint();
        }

        private int rowY(int insnIndex) { return 24 + insnIndex * ROW_HEIGHT; }

        private int indexOf(int pc) {
            for (int i = 0; i < ex.insns().size(); i++) {
                if (ex.insns().get(i).offset() == pc) return i;
            }
            return -1;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Step s = ex.steps().get(stepIndex);
            Color branchColor = Boolean.TRUE.equals(s.taken()) ? BRANCH_YES : BRANCH_NO;

            paintBackbone(g);
            paintArcs(g, s.active(), branchColor);
            paintNodes(g, s.active());
            paintBranchLabels(g, s.active(), branchColor);
            paintPcMarker(g, s);
        }

        private void paintBackbone(Graphics2D g) {
            int x = LEFT - 22;
            g.setStroke(new BasicStroke(1.4f));
            g.setColor(new Color(220, 220, 220));
            g.drawLine(x, rowY(0) + 14, x, rowY(ex.insns().size() - 1) + 14);
            g.setStroke(new BasicStroke(1f));
        }

        private void paintArcs(Graphics2D g, int active, Color branchColor) {
            for (int i = 0; i < ex.insns().size(); i++) {
                Insn in = ex.insns().get(i);
                if (in.target() < 0) continue;
                int j = indexOf(in.target());
                if (j < 0) continue;

                boolean isActive = i == active;
                Color color = isActive ? branchColor : new Color(205, 205, 205);
                float width = isActive ? 2.4f : 1f;
                int x = LEFT - 22;
                int y1 = rowY(i) + 14;
                int y2 = rowY(j) + 14;
                int bulge = i % 2 == 0 ? 26 : -26;

                Path2D arc = new Path2D.Double();
                arc.moveTo(x, y1);
                arc.curveTo(x + bulge, y1, x + bulge, y2, x, y2);
                g.setColor(color);
                g.setStroke(new BasicStroke(width));
                g.draw(arc);
                g.setStroke(new BasicStroke(1f));

                int direction = j > i ? 1 : -1;
                Path2D head = new Path2D.Double();
                head.moveTo(x, y2);
                head.lineTo(x - 4, y2 - direction * 7);
                head.lineTo(x + 4, y2 - direction * 7);
                head.closePath();
                g.setColor(color);
                g.fill(head);
            }
        }

        private void paintNodes(Graphics2D g, int active) {
            for (int i = 0; i < ex.insns().size(); i++) {
                Insn in = ex.insns().get(i);
                int y = rowY(i);
                boolean isActive = i == active;
                box(g, LEFT, y, getWidth() - LEFT - 18, ROW_HEIGHT - 6,
                        isActive ? FRESH_FILL : VALUE_FILL,
                        isActive ? OPCODE_BORDER : GRID_LINE,
                        isActive ? 2f : 1f);

                g.setFont(MONO_SMALL);
                g.setColor(isActive ? Color.WHITE : MUTED);
                g.drawString(String.valueOf(in.offset()), LEFT + 8, y + 19);
                g.setFont(isActive ? CODE_FONT : MONO);
                g.setColor(isActive ? Color.WHITE : INK);
                g.drawString(in.text(), LEFT + 44, y + 19);

                String tag = in.op().isBranch() ? (in.op() == Op.GOTO ? "jump" : "branch") : "";
                if (!tag.isEmpty()) {
                    g.setFont(SANS_SMALL);
                    g.setColor(isActive ? new Color(220, 235, 255) : MUTED);
                    g.drawString(tag, getWidth() - 68, y + 19);
                }
            }
        }

        private void paintBranchLabels(Graphics2D g, int active, Color branchColor) {
            for (int i = 0; i < ex.insns().size(); i++) {
                Insn in = ex.insns().get(i);
                if (in.target() < 0 || i != active) continue;
                int j = indexOf(in.target());
                if (j < 0) continue;
                int bulge = i % 2 == 0 ? 26 : -26;
                int x = LEFT - 22;
                int labelX = x + (bulge > 0 ? -34 : bulge - 12);
                int labelY = (rowY(i) + rowY(j)) / 2 + 14;
                g.setFont(MONO_SMALL);
                g.setColor(branchColor);
                g.drawString(Boolean.TRUE.equals(ex.steps().get(stepIndex).taken())
                        ? "taken" : "not taken", labelX, labelY);
            }
        }

        private void paintPcMarker(Graphics2D g, Step s) {
            int y = rowY(ex.insns().size()) + 8;
            g.setFont(MONO_SMALL);
            if (!s.alive()) {
                g.setColor(RETURN_COLOR);
                g.drawString("returned — execution stops (pc = " + s.pc() + ")", LEFT, y);
            } else if (indexOf(s.pc()) >= 0) {
                g.setColor(CODE_COLOR);
                g.drawString("next pc → " + s.pc(), LEFT, y);
            } else {
                g.setColor(RETURN_COLOR);
                g.drawString("pc = " + s.pc() + " (end of code)", LEFT, y);
            }
        }
    }

    /** Runtime frame: locals, operand stack and constant pool. */
    private static final class RuntimeView extends JPanel {
        private static final int SLOT_WIDTH = 74;
        private static final int LOCAL_HEIGHT = 46;
        private static final int STACK_WIDTH = 120;
        private static final int STACK_UNIT_HEIGHT = 30;
        private static final int STACK_GAP = 5;
        private static final int LOCAL_TOP = 36;
        private static final int LEFT = 20;

        private Example ex = build(WHILE_INT_SPEC, "3");
        private int stepIndex = 0;

        RuntimeView() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(520, 460));
            setMinimumSize(new Dimension(460, 360));
            setBorder(new TitledBorder("frame & constant pool"));
        }

        void setExample(Example ex) {
            this.ex = ex;
            setPreferredSize(new Dimension(520, contentHeight()));
            revalidate();
            repaint();
            setStep(0);
        }

        void setStep(int index) {
            stepIndex = index;
            repaint();
        }

        private int stackTop() { return LOCAL_TOP + LOCAL_HEIGHT + 46; }

        private int stackBottom() {
            return stackTop() + Math.max(2, ex.maxStack()) * (STACK_UNIT_HEIGHT + STACK_GAP);
        }

        private int contentHeight() {
            int cpTop = stackBottom() + 30;
            int cpRows = ex.cp().isEmpty() ? 1 : ex.cp().size();
            return Math.max(400, cpTop + 2 + cpRows * 26 + 60);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Step step = ex.steps().get(stepIndex);

            paintLocals(g, step);
            paintStack(g, step);
            paintConstantPool(g, step);
            if (!step.alive()) paintReturnChip(g, step);
        }

        private void paintLocals(Graphics2D g, Step step) {
            label(g, "locals", LEFT, 28);
            g.setFont(MONO_SMALL);
            g.setColor(PAIR_COLOR);
            g.drawString("dashed = one long/double across 2 slots", LEFT + 56, 28);

            int x = LEFT;
            int slotNumber = 0;
            for (int i = 0; i < ex.locals().size(); i++) {
                LocalDef def = ex.locals().get(i);
                boolean read = i == step.readLocal();
                boolean written = i == step.writtenLocal();
                int slots = def.slots();
                int y = LOCAL_TOP;

                for (int s = 0; s < slots; s++) {
                    int cellX = x + s * (SLOT_WIDTH + 4);
                    box(g, cellX, y, SLOT_WIDTH, LOCAL_HEIGHT,
                            written ? FRESH_FILL : read ? READ_FILL : VALUE_FILL,
                            written ? OPCODE_BORDER : read ? READ_COLOR : GRID_LINE,
                            read || written ? 2.2f : 1f);
                    g.setFont(MONO_SMALL);
                    g.setColor(read || written ? new Color(120, 60, 0) : MUTED);
                    g.drawString("slot " + (slotNumber + s), cellX + 5, y + 13);
                    if (s == 0) {
                        g.setFont(VAR_FONT);
                        g.setColor(new Color(55, 71, 79));
                        g.drawString(def.name(), cellX + 5, y + 28);
                        g.setFont(VALUE_FONT);
                        g.setColor(INK);
                        g.drawString(step.locals().get(i).text(), cellX + 5, y + 45);
                    } else {
                        g.setFont(MONO_SMALL);
                        g.setColor(MUTED);
                        g.drawString("↳", cellX + 5, y + 30);
                    }
                }
                if (slots == 2) {
                    dashedPair(g, x - 4, y - 4, 2 * SLOT_WIDTH + 4 + 8, LOCAL_HEIGHT + 8);
                }
                slotNumber += slots;
                x += slots * SLOT_WIDTH + (slots - 1) * 4 + 12;
            }
        }

        private void paintStack(Graphics2D g, Step step) {
            int top = stackTop();
            int bottom = stackBottom();
            label(g, "operand stack (bottom → top)", LEFT, top - 10);

            int maxUnits = Math.max(2, ex.maxStack());
            for (int unit = 0; unit < maxUnits; unit++) paintEmptySlot(g, bottom, unit);

            int usedUnits = 0;
            for (Val value : step.stack()) usedUnits += value.slots();

            int unit = 0;
            List<Val> stack = step.stack();
            for (int i = 0; i < stack.size(); i++) {
                Val value = stack.get(i);
                boolean fresh = step.freshTop() && i == stack.size() - 1;
                int slots = value.slots();
                for (int s = 0; s < slots; s++) paintStackSlot(g, bottom, unit + s, value, fresh, s);
                if (slots == 2) {
                    int topY = bottom - (unit + slots) * (STACK_UNIT_HEIGHT + STACK_GAP);
                    int margin = unit == 0 && unit + slots == usedUnits ? 4 : 0;
                    dashedPair(g, LEFT - 4, topY - margin, STACK_WIDTH + 8,
                            slots * STACK_UNIT_HEIGHT + (slots - 1) * STACK_GAP + margin * 2);
                }
                unit += slots;
            }

            g.setFont(MONO);
            g.setColor(MUTED);
            g.drawString("depth = " + usedUnits + " / " + ex.maxStack(), 152,
                    bottom - STACK_UNIT_HEIGHT / 2 + 4);
        }

        private void paintEmptySlot(Graphics2D g, int bottom, int unit) {
            int y = bottom - (unit + 1) * (STACK_UNIT_HEIGHT + STACK_GAP);
            g.setColor(EMPTY_FILL);
            g.fillRect(LEFT, y, STACK_WIDTH, STACK_UNIT_HEIGHT);
            g.setColor(new Color(189, 189, 189));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{5f, 4f}, 0f));
            g.drawRect(LEFT, y, STACK_WIDTH, STACK_UNIT_HEIGHT);
            g.setStroke(new BasicStroke(1f));
            g.setFont(MONO_SMALL);
            g.setColor(MUTED);
            g.drawString("slot " + unit, LEFT + 6, y + 13);
        }

        private void paintStackSlot(Graphics2D g, int bottom, int unit,
                                    Val value, boolean fresh, int part) {
            int y = bottom - (unit + 1) * (STACK_UNIT_HEIGHT + STACK_GAP);
            box(g, LEFT, y, STACK_WIDTH, STACK_UNIT_HEIGHT,
                    fresh ? FRESH_FILL : VALUE_FILL,
                    fresh ? OPCODE_BORDER : GRID_LINE,
                    fresh ? 2f : 1f);
            g.setFont(MONO_SMALL);
            g.setColor(fresh ? new Color(220, 235, 255) : MUTED);
            g.drawString("slot " + unit, LEFT + 6, y + 13);
            if (part == 0) {
                g.setFont(VALUE_FONT);
                g.setColor(fresh ? Color.WHITE : INK);
                String text = value.text();
                FontMetrics fm = g.getFontMetrics();
                g.drawString(text, LEFT + (STACK_WIDTH - fm.stringWidth(text)) / 2,
                        y + STACK_UNIT_HEIGHT - 6);
            } else {
                g.setFont(MONO_SMALL);
                g.setColor(fresh ? new Color(220, 235, 255) : MUTED);
                g.drawString("↳ value continues", LEFT + 6, y + STACK_UNIT_HEIGHT - 6);
            }
        }

        private void paintConstantPool(Graphics2D g, Step step) {
            int top = stackBottom() + 30;
            label(g, "constant pool", LEFT, top - 8);
            if (ex.cp().isEmpty()) {
                g.setFont(MONO);
                g.setColor(MUTED);
                g.drawString("no constant-pool access in this method", LEFT, top + 10);
                return;
            }
            int y = top + 2;
            for (Map.Entry<Integer, Double> entry : ex.cp().entrySet()) {
                boolean hit = step.active() >= 0
                        && ex.insns().get(step.active()).text().contains("#" + entry.getKey());
                if (hit) box(g, LEFT, y, getWidth() - 44, 24, READ_FILL, READ_COLOR, 2f);
                g.setFont(MONO);
                g.setColor(MUTED);
                g.drawString("#" + entry.getKey(), 28, y + 17);
                g.setFont(CODE_FONT);
                g.setColor(hit ? new Color(160, 100, 0) : INK);
                g.drawString("Double  " + entry.getValue(), 72, y + 17);
                y += 26;
            }
        }

        private void paintReturnChip(Graphics2D g, Step step) {
            String text = step.returned() == null
                    ? "return: method finished"
                    : "returned " + step.returned().text() + " to the caller";
            g.setFont(CHIP_FONT);
            FontMetrics fm = g.getFontMetrics();
            int width = fm.stringWidth(text) + 28;
            int x = Math.min(getWidth() - 28 - width, LEFT);
            int y = getHeight() - 48;
            g.setColor(new Color(232, 245, 233));
            g.fillRoundRect(x, y, width, 38, 14, 14);
            g.setColor(RETURN_COLOR);
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(x, y, width, 38, 14, 14);
            g.setStroke(new BasicStroke(1f));
            g.setColor(RETURN_COLOR);
            g.drawString(text, x + 14, y + 24);
        }
    }
}
