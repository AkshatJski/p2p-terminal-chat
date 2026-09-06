package com.p2p.chat.game;

/**
 * Two-player Tic-Tac-Toe on a 3x3 grid. First to {@code @ttt start} is X, second
 * to {@code @ttt join} is O; anyone else is a spectator. Moves are
 * {@code @ttt <row>-<col>} with coordinates 1-3.
 */
public final class TicTacToe implements Game {
    private static final String X = "X";
    private static final String O = "O";

    private char[][] b = new char[3][3];
    private String xPlayer;
    private String oPlayer;
    private String turn;
    private boolean over;
    private String result;

    @Override
    public String handle(String user, String args) {
        String a = args.trim();
        if (a.isEmpty()) {
            return usage();
        }
        String[] p = a.split("[\\s\\-,;]+");
        String act = p[0].toLowerCase();

        if (act.equals("start") || act.equals("new")) {
            reset();
            xPlayer = user;
            turn = X;
            return board("Game started. " + user + " is X. Another player: @ttt join");
        }
        if (act.equals("join")) {
            if (xPlayer == null) {
                return "Start the game first: @ttt start";
            }
            if (oPlayer != null) {
                return "The two player slots are already taken (" + xPlayer + " ⚡ " + oPlayer + ").";
            }
            if (user.equals(xPlayer)) {
                return "You are already X, " + user + ". Waiting for an opponent: @ttt join";
            }
            oPlayer = user;
            return board("Game started. " + xPlayer + " (X) vs " + user + " (O). " +
                    "It's " + xPlayer + "'s turn.");
        }
        if (act.equals("quit") || act.equals("reset")) {
            String who = result != null ? " (" + result + ")" : "";
            reset();
            return "Game reset" + who + ". Start a new one: @ttt start";
        }

        // A move: digits only.
        if (xPlayer == null) {
            return "No game running. Start one: @ttt start";
        }
        byte[] digits = digits(a);
        if (digits.length < 2 || digits[0] < 1 || digits[0] > 3 || digits[1] < 1 || digits[1] > 3) {
            return "Move format: @ttt <row>-<col> (1-3), e.g. @ttt 2 2";
        }
        if (over) {
            return "Game is over: " + result + ". Rematch: @ttt reset";
        }
        if (!user.equals(turnPlayer())) {
            return "Not your turn, " + user + ". It's " + turnPlayer() + "'s turn.";
        }
        int r = digits[0] - 1;
        int c = digits[1] - 1;
        if (b[r][c] != 0) {
            return "That cell is taken, " + user + ".";
        }
        b[r][c] = (turn.equals(X)) ? 'X' : 'O';
        if (win(r, c)) {
            over = true;
            result = turnPlayer() + " wins!";
        } else if (draw()) {
            over = true;
            result = "draw";
        } else {
            turn = turn.equals(X) ? O : X;
        }
        return board(turnLine());
    }

    private String turnLine() {
        if (over) {
            return result;
        }
        return "It's " + turnPlayer() + "'s turn (" + turn + ").";
    }

    private String turnPlayer() {
        return turn.equals(X) ? xPlayer : oPlayer;
    }

    private boolean win(int r, int c) {
        char v = b[r][c];
        int[] dr = {0, 1, 1, 1};
        int[] dc = {1, 0, 1, -1};
        for (int i = 0; i < 4; i++) {
            int cnt = 1;
            for (int step = 1; step < 3; step++) {
                int nr = r + dr[i] * step;
                int nc = c + dc[i] * step;
                if (nr < 0 || nr > 2 || nc < 0 || nc > 2 || b[nr][nc] != v) break;
                cnt++;
            }
            for (int step = 1; step < 3; step++) {
                int nr = r - dr[i] * step;
                int nc = c - dc[i] * step;
                if (nr < 0 || nr > 2 || nc < 0 || nc > 2 || b[nr][nc] != v) break;
                cnt++;
            }
            if (cnt >= 3) return true;
        }
        return false;
    }

    private boolean draw() {
        for (char[] row : b) {
            for (char cell : row) {
                if (cell == 0) return false;
            }
        }
        return true;
    }

    private String board(String note) {
        StringBuilder sb = new StringBuilder(note).append("\n");
        sb.append("       1   2   3\n");
        for (int r = 0; r < 3; r++) {
            sb.append("  ").append(r + 1).append("   ");
            for (int c = 0; c < 3; c++) {
                char cell = b[r][c];
                sb.append(' ').append(cell == 0 ? '.' : cell);
                if (c < 2) sb.append(" |");
            }
            if (r < 2) sb.append("\n      ---+---+---");
            sb.append('\n');
        }
        return sb.toString();
    }

    private void reset() {
        b = new char[3][3];
        xPlayer = null;
        oPlayer = null;
        turn = null;
        over = false;
        result = null;
    }

    private static byte[] digits(String s) {
        java.util.List<Byte> out = new java.util.ArrayList<>(4);
        for (char ch : s.toCharArray()) {
            if (ch >= '1' && ch <= '9') {
                out.add((byte) (ch - '0'));
            }
        }
        byte[] arr = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static String usage() {
        return "Tic-Tac-Toe:\n  @ttt start        become X\n  @ttt join         become O\n"
                + "  @ttt <row>-<col>  place your mark (1-3), e.g. @ttt 2 2\n  @ttt reset        restart";
    }
}