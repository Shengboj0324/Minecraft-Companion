package io.github.shengboj0324.architect.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small, safe math expression language for the "shape" op, so Claude can describe curved and free-form
 * architecture (shells, domes, twisted towers, arches of any profile) as an inequality over block coordinates.
 * No scripting, no reflection: only arithmetic, comparisons, logic and a fixed list of functions.
 * <pre>
 *   variables: x y z (block coordinates), u v w (0..1 across the region), nx ny nz (-1..1 from the region centre),
 *              cx cy cz (region centre), sx sy sz (region size), r (horizontal distance from the centre axis),
 *              a (angle around the centre axis in degrees, 0 = north, clockwise), pi
 *   operators:  + - * / % ^  < <= > >= == !=  && || !  and the ternary  cond ? a : b
 *   functions:  sin cos tan asin acos atan atan2 sqrt abs min max pow floor ceil round hypot exp log clamp
 *               lerp step smooth mod noise(x,y,z) (deterministic 0..1)
 * </pre>
 * Trigonometric functions take degrees.
 */
public final class Expr {

    public static final String[] VARS = {"x", "y", "z", "u", "v", "w", "nx", "ny", "nz", "cx", "cy", "cz", "sx", "sy", "sz", "r", "a", "pi"};

    @FunctionalInterface
    public interface Node { double eval(double[] v); }

    private final String src;
    private int pos;
    private final Node root;

    private Expr(String src) {
        if (src.length() > 4000) throw new IllegalArgumentException("expression too long (max 4000 chars)");
        this.src = src;
        this.root = ternary();
        skip();
        if (pos != src.length()) throw err("unexpected '" + src.charAt(pos) + "'");
    }

    public static Node compile(String src) { return new Expr(src).root; }

