package ubc.cosc322;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AmazonsBoardState {
    public static final int BOARD_DIMENSION = 11;
    public static final int MIN_INDEX = 1;
    public static final int MAX_INDEX = 10;

    public static final int EMPTY = 0;
    public static final int BLACK = 1;
    public static final int WHITE = 2;
    public static final int ARROW = 3;
    public static final int NONE = 0;

    public static int opponent(int color) {
        return color == BLACK ? WHITE : BLACK;
    }

    private static final int INF = 1_000_000;
    private static final int[][] DIRECTIONS = {
        {-1, -1}, {-1, 0}, {-1, 1},
        {0, -1},           {0, 1},
        {1, -1},  {1, 0},  {1, 1}
    };

    private final int[][] board;

    // Queen position cache: queenRows[player][i], queenCols[player][i] are the i-th queen's coords.
    // Indexed by player (BLACK=1, WHITE=2). Up to 4 queens per side.
    private final int[][] queenRows = new int[3][4];
    private final int[][] queenCols = new int[3][4];
    private final int[] queenCount = new int[3];

    public AmazonsBoardState() {
        this.board = new int[BOARD_DIMENSION][BOARD_DIMENSION];
    }

    public static AmazonsBoardState fromServerState(List<Integer> encodedState) {
        if (encodedState == null || encodedState.size() < BOARD_DIMENSION * BOARD_DIMENSION) {
            throw new IllegalArgumentException("Expected 121 integers for board state");
        }

        AmazonsBoardState state = new AmazonsBoardState();
        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                state.board[row][col] = encodedState.get(BOARD_DIMENSION * row + col).intValue();
            }
        }
        state.rebuildQueenCache();
        return state;
    }

    public AmazonsBoardState copy() {
        AmazonsBoardState copy = new AmazonsBoardState();
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            System.arraycopy(board[row], 0, copy.board[row], 0, BOARD_DIMENSION);
        }
        // Copy queen position cache
        int bc = queenCount[BLACK];
        int wc = queenCount[WHITE];
        System.arraycopy(queenRows[BLACK], 0, copy.queenRows[BLACK], 0, bc);
        System.arraycopy(queenCols[BLACK], 0, copy.queenCols[BLACK], 0, bc);
        System.arraycopy(queenRows[WHITE], 0, copy.queenRows[WHITE], 0, wc);
        System.arraycopy(queenCols[WHITE], 0, copy.queenCols[WHITE], 0, wc);
        copy.queenCount[BLACK] = bc;
        copy.queenCount[WHITE] = wc;
        return copy;
    }

    public int get(int row, int col) {
        return board[row][col];
    }

    public void set(int row, int col, int value) {
        ensurePlayable(row, col);
        int old = board[row][col];
        if (old == value) return;
        board[row][col] = value;
        if (old == BLACK || old == WHITE) {
            removeFromQueenCache(old, row, col);
        }
        if (value == BLACK || value == WHITE) {
            addToQueenCache(value, row, col);
        }
    }

    public ArrayList<Integer> toServerState() {
        ArrayList<Integer> encoded = new ArrayList<Integer>(BOARD_DIMENSION * BOARD_DIMENSION);
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            for (int col = 0; col < BOARD_DIMENSION; col++) {
                encoded.add(row >= MIN_INDEX && col >= MIN_INDEX ? board[row][col] : 0);
            }
        }
        return encoded;
    }

    public int inferSideToMove() {
        return countArrows() % 2 == 0 ? BLACK : WHITE;
    }

    public int countArrows() {
        int arrows = 0;
        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                if (board[row][col] == ARROW) {
                    arrows++;
                }
            }
        }
        return arrows;
    }

    public boolean hasAnyMoves(int player) {
        int count = queenCount[player];
        for (int i = 0; i < count; i++) {
            if (queenHasDestination(queenRows[player][i], queenCols[player][i])) {
                return true;
            }
        }
        return false;
    }

    public List<AmazonsMove> generateMoves(int player) {
        ArrayList<AmazonsMove> moves = new ArrayList<AmazonsMove>();
        int count = queenCount[player];
        for (int i = 0; i < count; i++) {
            addMovesForQueen(player, queenRows[player][i], queenCols[player][i], moves);
        }
        return moves;
    }

    public void applyMove(AmazonsMove move, int player) {
        if (board[move.getFromRow()][move.getFromCol()] != player) {
            throw new IllegalArgumentException("Source square does not contain the expected piece");
        }
        board[move.getFromRow()][move.getFromCol()] = EMPTY;
        board[move.getToRow()][move.getToCol()] = player;
        board[move.getArrowRow()][move.getArrowCol()] = ARROW;
        // Update queen cache: move from -> to
        int count = queenCount[player];
        int fr = move.getFromRow(), fc = move.getFromCol();
        int tr = move.getToRow(), tc = move.getToCol();
        for (int i = 0; i < count; i++) {
            if (queenRows[player][i] == fr && queenCols[player][i] == fc) {
                queenRows[player][i] = tr;
                queenCols[player][i] = tc;
                break;
            }
        }
    }

    public void undoMove(AmazonsMove move, int player) {
        board[move.getArrowRow()][move.getArrowCol()] = EMPTY;
        board[move.getToRow()][move.getToCol()] = EMPTY;
        board[move.getFromRow()][move.getFromCol()] = player;
        // Update queen cache: move to -> from
        int count = queenCount[player];
        int fr = move.getFromRow(), fc = move.getFromCol();
        int tr = move.getToRow(), tc = move.getToCol();
        for (int i = 0; i < count; i++) {
            if (queenRows[player][i] == tr && queenCols[player][i] == tc) {
                queenRows[player][i] = fr;
                queenCols[player][i] = fc;
                break;
            }
        }
    }

    public int countQueenDestinations(int player) {
        int destinations = 0;
        int count = queenCount[player];
        for (int i = 0; i < count; i++) {
            destinations += countDestinationsFrom(queenRows[player][i], queenCols[player][i]);
        }
        return destinations;
    }

    public int countActiveQueens(int player) {
        int active = 0;
        int count = queenCount[player];
        for (int i = 0; i < count; i++) {
            if (queenHasDestination(queenRows[player][i], queenCols[player][i])) {
                active++;
            }
        }
        return active;
    }

    public int countDestinationsFrom(int row, int col) {
        int destinations = 0;
        for (int[] direction : DIRECTIONS) {
            int nextRow = row + direction[0];
            int nextCol = col + direction[1];
            while (isPlayable(nextRow, nextCol) && board[nextRow][nextCol] == EMPTY) {
                destinations++;
                nextRow += direction[0];
                nextCol += direction[1];
            }
        }
        return destinations;
    }

    public int evaluate(int perspective) {
        int opponent = opponent(perspective);
        int[][] myDistances = queenDistances(perspective);
        int[][] opponentDistances = queenDistances(opponent);

        int territoryScore = 0;
        int contestedScore = 0;
        int myReachable = 0;
        int opponentReachable = 0;

        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                if (board[row][col] != EMPTY) {
                    continue;
                }

                int myDistance = myDistances[row][col];
                int opponentDistance = opponentDistances[row][col];
                boolean myFinite = myDistance < INF;
                boolean opponentFinite = opponentDistance < INF;

                if (myFinite) {
                    myReachable++;
                }
                if (opponentFinite) {
                    opponentReachable++;
                }

                if (myFinite && !opponentFinite) {
                    territoryScore++;
                } else if (!myFinite && opponentFinite) {
                    territoryScore--;
                } else if (myFinite && opponentFinite) {
                    if (myDistance < opponentDistance) {
                        territoryScore++;
                    } else if (opponentDistance < myDistance) {
                        territoryScore--;
                    } else {
                        contestedScore += contestedPressure(row, col, perspective, opponent);
                    }
                }
            }
        }

        int mobilityScore = countQueenDestinations(perspective) - countQueenDestinations(opponent);
        int activeQueenScore = countActiveQueens(perspective) - countActiveQueens(opponent);
        int reachabilityScore = myReachable - opponentReachable;
        int trapScore = countTrappedQueens(opponent) - countTrappedQueens(perspective);

        return territoryScore * 100
            + mobilityScore * 4
            + activeQueenScore * 15
            + contestedScore * 2
            + reachabilityScore
            + trapScore * 120;
    }

    private int contestedPressure(int row, int col, int myPlayer, int oppPlayer) {
        int myPressure = nearbyQueenPressure(row, col, myPlayer);
        int opponentPressure = nearbyQueenPressure(row, col, oppPlayer);
        if (myPressure == opponentPressure) {
            return 0;
        }
        return myPressure > opponentPressure ? 1 : -1;
    }

    private int nearbyQueenPressure(int targetRow, int targetCol, int player) {
        int pressure = 0;
        int count = queenCount[player];
        for (int i = 0; i < count; i++) {
            int distance = Math.max(
                Math.abs(queenRows[player][i] - targetRow),
                Math.abs(queenCols[player][i] - targetCol)
            );
            if (distance <= 2) {
                pressure += 3 - distance;
            }
        }
        return pressure;
    }

    // BFS-based queen-move distances from all queens of the given player.
    private int[][] queenDistances(int player) {
        int[][] distances = new int[BOARD_DIMENSION][BOARD_DIMENSION];
        for (int row = 0; row < BOARD_DIMENSION; row++) {
            Arrays.fill(distances[row], INF);
        }

        ArrayDeque<int[]> frontier = new ArrayDeque<int[]>();
        int count = queenCount[player];
        for (int i = 0; i < count; i++) {
            int qr = queenRows[player][i];
            int qc = queenCols[player][i];
            distances[qr][qc] = 0;
            frontier.addLast(new int[] {qr, qc});
        }

        while (!frontier.isEmpty()) {
            int[] current = frontier.removeFirst();
            int nextDistance = distances[current[0]][current[1]] + 1;
            for (int[] direction : DIRECTIONS) {
                int row = current[0] + direction[0];
                int col = current[1] + direction[1];
                while (isPlayable(row, col) && board[row][col] == EMPTY) {
                    if (nextDistance < distances[row][col]) {
                        distances[row][col] = nextDistance;
                        frontier.addLast(new int[] {row, col});
                    }
                    row += direction[0];
                    col += direction[1];
                }
            }
        }

        return distances;
    }

    private void addMovesForQueen(int player, int fromRow, int fromCol, List<AmazonsMove> moves) {
        for (int[] direction : DIRECTIONS) {
            int toRow = fromRow + direction[0];
            int toCol = fromCol + direction[1];
            while (isPlayable(toRow, toCol) && board[toRow][toCol] == EMPTY) {
                board[fromRow][fromCol] = EMPTY;
                board[toRow][toCol] = player;
                addArrowMoves(fromRow, fromCol, toRow, toCol, moves);
                board[toRow][toCol] = EMPTY;
                board[fromRow][fromCol] = player;

                toRow += direction[0];
                toCol += direction[1];
            }
        }
    }

    private void addArrowMoves(int fromRow, int fromCol, int toRow, int toCol, List<AmazonsMove> moves) {
        for (int[] direction : DIRECTIONS) {
            int arrowRow = toRow + direction[0];
            int arrowCol = toCol + direction[1];
            while (isPlayable(arrowRow, arrowCol) && board[arrowRow][arrowCol] == EMPTY) {
                moves.add(new AmazonsMove(fromRow, fromCol, toRow, toCol, arrowRow, arrowCol));
                arrowRow += direction[0];
                arrowCol += direction[1];
            }
        }
    }

    private int countTrappedQueens(int player) {
        int trapped = 0;
        int count = queenCount[player];
        for (int i = 0; i < count; i++) {
            if (!queenHasDestination(queenRows[player][i], queenCols[player][i])) {
                trapped++;
            }
        }
        return trapped;
    }

    private boolean queenHasDestination(int row, int col) {
        for (int[] direction : DIRECTIONS) {
            int nextRow = row + direction[0];
            int nextCol = col + direction[1];
            if (isPlayable(nextRow, nextCol) && board[nextRow][nextCol] == EMPTY) {
                return true;
            }
        }
        return false;
    }

    private List<int[]> getQueenPositions(int player) {
        int count = queenCount[player];
        ArrayList<int[]> queens = new ArrayList<int[]>(count);
        for (int i = 0; i < count; i++) {
            queens.add(new int[] {queenRows[player][i], queenCols[player][i]});
        }
        return queens;
    }

    private void rebuildQueenCache() {
        queenCount[BLACK] = 0;
        queenCount[WHITE] = 0;
        for (int row = MIN_INDEX; row <= MAX_INDEX; row++) {
            for (int col = MIN_INDEX; col <= MAX_INDEX; col++) {
                int cell = board[row][col];
                if (cell == BLACK || cell == WHITE) {
                    int idx = queenCount[cell]++;
                    queenRows[cell][idx] = row;
                    queenCols[cell][idx] = col;
                }
            }
        }
    }

    private void addToQueenCache(int player, int row, int col) {
        int idx = queenCount[player]++;
        queenRows[player][idx] = row;
        queenCols[player][idx] = col;
    }

    private void removeFromQueenCache(int player, int row, int col) {
        int count = queenCount[player];
        for (int i = 0; i < count; i++) {
            if (queenRows[player][i] == row && queenCols[player][i] == col) {
                int last = count - 1;
                queenRows[player][i] = queenRows[player][last];
                queenCols[player][i] = queenCols[player][last];
                queenCount[player]--;
                return;
            }
        }
    }

    private static boolean isPlayable(int row, int col) {
        return row >= MIN_INDEX && row <= MAX_INDEX && col >= MIN_INDEX && col <= MAX_INDEX;
    }

    private static void ensurePlayable(int row, int col) {
        if (!isPlayable(row, col)) {
            throw new IllegalArgumentException("Coordinate out of bounds: (" + row + "," + col + ")");
        }
    }
}
