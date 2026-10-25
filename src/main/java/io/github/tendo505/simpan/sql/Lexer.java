package io.github.tendo505.simpan.sql;

import io.github.tendo505.simpan.SqlException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Splits SQL text into tokens. Unquoted identifiers and keywords fold to lower case, as in PostgreSQL. */
public final class Lexer {
    public enum Kind { IDENT, QUOTED_IDENT, NUMBER, STRING, PARAM, OP, EOF }

    /** One token; {@code pos} is its offset in the SQL text, for error messages. */
    public record Token(Kind kind, String text, int pos) {
        public boolean is(String keywordOrOp) {
            return (kind == Kind.IDENT || kind == Kind.OP) && text.equals(keywordOrOp);
        }

        @Override
        public String toString() {
            return kind == Kind.EOF ? "end of input" : "\"" + text + "\"";
        }
    }

    private Lexer() {}

    public static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw SqlException.syntax("unterminated /* comment");
                i = end + 2;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                out.add(new Token(Kind.IDENT, sql.substring(start, i).toLowerCase(Locale.ROOT), start));
            } else if (c == '"') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) throw SqlException.syntax("unterminated quoted identifier at position " + start);
                    char d = sql.charAt(i++);
                    if (d == '"') {
                        if (i < n && sql.charAt(i) == '"') {
                            sb.append('"');
                            i++;
                        } else {
                            break;
                        }
                    } else {
                        sb.append(d);
                    }
                }
                if (sb.isEmpty()) throw SqlException.syntax("zero-length delimited identifier at position " + start);
                out.add(new Token(Kind.QUOTED_IDENT, sb.toString(), start));
            } else if (c == '\'') {
                int start = i;
                StringBuilder sb = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n) throw SqlException.syntax("unterminated quoted string at position " + start);
                    char d = sql.charAt(i++);
                    if (d == '\'') {
                        if (i < n && sql.charAt(i) == '\'') {
                            sb.append('\'');
                            i++;
                        } else {
                            break;
                        }
                    } else {
                        sb.append(d);
                    }
                }
                out.add(new Token(Kind.STRING, sb.toString(), start));
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)))) {
                int start = i;
                while (i < n && Character.isDigit(sql.charAt(i))) i++;
                if (i < n && sql.charAt(i) == '.' && !(i + 1 < n && sql.charAt(i + 1) == '.')) {
                    i++;
                    while (i < n && Character.isDigit(sql.charAt(i))) i++;
                }
                if (i < n && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
                    int save = i;
                    i++;
                    if (i < n && (sql.charAt(i) == '+' || sql.charAt(i) == '-')) i++;
                    if (i < n && Character.isDigit(sql.charAt(i))) {
                        while (i < n && Character.isDigit(sql.charAt(i))) i++;
                    } else {
                        i = save;
                    }
                }
                if (i < n && (Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    throw SqlException.syntax("trailing junk after numeric literal at position " + start);
                }
                out.add(new Token(Kind.NUMBER, sql.substring(start, i), start));
            } else if (c == '$' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
                int start = i++;
                while (i < n && Character.isDigit(sql.charAt(i))) i++;
                out.add(new Token(Kind.PARAM, sql.substring(start + 1, i), start));
            } else {
                String op = operator(sql, i);
                if (op == null) throw SqlException.syntax("syntax error at or near \"" + c + "\" (position " + i + ")");
                out.add(new Token(Kind.OP, op.equals("!=") ? "<>" : op, i));
                i += op.length();
            }
        }
        out.add(new Token(Kind.EOF, "", n));
        return out;
    }

    private static String operator(String sql, int i) {
        for (String op : new String[] {"<>", "!=", "<=", ">=", "||", "::"}) {
            if (sql.startsWith(op, i)) return op;
        }
        char c = sql.charAt(i);
        return "=<>+-*/%(),;.[]".indexOf(c) >= 0 ? String.valueOf(c) : null;
    }
}