    public static int var(String name) {
        for (int i = 0; i < VARS.length; i++) if (VARS[i].equals(name)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ grammar

    private Node ternary() {
        Node c = or();
        skip();
        if (eat('?')) {
            Node a = ternary();
            skip();
            if (!eat(':')) throw err("expected ':' in ternary");
            Node b = ternary();
            return v -> c.eval(v) != 0 ? a.eval(v) : b.eval(v);
        }
        return c;
    }

    private Node or() {
        Node l = and();
        while (true) {
            skip();
            if (eat2("||")) { Node a = l, b = and(); l = v -> (a.eval(v) != 0 || b.eval(v) != 0) ? 1 : 0; }
            else return l;
        }
    }

    private Node and() {
        Node l = cmp();
        while (true) {
            skip();
            if (eat2("&&")) { Node a = l, b = cmp(); l = v -> (a.eval(v) != 0 && b.eval(v) != 0) ? 1 : 0; }
            else return l;
        }
    }

    private Node cmp() {
        Node l = add();
        skip();
        Node a = l;
        if (eat2("<=")) { Node b = add(); return v -> a.eval(v) <= b.eval(v) ? 1 : 0; }
        if (eat2(">=")) { Node b = add(); return v -> a.eval(v) >= b.eval(v) ? 1 : 0; }
        if (eat2("==")) { Node b = add(); return v -> Math.abs(a.eval(v) - b.eval(v)) < 1e-9 ? 1 : 0; }
        if (eat2("!=")) { Node b = add(); return v -> Math.abs(a.eval(v) - b.eval(v)) >= 1e-9 ? 1 : 0; }
        if (eat('<')) { Node b = add(); return v -> a.eval(v) < b.eval(v) ? 1 : 0; }
        if (eat('>')) { Node b = add(); return v -> a.eval(v) > b.eval(v) ? 1 : 0; }
        return l;
    }

    private Node add() {
        Node l = mul();
        while (true) {
            skip();
            if (eat('+')) { Node a = l, b = mul(); l = v -> a.eval(v) + b.eval(v); }
            else if (peek('-')) { pos++; Node a = l, b = mul(); l = v -> a.eval(v) - b.eval(v); }
            else return l;
        }
    }

    private Node mul() {
        Node l = unary();
        while (true) {
            skip();
            if (eat('*')) { Node a = l, b = unary(); l = v -> a.eval(v) * b.eval(v); }
            else if (eat('/')) { Node a = l, b = unary(); l = v -> { double d = b.eval(v); return d == 0 ? 0 : a.eval(v) / d; }; }
            else if (eat('%')) { Node a = l, b = unary(); l = v -> { double d = b.eval(v); return d == 0 ? 0 : a.eval(v) - d * Math.floor(a.eval(v) / d); }; }
            else return l;
        }
    }

    private Node unary() {
        skip();
        if (eat('-')) { Node a = unary(); return v -> -a.eval(v); }
        if (eat('+')) return unary();
        if (peek('!') && !(pos + 1 < src.length() && src.charAt(pos + 1) == '=')) { pos++; Node a = unary(); return v -> a.eval(v) == 0 ? 1 : 0; }
        Node base = atom();
        skip();
        if (eat('^')) { Node e = unary(); return v -> Math.pow(base.eval(v), e.eval(v)); }
        return base;
    }

    private Node atom() {
        skip();
        if (pos >= src.length()) throw err("unexpected end");
        char ch = src.charAt(pos);
        if (ch == '(') {
            pos++;
            Node n = ternary();
            skip();
            if (!eat(')')) throw err("expected ')'");
            return n;
        }
        if (Character.isDigit(ch) || ch == '.') {
            int s = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            double d = Double.parseDouble(src.substring(s, pos));
            return v -> d;
        }
        if (Character.isLetter(ch) || ch == '_') {
            int s = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
            String id = src.substring(s, pos).toLowerCase(Locale.ROOT);
            skip();
            if (eat('(')) {
                List<Node> args = new ArrayList<>();
                skip();
                if (!eat(')')) {
                    do { args.add(ternary()); skip(); } while (eat(','));
                    if (!eat(')')) throw err("expected ')' after arguments of " + id);
                }
                return function(id, args);
            }
            if (id.equals("pi")) return v -> Math.PI;
            if (id.equals("true")) return v -> 1;
            if (id.equals("false")) return v -> 0;
            int idx = var(id);
            if (idx < 0) throw err("unknown variable '" + id + "' (use " + String.join(" ", VARS) + ")");
            return v -> v[idx];
        }
        throw err("unexpected '" + ch + "'");
    }

    private Node function(String f, List<Node> a) {
        int n = a.size();
        java.util.function.IntPredicate need = k -> n == k;
        switch (f) {
            case "sin": arity(f, n, 1); return v -> Math.sin(Math.toRadians(a.get(0).eval(v)));
            case "cos": arity(f, n, 1); return v -> Math.cos(Math.toRadians(a.get(0).eval(v)));
            case "tan": arity(f, n, 1); return v -> Math.tan(Math.toRadians(a.get(0).eval(v)));
            case "asin": arity(f, n, 1); return v -> Math.toDegrees(Math.asin(clamp(a.get(0).eval(v), -1, 1)));
            case "acos": arity(f, n, 1); return v -> Math.toDegrees(Math.acos(clamp(a.get(0).eval(v), -1, 1)));
            case "atan": arity(f, n, 1); return v -> Math.toDegrees(Math.atan(a.get(0).eval(v)));
            case "atan2": arity(f, n, 2); return v -> Math.toDegrees(Math.atan2(a.get(0).eval(v), a.get(1).eval(v)));
            case "sqrt": arity(f, n, 1); return v -> Math.sqrt(Math.max(0, a.get(0).eval(v)));
            case "abs": arity(f, n, 1); return v -> Math.abs(a.get(0).eval(v));
            case "floor": arity(f, n, 1); return v -> Math.floor(a.get(0).eval(v));
            case "ceil": arity(f, n, 1); return v -> Math.ceil(a.get(0).eval(v));
            case "round": arity(f, n, 1); return v -> Math.rint(a.get(0).eval(v));
            case "exp": arity(f, n, 1); return v -> Math.exp(Math.min(50, a.get(0).eval(v)));
            case "log": arity(f, n, 1); return v -> { double d = a.get(0).eval(v); return d <= 0 ? -1e9 : Math.log(d); };
            case "pow": arity(f, n, 2); return v -> Math.pow(a.get(0).eval(v), a.get(1).eval(v));
            case "hypot": arity(f, n, 2); return v -> Math.hypot(a.get(0).eval(v), a.get(1).eval(v));
            case "mod": arity(f, n, 2); return v -> { double d = a.get(1).eval(v), x = a.get(0).eval(v); return d == 0 ? 0 : x - d * Math.floor(x / d); };
            case "step": arity(f, n, 2); return v -> a.get(1).eval(v) >= a.get(0).eval(v) ? 1 : 0;
            case "clamp": arity(f, n, 3); return v -> clamp(a.get(0).eval(v), a.get(1).eval(v), a.get(2).eval(v));
            case "lerp": arity(f, n, 3); return v -> { double t = a.get(2).eval(v); return a.get(0).eval(v) * (1 - t) + a.get(1).eval(v) * t; };
            case "smooth": arity(f, n, 3); return v -> {
                double e0 = a.get(0).eval(v), e1 = a.get(1).eval(v), x = a.get(2).eval(v);
                double t = clamp((x - e0) / (e1 - e0 == 0 ? 1e-9 : e1 - e0), 0, 1);
                return t * t * (3 - 2 * t);
            };
            case "noise": arity(f, n, 3); return v -> hash(a.get(0).eval(v), a.get(1).eval(v), a.get(2).eval(v));
            case "min": if (n < 1) throw err("min needs arguments"); return v -> { double m = Double.MAX_VALUE; for (Node x : a) m = Math.min(m, x.eval(v)); return m; };
            case "max": if (n < 1) throw err("max needs arguments"); return v -> { double m = -Double.MAX_VALUE; for (Node x : a) m = Math.max(m, x.eval(v)); return m; };
            default: throw err("unknown function '" + f + "'");
        }
    }

    private void arity(String f, int n, int k) { if (n != k) throw err(f + " takes " + k + " argument(s)"); }

    static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    static double hash(double x, double y, double z) {
        long h = (long) Math.floor(x) * 73856093L ^ (long) Math.floor(y) * 19349663L ^ (long) Math.floor(z) * 83492791L;
        h ^= (h >>> 13);
        h *= 0x5bd1e995L;
        h ^= (h >>> 15);
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private IllegalArgumentException err(String m) { return new IllegalArgumentException("expression error at " + pos + ": " + m); }

    private void skip() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }
    private boolean peek(char c) { return pos < src.length() && src.charAt(pos) == c; }
    private boolean eat(char c) { if (peek(c)) { pos++; return true; } return false; }
    private boolean eat2(String s) { if (src.startsWith(s, pos)) { pos += 2; return true; } return false; }
}
